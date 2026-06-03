package com.niranjan.max20.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager
import com.niranjan.max20.data.TimerPhase
import com.niranjan.max20.data.TimerState
import com.niranjan.max20.data.TimerStateStore
import com.niranjan.max20.ui.LockdownActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TimeEnforcerService — the process-level heart of the 20/20 cycle.
 *
 * Survival strategy for Vivo i-Manager:
 *   1. startForeground() is called at the VERY FIRST line of onStartCommand(),
 *      before any store reads. Android 14 gives exactly 10 seconds before ANR;
 *      Vivo's i-Manager gives less. We do not wait.
 *   2. PARTIAL_WAKE_LOCK prevents the CPU from sleeping between timer ticks.
 *      Without this, Doze Mode on Vivo silently suspends the coroutine.
 *   3. AlarmManager.setExactAndAllowWhileIdle() schedules the phase
 *      transition even if the device enters deep sleep. The alarm fires in
 *      Doze, re-starts the service, and snaps to the next phase.
 *   4. onTaskRemoved() fires when the user swipes the app from Recents.
 *      We schedule a 500ms resurrection alarm before returning so the
 *      service restarts before Vivo's kill cooldown elapses.
 *   5. onDestroy() inspects state synchronously and schedules a 5-second
 *      resurrection alarm if killed during an active lockdown.
 *      START_STICKY causes the OS to restart us as well.
 */
class TimeEnforcerService : Service() {

    private companion object {
        // Tolerance for treating a phase as "expired" at the boundary, absorbing the
        // small clock skew between the exact alarm and the local tick counter.
        const val TRANSITION_GRACE_MS = 2_000L
    }

    private lateinit var store: TimerStateStore
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alarmManager: AlarmManager
    private lateinit var notificationManager: NotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    // Serializes phase transitions. The AlarmManager transition and the per-second
    // tick safety-net can both fire at the phase boundary; without this they could
    // each flip the phase, producing a spurious double transition (e.g.
    // WORK→LOCKDOWN→WORK in the same instant).
    private val transitionMutex = Mutex()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConstants.TAG_ENFORCER, "onCreate: Initializing TimeEnforcerService")

        store = TimerStateStore.getInstance(this)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Max20::TimeEnforcerWakeLock"
        ).apply { setReferenceCounted(false) }

        createNotificationChannel()
        AppStateManager.onServiceLifecycle(alive = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConstants.TAG_ENFORCER,
            "onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")

        // ══ RULE #1: startForeground MUST be the first substantive call. ═══
        promoteForeground("20/20 Rule — initializing")
        acquireWakeLock()

        when (intent?.action) {
            AppConstants.ACTION_PHASE_TRANSITION -> {
                Log.i(AppConstants.TAG_ENFORCER, "Phase-transition alarm fired")
                handlePhaseTransitionAsync()
            }
            AppConstants.ACTION_START_LOCKDOWN -> {
                Log.i(AppConstants.TAG_ENFORCER, "Explicit lockdown-start command received")
                serviceScope.launch { transitionMutex.withLock { transitionToLockdown() } }
            }
            AppConstants.ACTION_END_LOCKDOWN -> {
                Log.i(AppConstants.TAG_ENFORCER, "Explicit lockdown-end command received")
                serviceScope.launch { transitionMutex.withLock { transitionToWork() } }
            }
            else -> {
                Log.i(AppConstants.TAG_ENFORCER, "Service (re)started — resuming from DE storage")
                resumeFromPersistedState()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(AppConstants.TAG_ENFORCER,
            "onTaskRemoved: App swept from Recents — scheduling 500ms resurrection alarm")
        scheduleAlarm(
            delayMs = 500L,
            action = null,
            requestCode = AppConstants.ALARM_REQUEST_RESURRECTION
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(AppConstants.TAG_ENFORCER,
            "onDestroy: Service being destroyed — inspecting state before death")

        AppStateManager.onServiceLifecycle(alive = false)
        tickJob?.cancel()

        // Synchronous read required: serviceScope is about to be cancelled
        val state = store.getStateSync()
        if (state?.isActive == true) {
            Log.e(AppConstants.TAG_ENFORCER,
                "CRITICAL: Killed during active ${state.phase} — scheduling 5s resurrection alarm")
            scheduleAlarm(
                delayMs = 5_000L,
                action = null,
                requestCode = AppConstants.ALARM_REQUEST_RESURRECTION
            )
        } else {
            Log.i(AppConstants.TAG_ENFORCER, "Destroyed while idle — no resurrection needed")
        }

        serviceScope.cancel()

        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.i(AppConstants.TAG_ENFORCER, "WakeLock released in onDestroy")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── State Machine ──────────────────────────────────────────────────────

    private fun resumeFromPersistedState() {
        serviceScope.launch {
            // Hold the transition lock for the whole resume so a phase-transition alarm
            // arriving on a concurrent onStartCommand can't race the resume decision.
            transitionMutex.withLock {
            val state = store.getState()

            if (state == null || !state.isActive) {
                Log.i(AppConstants.TAG_ENFORCER, "No active state found — starting fresh WORK phase")
                startNewWorkPhase()
                return@withLock
            }

            if (state.isExpired) {
                Log.w(AppConstants.TAG_ENFORCER,
                    "Phase ${state.phase} expired while service was dead " +
                    "(elapsed=${state.elapsedMs}ms > duration=${state.phaseDurationMs}ms) " +
                    "— transitioning immediately")
                when (state.phase) {
                    TimerPhase.WORK     -> transitionToLockdown()
                    TimerPhase.LOCKDOWN -> transitionToWork()
                }
                return@withLock
            }

            Log.i(AppConstants.TAG_ENFORCER,
                "Resuming ${state.phase} with ${state.remainingMs / 1000}s remaining")
            AppStateManager.onPhaseChanged(state.phase)
            scheduleAlarm(delayMs = state.remainingMs, action = AppConstants.ACTION_PHASE_TRANSITION)
            if (state.phase == TimerPhase.LOCKDOWN) {
                withContext(Dispatchers.Main) { enforceLockdownUI() }
            }
            startTickJob(state.phase, state.remainingMs)
            }
        }
    }

    private fun handlePhaseTransitionAsync() {
        serviceScope.launch {
            transitionMutex.withLock {
                val state = store.getState()
                if (state == null || !state.isActive) {
                    Log.i(AppConstants.TAG_ENFORCER,
                        "handlePhaseTransition: no active state — starting fresh WORK phase")
                    startNewWorkPhase()
                    return@withLock
                }

                // Idempotency guard against the alarm + tick-safety-net race: a phase
                // is only advanced when the CURRENT phase has actually run its course.
                // A concurrent trigger that arrives after the first transition will see
                // a freshly-started (non-expired) phase and is ignored.
                if (state.remainingMs > TRANSITION_GRACE_MS) {
                    Log.d(AppConstants.TAG_ENFORCER,
                        "handlePhaseTransition: ${state.phase} not expired " +
                        "(remaining=${state.remainingMs}ms) — duplicate trigger ignored")
                    return@withLock
                }

                Log.i(AppConstants.TAG_ENFORCER, "handlePhaseTransition: advancing from ${state.phase}")
                when (state.phase) {
                    TimerPhase.WORK     -> transitionToLockdown()
                    TimerPhase.LOCKDOWN -> transitionToWork()
                }
            }
        }
    }

    private suspend fun startNewWorkPhase() {
        val state = TimerState(
            phase             = TimerPhase.WORK,
            phaseStartEpochMs = System.currentTimeMillis(),
            phaseDurationMs   = AppConstants.TIMER_WORK_MS,
            isActive          = true
        )
        Log.i(AppConstants.TAG_ENFORCER,
            "Starting WORK phase — ${AppConstants.TIMER_WORK_MS / 60_000}min usage window begins now")
        store.saveState(state)
        AppStateManager.onPhaseChanged(TimerPhase.WORK)
        scheduleAlarm(delayMs = AppConstants.TIMER_WORK_MS, action = AppConstants.ACTION_PHASE_TRANSITION)
        startTickJob(TimerPhase.WORK, AppConstants.TIMER_WORK_MS)
        withContext(Dispatchers.Main) {
            updateNotification("Work — 20:00 remaining")
            releaseLockdownUI()
        }
    }

    private suspend fun transitionToLockdown() {
        val state = TimerState(
            phase             = TimerPhase.LOCKDOWN,
            phaseStartEpochMs = System.currentTimeMillis(),
            phaseDurationMs   = AppConstants.TIMER_LOCKDOWN_MS,
            isActive          = true
        )
        Log.i(AppConstants.TAG_ENFORCER,
            "═══ TRANSITIONING TO LOCKDOWN — blocking all UI immediately ═══")
        store.saveState(state)
        AppStateManager.onPhaseChanged(TimerPhase.LOCKDOWN)
        scheduleAlarm(delayMs = AppConstants.TIMER_LOCKDOWN_MS, action = AppConstants.ACTION_PHASE_TRANSITION)
        startTickJob(TimerPhase.LOCKDOWN, AppConstants.TIMER_LOCKDOWN_MS)
        withContext(Dispatchers.Main) {
            updateNotification("LOCKDOWN — 20:00 remaining")
            enforceLockdownUI()
        }
    }

    private suspend fun transitionToWork() {
        Log.i(AppConstants.TAG_ENFORCER, "Lockdown complete — releasing to WORK phase")
        startNewWorkPhase()
    }

    // ── UI Enforcement Commands (Main Thread) ──────────────────────────────

    private fun enforceLockdownUI() {
        Log.i(AppConstants.TAG_ENFORCER, "enforceLockdownUI: broadcasting + starting KioskOverlay")
        sendBroadcast(Intent(AppConstants.ACTION_START_LOCKDOWN).setPackage(packageName))
        // Both the FGS start and the activity start can be rejected by background-start
        // restrictions on modern Android/OEM builds. The broadcast above already drives
        // the (already-running) overlay and activity, so a failure here is non-fatal.
        runCatching {
            startForegroundService(
                Intent(this, KioskOverlayService::class.java)
                    .apply { action = AppConstants.ACTION_START_LOCKDOWN }
            )
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_ENFORCER,
                "Could not start KioskOverlayService (${ex.javaClass.simpleName}: ${ex.message})")
        }
        runCatching {
            startActivity(
                Intent(this, LockdownActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_ENFORCER,
                "Could not launch LockdownActivity (${ex.javaClass.simpleName}: ${ex.message}) — " +
                "overlay provides coverage")
        }
    }

    private fun releaseLockdownUI() {
        Log.i(AppConstants.TAG_ENFORCER, "releaseLockdownUI: stopping KioskOverlay + broadcasting unlock")
        sendBroadcast(Intent(AppConstants.ACTION_END_LOCKDOWN).setPackage(packageName))
        stopService(Intent(this, KioskOverlayService::class.java))
    }

    // ── Tick Coroutine ─────────────────────────────────────────────────────

    private fun startTickJob(phase: TimerPhase, initialRemainingMs: Long) {
        tickJob?.cancel()
        var remainingMs = initialRemainingMs
        val label = if (phase == TimerPhase.LOCKDOWN) "LOCKDOWN" else "Work"

        tickJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                remainingMs -= 1_000L

                if (remainingMs <= 0L) {
                    Log.w(AppConstants.TAG_ENFORCER,
                        "Tick safety-net: $phase expired — triggering transition")
                    handlePhaseTransitionAsync()
                    break
                }

                val mins = remainingMs / 60_000L
                val secs = (remainingMs % 60_000L) / 1_000L
                withContext(Dispatchers.Main) {
                    updateNotification(
                        "$label — ${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')} remaining"
                    )
                }
            }
        }
    }

    // ── Alarm Scheduling ───────────────────────────────────────────────────

    private fun scheduleAlarm(delayMs: Long, action: String?, requestCode: Int = AppConstants.ALARM_REQUEST_TRANSITION) {
        val intent = Intent(this, TimeEnforcerService::class.java).apply {
            if (action != null) this.action = action
        }
        val pendingIntent = PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMs = System.currentTimeMillis() + delayMs
        alarmManager.cancel(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                Log.i(AppConstants.TAG_ENFORCER,
                    "scheduleAlarm: setExactAndAllowWhileIdle in ${delayMs / 1000}s [action=$action]")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                Log.w(AppConstants.TAG_ENFORCER,
                    "scheduleAlarm: SCHEDULE_EXACT_ALARM not granted — using inexact fallback")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            Log.i(AppConstants.TAG_ENFORCER,
                "scheduleAlarm: setExactAndAllowWhileIdle in ${delayMs / 1000}s [action=$action]")
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            Log.i(AppConstants.TAG_ENFORCER,
                "scheduleAlarm: setExact in ${delayMs / 1000}s [action=$action]")
        }
    }

    // ── WakeLock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            val maxHoldMs = AppConstants.TIMER_WORK_MS + AppConstants.TIMER_LOCKDOWN_MS + 120_000L
            wakeLock.acquire(maxHoldMs)
            Log.i(AppConstants.TAG_ENFORCER, "WakeLock acquired (max hold = ${maxHoldMs / 60_000}min)")
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            AppConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent 20/20 Rule timer"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        // When the OS restarts us from the background (resurrection alarm, START_STICKY,
        // boot), startForeground can throw ForegroundServiceStartNotAllowedException on
        // Android 12+. Swallowing it keeps the process alive so START_STICKY / the next
        // legal entry point can promote us, rather than crash-looping.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    AppConstants.NOTIFICATION_ID_ENFORCER,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(AppConstants.NOTIFICATION_ID_ENFORCER, notification)
            }
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_ENFORCER,
                "startForeground failed (${ex.javaClass.simpleName}: ${ex.message}) — " +
                "background-start restriction")
        }
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(AppConstants.NOTIFICATION_ID_ENFORCER, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification =
        NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("20/20 Rule Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}

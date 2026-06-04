package com.niranjan.max20.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager
import kotlinx.coroutines.*

/**
 * KioskOverlayService — the visual fallback shield.
 *
 * Role in the layered defense (from research report §1, API comparison table):
 * "Acts as the ultimate safety net and visual shield. Deployed dynamically as
 * a fallback only if the system detects that the primary Lock Task Mode has
 * been compromised or focus is unexpectedly lost."
 *
 * Technical implementation:
 *   - Draws a TYPE_APPLICATION_OVERLAY window using WindowManager. This type
 *     renders above ALL other windows, including Lock Task Mode exceptions,
 *     notification shade content, and system dialogs.
 *   - FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS extend the window into the
 *     status bar and navigation bar cutout areas, preventing peek-through.
 *   - FLAG_NOT_FOCUSABLE keeps navigation key events in the accessibility
 *     service chain, preventing the overlay from swallowing back presses it
 *     shouldn't handle.
 *   - Touch events on the overlay VIEW are consumed by the view's onTouch
 *     listener, blocking interaction with any app beneath.
 *   - The overlay is hidden (not destroyed) during active phone calls so the
 *     InCallService can render without the visual shield on top.
 */
class KioskOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var timerTextView: TextView? = null
    private var emergencyButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())

    private val store by lazy { com.niranjan.max20.data.TimerStateStore.getInstance(this) }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    private val lockdownStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AppConstants.ACTION_START_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_OVERLAY, "Received START_LOCKDOWN — showing overlay")
                    showOverlay()
                }
                AppConstants.ACTION_END_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_OVERLAY, "Received END_LOCKDOWN — removing overlay")
                    removeOverlay()
                    stopSelf()
                }
                AppConstants.ACTION_CALL_STARTED -> {
                    Log.i(AppConstants.TAG_OVERLAY, "Call started — hiding overlay for call UI")
                    overlayView?.visibility = View.GONE
                }
                AppConstants.ACTION_CALL_ENDED -> {
                    Log.i(AppConstants.TAG_OVERLAY, "Call ended — restoring overlay")
                    if (AppStateManager.isLockdownActive) {
                        overlayView?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConstants.TAG_OVERLAY, "onCreate: KioskOverlayService initializing")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(AppConstants.ACTION_START_LOCKDOWN)
            addAction(AppConstants.ACTION_END_LOCKDOWN)
            addAction(AppConstants.ACTION_CALL_STARTED)
            addAction(AppConstants.ACTION_CALL_ENDED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockdownStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // Pre-33 has no NOT_EXPORTED flag; gate delivery with the signature
            // permission so only this app can reach the receiver.
            registerReceiver(lockdownStateReceiver, filter, AppConstants.PERMISSION_INTERNAL, null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConstants.TAG_OVERLAY,
            "onStartCommand: action=${intent?.action}, flags=$flags")

        // startForeground can throw ForegroundServiceStartNotAllowedException when the
        // OS started us from the background (e.g. resurrection / boot on Android 12+).
        // A crash here would defeat the very resilience this service provides, so we
        // guard it and bail out gracefully instead of letting the process die.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    AppConstants.NOTIFICATION_ID_OVERLAY,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(AppConstants.NOTIFICATION_ID_OVERLAY, buildNotification())
            }
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_OVERLAY,
                "startForeground failed (${ex.javaClass.simpleName}: ${ex.message}) — " +
                "background-start restriction; stopping overlay service")
        }
        if (promoted.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.e(AppConstants.TAG_OVERLAY,
                "SYSTEM_ALERT_WINDOW permission not granted — overlay cannot be drawn. " +
                "User must enable 'Display Over Other Apps' via VivoOptimizationHelper.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == AppConstants.ACTION_START_LOCKDOWN) {
            showOverlay()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(AppConstants.TAG_OVERLAY, "onDestroy: removing overlay and cleaning up")
        tickJob?.cancel()
        serviceScope.cancel()
        removeOverlay()
        runCatching { unregisterReceiver(lockdownStateReceiver) }.onFailure { ex ->
            Log.w(AppConstants.TAG_OVERLAY,
                "lockdownStateReceiver already unregistered: ${ex.message}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Overlay Rendering ──────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) {
            Log.d(AppConstants.TAG_OVERLAY, "Overlay already visible — skipping creation")
            overlayView?.visibility = View.VISIBLE
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.e(AppConstants.TAG_OVERLAY,
                "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = buildBlockingView()

        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
            Log.i(AppConstants.TAG_OVERLAY,
                "Overlay inflated: TYPE_APPLICATION_OVERLAY covers full screen including status/nav bars")
            startTimerTick()
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_OVERLAY,
                "WindowManager.addView failed — overlay could not be drawn: ${ex.message}")
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching {
            windowManager?.removeView(view)
            Log.i(AppConstants.TAG_OVERLAY, "Overlay removed from WindowManager")
        }.onFailure { ex ->
            Log.w(AppConstants.TAG_OVERLAY,
                "removeView failed (view may already be detached): ${ex.message}")
        }
        overlayView = null
        timerTextView = null
        emergencyButton = null
        handler.removeCallbacksAndMessages(null) // cancel any in-flight emergency hold
        tickJob?.cancel()
    }

    private fun buildBlockingView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true

            // Consume all touch events — nothing below this view is reachable
            setOnTouchListener { _, _ -> true }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = getString(com.niranjan.max20.R.string.lockdown_title)
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        timerTextView = TextView(this).apply {
            text = "20:00"
            textSize = 72f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val subtitleView = TextView(this).apply {
            text = getString(com.niranjan.max20.R.string.lockdown_subtitle)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 0)
        }

        val callButton = Button(this).apply {
            text = getString(com.niranjan.max20.R.string.emergency_call_label)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(64, 32, 64, 32)
            isClickable = true

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 64
                gravity = Gravity.CENTER_HORIZONTAL
            }

            setOnClickListener {
                Log.i(AppConstants.TAG_OVERLAY, "Emergency call button pressed during lockdown")
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Fire ACTION_DIAL (not ACTION_CALL) — opens the system dialer. Once the
                // call is placed, call-state detection sets isCallActive, which hides this
                // overlay and lets the dialer/in-call UI through.
                runCatching { startActivity(dialIntent) }.onFailure { ex ->
                    Log.e(AppConstants.TAG_OVERLAY,
                        "Failed to launch dial intent: ${ex.message}")
                }
            }
        }

        val emergencyButton = buildEmergencyButton()
        this.emergencyButton = emergencyButton

        container.addView(titleView)
        container.addView(timerTextView)
        container.addView(subtitleView)
        container.addView(callButton)
        container.addView(emergencyButton)
        root.addView(container)

        refreshEmergencyButton()

        return root
    }

    /**
     * "Emergency unlock" — hold (not tap) to confirm, then the device fully opens
     * for a fixed window. A hold (with a visible countdown) plus the daily cap is
     * the friction that keeps this a genuine safety valve rather than a one-tap
     * lockdown bypass.
     */
    private fun buildEmergencyButton(): Button {
        return Button(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5F6368"))
            setPadding(48, 24, 48, 24)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnTouchListener(EmergencyHoldListener(this))
        }
    }

    /** Reads the remaining daily allowance and updates the emergency button label. */
    private fun refreshEmergencyButton() {
        val button = emergencyButton ?: return
        serviceScope.launch {
            val remaining = runCatching {
                store.emergencyUsesRemaining(AppConstants.EMERGENCY_DAILY_LIMIT)
            }.getOrDefault(AppConstants.EMERGENCY_DAILY_LIMIT)
            if (remaining <= 0) {
                button.isEnabled = false
                button.alpha = 0.5f
                button.text = getString(com.niranjan.max20.R.string.emergency_none_left)
            } else {
                button.isEnabled = true
                button.alpha = 1f
                button.text = getString(com.niranjan.max20.R.string.emergency_hold_label, remaining)
            }
        }
    }

    /**
     * Touch listener implementing hold-to-confirm: a sustained press of
     * EMERGENCY_HOLD_MS triggers the unlock; releasing early cancels. The label
     * shows a live countdown while held.
     */
    private inner class EmergencyHoldListener(private val button: Button) : View.OnTouchListener {
        private var triggered = false
        private val fireRunnable = Runnable {
            triggered = true
            onEmergencyConfirmed()
        }
        private val countdownRunnable = object : Runnable {
            var secondsLeft = 0
            override fun run() {
                if (secondsLeft > 0) {
                    button.text = getString(com.niranjan.max20.R.string.emergency_hold_progress, secondsLeft)
                    secondsLeft--
                    handler.postDelayed(this, 1_000L)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!button.isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    triggered = false
                    handler.postDelayed(fireRunnable, AppConstants.EMERGENCY_HOLD_MS)
                    countdownRunnable.secondsLeft = (AppConstants.EMERGENCY_HOLD_MS / 1_000L).toInt()
                    handler.post(countdownRunnable)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(fireRunnable)
                    handler.removeCallbacks(countdownRunnable)
                    if (!triggered) refreshEmergencyButton() // restore label
                    return true
                }
            }
            return false
        }
    }

    private fun onEmergencyConfirmed() {
        Log.w(AppConstants.TAG_OVERLAY, "Emergency unlock confirmed via hold — requesting unlock")
        runCatching {
            startForegroundService(
                Intent(this, TimeEnforcerService::class.java)
                    .setAction(AppConstants.ACTION_EMERGENCY_UNLOCK)
            )
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_OVERLAY,
                "Failed to request emergency unlock: ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    // ── Timer Display ──────────────────────────────────────────────────────

    private fun startTimerTick() {
        tickJob?.cancel()

        tickJob = serviceScope.launch {
            while (isActive) {
                // Derive the countdown from the authoritative persisted state rather
                // than a local counter seeded with the full duration. The overlay can
                // be (re)created mid-lockdown — after a call ends, on boot resume, or
                // after a process restart — at which point a fresh 20:00 would be wrong.
                val state = store.getState()
                val remainingMs = if (state != null && state.phase == com.niranjan.max20.data.TimerPhase.LOCKDOWN) {
                    state.remainingMs
                } else {
                    AppConstants.TIMER_LOCKDOWN_MS
                }

                val mins = remainingMs / 60_000L
                val secs = (remainingMs % 60_000L) / 1_000L
                timerTextView?.text =
                    "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"

                if (remainingMs <= 0L) break
                delay(1_000L)
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            AppConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Kiosk overlay service notification"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Lockdown Active")
            .setContentText("Screen is locked for 20-minute rest period")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}

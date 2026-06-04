package com.niranjan.max20.ui

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.niranjan.max20.AppConstants
import com.niranjan.max20.R
import com.niranjan.max20.AppStateManager
import com.niranjan.max20.data.TimerPhase
import com.niranjan.max20.data.TimerState
import com.niranjan.max20.data.TimerStateStore
import com.niranjan.max20.service.TimeEnforcerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LockdownActivity — dual-role: inescapable lockdown UI + HOME launcher replacement.
 *
 * Lock Task Mode:
 *   startLockTask() is called in onResume() every time the activity returns to
 *   the foreground. If Lock Task Mode is active, the Recents button and its
 *   swipe gesture are suppressed by the OS. We handle SecurityException
 *   gracefully and rely on the overlay + accessibility service as fallback.
 *
 * Home button capture:
 *   The activity declares HOME/DEFAULT intent filters (see manifest) allowing
 *   RoleManager.ROLE_HOME to route home-button presses here. We request this
 *   role on first launch. Until the role is granted, the KioskOverlayService
 *   provides visual coverage.
 *
 * Back press neutralization:
 *   OnBackPressedDispatcher with a consuming callback — during lockdown, back
 *   presses are silently consumed. The callback is only enabled in LOCKDOWN phase.
 *
 * onUserLeaveHint():
 *   Fires when the user presses Home, Recents, or switches apps. We immediately
 *   reorder the task to front with FLAG_ACTIVITY_REORDER_TO_FRONT, snapping
 *   back before the other app's first frame renders.
 *
 * Direct Boot:
 *   The activity is declared directBootAware. If launched before credential
 *   unlock (by BootReceiver), the Room DB read is from DE storage — the
 *   activity shows the countdown immediately.
 */
class LockdownActivity : ComponentActivity() {

    private companion object {
        const val REQUEST_ROLE_HOME = 1001
    }

    private lateinit var store: TimerStateStore
    private val lockdownStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AppConstants.ACTION_START_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_ENFORCER,
                        "LockdownActivity: received START_LOCKDOWN broadcast")
                    applyLockdownWindowFlags()
                    startLockTaskSafely()
                }
                AppConstants.ACTION_END_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_ENFORCER,
                        "LockdownActivity: received END_LOCKDOWN — releasing window flags")
                    releaseWindowFlags()
                    stopLockTaskSafely()
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TimerStateStore.getInstance(this)

        configureWindowForLockdown()
        registerPhaseReceiver()
        installBackPressHandler()

        setContent {
            LockdownScreen(store = store)
        }

        ensureEnforcerServiceRunning()
        requestSystemRoles()
    }

    override fun onResume() {
        super.onResume()
        Log.d(AppConstants.TAG_ENFORCER, "LockdownActivity.onResume")

        if (AppStateManager.isLockdownActive) {
            applyLockdownWindowFlags()
            startLockTaskSafely()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(AppConstants.TAG_ENFORCER, "LockdownActivity.onPause")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AppStateManager.isLockdownActive && !AppStateManager.isCallActive) {
            Log.i(AppConstants.TAG_ENFORCER,
                "onUserLeaveHint during LOCKDOWN — reordering task to front immediately")
            val reorderIntent = Intent(this, LockdownActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(reorderIntent)
        }
    }

    override fun onDestroy() {
        Log.d(AppConstants.TAG_ENFORCER, "LockdownActivity.onDestroy")
        runCatching { unregisterReceiver(lockdownStateReceiver) }.onFailure { ex ->
            Log.w(AppConstants.TAG_ENFORCER,
                "Receiver already unregistered: ${ex.message}")
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE_HOME) {
            val granted = resultCode == RESULT_OK
            Log.i(AppConstants.TAG_ENFORCER, "ROLE_HOME request result: granted=$granted")
            if (!granted) {
                Log.w(AppConstants.TAG_ENFORCER,
                    "ROLE_HOME not granted — home button will not be captured. " +
                    "KioskOverlayService acts as coverage.")
            }
        }
    }

    // ── Window Configuration ───────────────────────────────────────────────

    private fun configureWindowForLockdown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyLockdownWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
        Log.d(AppConstants.TAG_ENFORCER, "Lockdown window flags applied — system bars hidden")
    }

    private fun releaseWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
        Log.d(AppConstants.TAG_ENFORCER, "Window flags released — system bars restored")
    }

    // ── Lock Task Mode ─────────────────────────────────────────────────────

    private fun startLockTaskSafely() {
        runCatching {
            startLockTask()
            Log.i(AppConstants.TAG_ENFORCER, "Lock Task Mode STARTED — Recents and Overview suppressed")
        }.onFailure { ex ->
            Log.w(AppConstants.TAG_ENFORCER,
                "startLockTask() failed (${ex.javaClass.simpleName}: ${ex.message}) — " +
                "no Device Owner provisioned. Relying on overlay + accessibility enforcement.")
        }
    }

    private fun stopLockTaskSafely() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val isInLockTask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }

        if (isInLockTask) {
            runCatching {
                stopLockTask()
                Log.i(AppConstants.TAG_ENFORCER, "Lock Task Mode stopped")
            }.onFailure { ex ->
                Log.w(AppConstants.TAG_ENFORCER,
                    "stopLockTask() failed: ${ex.message}")
            }
        }
    }

    // ── Back Press ────────────────────────────────────────────────────────

    private fun installBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AppStateManager.isLockdownActive) {
                    Log.d(AppConstants.TAG_ENFORCER,
                        "Back press consumed during LOCKDOWN — not finishing activity")
                    // Intentionally consumed. Do not call isEnabled = false or finish().
                } else {
                    Log.d(AppConstants.TAG_ENFORCER,
                        "Back press during WORK phase — passing through")
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ── Role Requests ──────────────────────────────────────────────────────

    /**
     * Roles must be requested one at a time: the platform only surfaces a single
     * role-request dialog. We request ROLE_HOME so the Home button returns to the
     * lockdown screen. We deliberately do NOT request ROLE_DIALER: replacing the
     * system dialer requires a full in-call UI we don't provide, and would break
     * calling. Calls are instead allowed through via call-state detection.
     */
    private fun requestSystemRoles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        requestRoleIfMissing(RoleManager.ROLE_HOME, REQUEST_ROLE_HOME)
    }

    /**
     * Launches the role-request dialog if the role isn't already held.
     * @return true if a request dialog was launched, false if the role is already
     *         held or could not be requested.
     */
    private fun requestRoleIfMissing(role: String, requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: run {
            Log.e(AppConstants.TAG_ENFORCER, "RoleManager is null — cannot request role $role")
            return false
        }
        if (!roleManager.isRoleAvailable(role) || roleManager.isRoleHeld(role)) {
            Log.i(AppConstants.TAG_ENFORCER, "Role $role already held or unavailable — skipping request")
            return false
        }
        Log.i(AppConstants.TAG_ENFORCER, "Requesting role $role")
        @Suppress("DEPRECATION")
        startActivityForResult(roleManager.createRequestRoleIntent(role), requestCode)
        return true
    }

    // ── Service Initialization ─────────────────────────────────────────────

    private fun ensureEnforcerServiceRunning() {
        if (!AppStateManager.isServiceAlive) {
            Log.i(AppConstants.TAG_ENFORCER,
                "TimeEnforcerService not alive — starting from LockdownActivity.onCreate")
            val intent = Intent(this, TimeEnforcerService::class.java)
            startForegroundService(intent)
        }
    }

    // ── Receiver Registration ──────────────────────────────────────────────

    private fun registerPhaseReceiver() {
        val filter = IntentFilter().apply {
            addAction(AppConstants.ACTION_START_LOCKDOWN)
            addAction(AppConstants.ACTION_END_LOCKDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockdownStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // Pre-33: gate delivery with the signature permission (no NOT_EXPORTED flag).
            registerReceiver(lockdownStateReceiver, filter, AppConstants.PERMISSION_INTERNAL, null)
        }
    }
}

// ── Compose UI ─────────────────────────────────────────────────────────────

@Composable
private fun LockdownScreen(store: TimerStateStore) {
    val timerState by store.observeState()
        .filterNotNull()
        .collectAsState(initial = null)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            val state = timerState
            if (state != null && state.phase == TimerPhase.LOCKDOWN && state.isActive) {
                LockdownCountdown(state, store)
            } else if (state != null && state.phase == TimerPhase.WORK && state.isActive) {
                WorkPhaseDisplay(state)
            } else {
                InitializingDisplay()
            }
        }
    }
}

@Composable
private fun LockdownCountdown(state: TimerState, store: TimerStateStore) {
    var remainingMs by remember { mutableLongStateOf(state.remainingMs) }

    LaunchedEffect(state.phaseStartEpochMs) {
        while (remainingMs > 0L) {
            delay(1_000L)
            remainingMs = state.remainingMs
        }
    }

    val mins = remainingMs / 60_000L
    val secs = (remainingMs % 60_000L) / 1_000L

    Text(
        text = "Screen Time Paused",
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}",
        color = Color.White,
        fontSize = 80.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Rest period in progress",
        color = Color(0xFFAAAAAA),
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(48.dp))
    Text(
        text = "Phone calls are available",
        color = Color(0xFF4CAF50),
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(24.dp))
    EmergencyUnlockButton(store)
}

/**
 * Hold-to-confirm emergency unlock for the Compose lockdown screen (the fallback
 * surface when the system overlay can't be drawn). Mirrors the overlay control:
 * a sustained EMERGENCY_HOLD_MS press triggers the unlock; releasing early
 * cancels; the daily allowance is shown and enforced.
 */
@Composable
private fun EmergencyUnlockButton(store: TimerStateStore) {
    val context = LocalContext.current
    var remaining by remember { mutableIntStateOf(-1) }
    var holding by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        remaining = runCatching {
            store.emergencyUsesRemaining(AppConstants.EMERGENCY_DAILY_LIMIT)
        }.getOrDefault(AppConstants.EMERGENCY_DAILY_LIMIT)
    }

    LaunchedEffect(holding) {
        if (holding) {
            var s = (AppConstants.EMERGENCY_HOLD_MS / 1_000L).toInt()
            while (s > 0 && holding) {
                secondsLeft = s
                delay(1_000L)
                s--
            }
        }
    }

    val enabled = remaining != 0
    val label = when {
        remaining == 0 -> stringResource(R.string.emergency_none_left)
        holding        -> stringResource(R.string.emergency_hold_progress, secondsLeft)
        remaining < 0  -> "Emergency unlock"
        else           -> stringResource(R.string.emergency_hold_label, remaining)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0xFF5F6368) else Color(0xFF3A3A3A))
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val releasedEarly = withTimeoutOrNull(AppConstants.EMERGENCY_HOLD_MS) {
                                tryAwaitRelease()
                            }
                            holding = false
                            if (releasedEarly == null) {
                                runCatching {
                                    context.startForegroundService(
                                        Intent(context, TimeEnforcerService::class.java)
                                            .setAction(AppConstants.ACTION_EMERGENCY_UNLOCK)
                                    )
                                }
                            }
                        }
                    )
                }
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun WorkPhaseDisplay(state: TimerState) {
    var remainingMs by remember { mutableLongStateOf(state.remainingMs) }

    LaunchedEffect(state.phaseStartEpochMs) {
        while (remainingMs > 0L) {
            delay(1_000L)
            remainingMs = state.remainingMs
        }
    }

    val mins = remainingMs / 60_000L
    val secs = (remainingMs % 60_000L) / 1_000L

    Text(
        text = "20/20 Rule Active",
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Screen time remaining",
        color = Color(0xFFAAAAAA),
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}",
        color = Color(0xFF4CAF50),
        fontSize = 64.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun InitializingDisplay() {
    CircularProgressIndicator(color = Color.White)
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "Initializing...", color = Color.White, fontSize = 16.sp)
}

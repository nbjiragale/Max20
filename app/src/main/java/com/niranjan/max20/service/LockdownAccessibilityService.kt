package com.niranjan.max20.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager
import kotlinx.coroutines.*

/**
 * LockdownAccessibilityService — the real-time, event-driven enforcement engine.
 *
 * Architecture rationale (from research report §1, Deep Dive section):
 * UsageStatsManager polls at throttled intervals, introducing a multi-second
 * window where forbidden apps are visible. This AccessibilityService receives
 * AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED directly from WindowManagerService
 * the instant any window gains focus — before the app's first frame is drawn.
 *
 * Three enforcement roles:
 *   1. Application blocker: any non-whitelisted package gains focus during
 *      lockdown → GLOBAL_ACTION_HOME
 *   2. Notification shade interceptor: com.android.systemui gains focus →
 *      GLOBAL_ACTION_BACK (collapses the shade before Quick Settings loads)
 *   3. Settings anti-tamper: com.android.settings App Info or Device Admin
 *      screen detected → GLOBAL_ACTION_HOME (blocks uninstallation)
 *
 * Self-monitoring watchdog:
 *   Vivo's i-Manager can silently disable AccessibilityServices in memory-
 *   pressure situations. A coroutine watchdog checks the service state every
 *   30 seconds and fires a high-priority alert notification if the service
 *   was killed while lockdown is active.
 */
class LockdownAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

    // Cached default-dialer package (resolved lazily, briefly memoised). Read on the
    // accessibility event hot-path, so we avoid a TelecomManager query per event.
    @Volatile private var cachedDialerPackage: String? = null
    @Volatile private var cachedDialerAtMs: Long = 0L

    private fun defaultDialerPackage(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedDialerAtMs < 60_000L && cachedDialerPackage != null) {
            return cachedDialerPackage
        }
        val pkg = runCatching {
            (getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
        }.getOrNull()
        cachedDialerPackage = pkg
        cachedDialerAtMs = now
        return pkg
    }

    private val phaseChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AppConstants.ACTION_START_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_ACCESSIBILITY,
                        "Received lockdown broadcast — enforcement mode ON")
                }
                AppConstants.ACTION_END_LOCKDOWN -> {
                    Log.i(AppConstants.TAG_ACCESSIBILITY,
                        "Received unlock broadcast — enforcement mode OFF")
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(AppConstants.TAG_ACCESSIBILITY,
            "onServiceConnected: Accessibility service bound and active")

        val filter = IntentFilter().apply {
            addAction(AppConstants.ACTION_START_LOCKDOWN)
            addAction(AppConstants.ACTION_END_LOCKDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phaseChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // Pre-33: gate delivery with the signature permission (no NOT_EXPORTED flag).
            registerReceiver(phaseChangeReceiver, filter, AppConstants.PERMISSION_INTERNAL, null)
        }

        configureServiceCapabilities()
        startWatchdog()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(AppConstants.TAG_ACCESSIBILITY,
            "onUnbind: Service unbound — this may indicate i-Manager killed us during lockdown. " +
            "Current lockdown state: ${AppStateManager.isLockdownActive}")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(AppConstants.TAG_ACCESSIBILITY,
            "onInterrupt: Accessibility service interrupted by system")
    }

    override fun onDestroy() {
        Log.e(AppConstants.TAG_ACCESSIBILITY,
            "onDestroy: AccessibilityService destroyed! " +
            "Lockdown active at time of death: ${AppStateManager.isLockdownActive}")
        watchdogJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(phaseChangeReceiver) }.onFailure { ex ->
            Log.w(AppConstants.TAG_ACCESSIBILITY,
                "phaseChangeReceiver was already unregistered: ${ex.message}")
        }
        if (AppStateManager.isLockdownActive) {
            fireAccessibilityKilledAlert()
        }
        super.onDestroy()
    }

    // ── Core Event Processing ──────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Emergency unlock: the device is fully open — no blocking, no anti-tamper.
        if (AppStateManager.isEmergencyActive) return

        val packageName = event.packageName?.toString() ?: run {
            Log.v(AppConstants.TAG_ACCESSIBILITY, "Event with null packageName — ignoring")
            return
        }

        // During the WORK phase, do not interfere with normal app usage.
        // Only enforce during LOCKDOWN or when blocking Settings anti-tamper.
        if (!AppStateManager.isLockdownActive) {
            // Settings anti-tampering is always active regardless of phase.
            // A user navigating to our App Info to uninstall triggers this.
            if (packageName == AppConstants.PKG_ANDROID_SETTINGS) {
                handleSettingsDetected(event)
            }
            return
        }

        // ── Active call exception ───────────────────────────────────────
        // During a call the system dialer / in-call UI must be allowed to the
        // foreground. We allow the static telephony set plus the device's actual
        // default dialer, resolved dynamically (covers OEM dialers not in the list).
        if (AppStateManager.isCallActive &&
            (packageName in AppConstants.TELEPHONY_WHITELIST || packageName == defaultDialerPackage())
        ) {
            Log.d(AppConstants.TAG_ACCESSIBILITY,
                "Allowing telephony package during active call: $packageName")
            return
        }

        // ── Our own package ─────────────────────────────────────────────
        if (packageName == this.packageName) return

        // ── System UI (notification shade / quick settings) ─────────────
        if (packageName == AppConstants.PKG_SYSTEM_UI) {
            Log.i(AppConstants.TAG_ACCESSIBILITY,
                "SystemUI gained focus during lockdown — collapsing notification shade")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // ── Settings (uninstall / permission revocation attempt) ─────────
        if (packageName == AppConstants.PKG_ANDROID_SETTINGS) {
            handleSettingsDetected(event)
            return
        }

        // ── Any other package ────────────────────────────────────────────
        Log.i(AppConstants.TAG_ACCESSIBILITY,
            "Blocked package during lockdown: '$packageName' — redirecting HOME")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ── Settings Anti-Tamper ───────────────────────────────────────────────

    private fun handleSettingsDetected(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        Log.w(AppConstants.TAG_ACCESSIBILITY,
            "Settings detected: class='$className' — inspecting for tampering vectors")

        // Any navigation into Settings during lockdown is suspicious.
        // During work phase we only block specific screens.
        val isAtRiskScreen = className.contains("AppInfo", ignoreCase = true)
            || className.contains("DeviceAdmin", ignoreCase = true)
            || className.contains("InstalledApp", ignoreCase = true)
            || className.contains("ManagePermissions", ignoreCase = true)
            || className.contains("AppLaunchSettings", ignoreCase = true)

        if (AppStateManager.isLockdownActive || isAtRiskScreen) {
            Log.w(AppConstants.TAG_ACCESSIBILITY,
                "Intercepting Settings navigation (lockdown=${AppStateManager.isLockdownActive}, " +
                "atRiskScreen=$isAtRiskScreen) — forcing HOME")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ── Service Self-Configuration ─────────────────────────────────────────

    private fun configureServiceCapabilities() {
        // Never assign null back to serviceInfo: setServiceInfo(null) throws. If the
        // framework hasn't populated it yet, fall back to the XML configuration.
        val info = serviceInfo
        if (info == null) {
            Log.e(AppConstants.TAG_ACCESSIBILITY,
                "serviceInfo was null during configuration — relying on XML defaults")
            return
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 100L
        serviceInfo = info
        Log.d(AppConstants.TAG_ACCESSIBILITY, "Service capabilities configured dynamically")
    }

    // ── Watchdog ───────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L) // Check every 30 seconds
                val isEnabled = runCatching {
                    val am = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                    am.isEnabled
                }.getOrElse { ex ->
                    Log.e(AppConstants.TAG_ACCESSIBILITY,
                        "Watchdog failed to query AccessibilityManager: ${ex.message}")
                    true // Assume enabled on failure — avoid false-positive alerts
                }

                if (!isEnabled && AppStateManager.isLockdownActive) {
                    Log.e(AppConstants.TAG_ACCESSIBILITY,
                        "WATCHDOG ALERT: AccessibilityService disabled during active LOCKDOWN — " +
                        "likely killed by Vivo i-Manager. Firing high-priority alert.")
                    withContext(Dispatchers.Main) { fireAccessibilityKilledAlert() }
                } else {
                    Log.d(AppConstants.TAG_ACCESSIBILITY,
                        "Watchdog tick: service alive, lockdown=${AppStateManager.isLockdownActive}")
                }
            }
        }
    }

    private fun fireAccessibilityKilledAlert() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "max20_alert_channel"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Max20 Alerts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Critical accessibility service alerts" }
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Max20: Action Required")
            .setContentText(
                "The enforcement service was disabled. Re-enable Accessibility to restore lockdown."
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        nm.notify(AppConstants.NOTIFICATION_ID_ALERT, notification)
    }
}

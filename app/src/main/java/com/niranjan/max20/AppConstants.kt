package com.niranjan.max20

object AppConstants {

    // ── Log Tags ──────────────────────────────────────────────────────────
    const val TAG_ENFORCER    = "Max20:TimeEnforcer"
    const val TAG_ACCESSIBILITY = "Max20:Accessibility"
    const val TAG_OVERLAY     = "Max20:KioskOverlay"
    const val TAG_BOOT        = "Max20:Boot"
    const val TAG_CALL        = "Max20:Call"
    const val TAG_VIVO        = "Max20:VivoHelper"
    const val TAG_ADMIN       = "Max20:DeviceAdmin"

    // ── Timer Durations ───────────────────────────────────────────────────
    const val TIMER_WORK_MS: Long     = 20 * 60 * 1_000L  // 20 min work window
    const val TIMER_LOCKDOWN_MS: Long = 20 * 60 * 1_000L  // 20 min mandatory break

    // ── Notification ──────────────────────────────────────────────────────
    const val NOTIFICATION_CHANNEL_ID   = "max20_enforcer_channel"
    const val NOTIFICATION_CHANNEL_NAME = "20/20 Rule Enforcer"
    const val NOTIFICATION_ID_ENFORCER  = 1001
    const val NOTIFICATION_ID_ALERT     = 1002
    // Distinct ID for the KioskOverlayService foreground notification. Must NOT
    // collide with NOTIFICATION_ID_ALERT (1002), otherwise the accessibility
    // "service killed" alert would silently replace the overlay's FGS notification.
    const val NOTIFICATION_ID_OVERLAY   = 1003

    // ── Internal Broadcast Actions ────────────────────────────────────────
    const val ACTION_PHASE_TRANSITION = "com.niranjan.max20.ACTION_PHASE_TRANSITION"
    const val ACTION_START_LOCKDOWN   = "com.niranjan.max20.ACTION_START_LOCKDOWN"
    const val ACTION_END_LOCKDOWN     = "com.niranjan.max20.ACTION_END_LOCKDOWN"
    const val ACTION_CALL_STARTED     = "com.niranjan.max20.ACTION_CALL_STARTED"
    const val ACTION_CALL_ENDED       = "com.niranjan.max20.ACTION_CALL_ENDED"

    // ── Intent Extras ─────────────────────────────────────────────────────
    const val EXTRA_PHASE        = "extra_phase"
    const val EXTRA_REMAINING_MS = "extra_remaining_ms"

    // ── Alarm Request Codes ───────────────────────────────────────────────
    const val ALARM_REQUEST_TRANSITION  = 100
    const val ALARM_REQUEST_RESURRECTION = 101

    // ── Telephony Package Whitelist ───────────────────────────────────────
    // These packages are allowed to gain window focus during lockdown.
    // Vivo may use com.vivo.phone; Google Pixel uses com.google.android.dialer.
    val TELEPHONY_WHITELIST: Set<String> = setOf(
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.vivo.phone",
        "com.android.phone",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
    )

    // ── Vivo-Specific Package Names ───────────────────────────────────────
    const val PKG_IQOO_SECURE           = "com.iqoo.secure"
    const val PKG_VIVO_PERM_MANAGER     = "com.vivo.permissionmanager"
    const val PKG_VIVO_SETTINGS         = "com.vivo.settings"
    const val VIVO_DIALER_PACKAGE       = "com.vivo.phone"

    // ── System UI / Settings Packages to Monitor ──────────────────────────
    const val PKG_ANDROID_SETTINGS = "com.android.settings"
    const val PKG_SYSTEM_UI        = "com.android.systemui"

    // ── Vivo i-Manager i-Manager Intent Cascades ──────────────────────────
    // Primary autostart manager (modern iqoo/Funtouch OS)
    const val VIVO_AUTOSTART_PKG_PRIMARY   = "com.iqoo.secure"
    const val VIVO_AUTOSTART_CLASS_PRIMARY = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
    // Fallback autostart (older Funtouch OS builds)
    const val VIVO_AUTOSTART_PKG_LEGACY    = "com.iqoo.secure"
    const val VIVO_AUTOSTART_CLASS_LEGACY  = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
    // Second-tier fallback via vivo permission manager
    const val VIVO_AUTOSTART_PKG_PERM      = "com.vivo.permissionmanager"
    const val VIVO_AUTOSTART_CLASS_PERM    = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    // Power saving whitelist (Background power consumption whitelist)
    const val VIVO_POWER_PKG               = "com.iqoo.secure"
    const val VIVO_POWER_CLASS             = "com.iqoo.secure.ui.powersaving.PowerSavingManagerActivity"
    // Overlay permission (Display Over Other Apps) — modern
    const val VIVO_OVERLAY_PKG_PRIMARY     = "com.vivo.permissionmanager"
    const val VIVO_OVERLAY_CLASS_PRIMARY   = "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
    // Overlay permission — legacy iqoo path
    const val VIVO_OVERLAY_PKG_LEGACY      = "com.iqoo.secure"
    const val VIVO_OVERLAY_CLASS_LEGACY    = "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"
}

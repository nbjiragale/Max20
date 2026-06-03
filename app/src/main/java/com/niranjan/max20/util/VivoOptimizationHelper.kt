package com.niranjan.max20.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.niranjan.max20.AppConstants

/**
 * VivoOptimizationHelper — programmatic routing to i-Manager exemption screens.
 *
 * Research report §3: "Navigating Vivo-Specific Challenges: Funtouch OS and OriginOS"
 *
 * Vivo devices use "i-Manager" (com.iqoo.secure) and the Vivo Permission Manager
 * (com.vivo.permissionmanager) to aggressively kill background processes. Standard
 * AOSP intents like ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS are silently ignored
 * or redirected to non-functional screens on Funtouch OS.
 *
 * This helper implements a cascading ComponentName strategy:
 *   1. Try the primary target (modern Funtouch OS / OriginOS).
 *   2. Catch ActivityNotFoundException and try the legacy iqoo path.
 *   3. Catch again and fall back to the standard AOSP intent.
 *   4. Log every fallback so we know which path succeeded on the test device.
 *
 * Three permission categories must be granted for reliable operation:
 *   A) Autostart — allows TimeEnforcerService to boot automatically after reboot
 *      and restart autonomously if killed by the Low Memory Killer (LMK).
 *   B) High Background Power Consumption whitelist — prevents i-Manager from
 *      throttling or killing the service when the screen is off.
 *   C) Display Over Other Apps (SYSTEM_ALERT_WINDOW) — required for
 *      KioskOverlayService to draw the TYPE_APPLICATION_OVERLAY window.
 */
object VivoOptimizationHelper {

    // ── Device Detection ───────────────────────────────────────────────────

    val isVivoDevice: Boolean
        get() = Build.MANUFACTURER.equals("vivo", ignoreCase = true)
                || Build.MANUFACTURER.equals("iqoo", ignoreCase = true)

    // ── Permission State Checks ────────────────────────────────────────────

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isOverlayPermissionGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Checks if the i-Manager Autostart entry for this package is detectable.
     * There is no public API to read the autostart grant state — this performs
     * a heuristic check by querying if the i-Manager package is installed.
     * The actual grant state must be confirmed by the user.
     */
    fun isAutostartPackagePresent(context: Context): Boolean {
        return isPackageInstalled(context, AppConstants.PKG_IQOO_SECURE)
            || isPackageInstalled(context, AppConstants.PKG_VIVO_PERM_MANAGER)
    }

    // ── Intent Launchers ───────────────────────────────────────────────────

    /**
     * Opens the Vivo Autostart manager so the user can whitelist this app.
     * The app CANNOT grant itself autostart programmatically — only the user can.
     *
     * Cascade order (matches report §3 "Programmatic Routing" section):
     *   1. com.iqoo.secure / AddWhiteListActivity   (modern FunTouch OS)
     *   2. com.iqoo.secure / BgStartUpManager       (older iqoo builds)
     *   3. com.vivo.permissionmanager / BgStartUpManagerActivity
     *   4. Standard ACTION_APPLICATION_DETAILS_SETTINGS  (AOSP fallback)
     */
    fun openAutostartSettings(context: Context) {
        Log.i(AppConstants.TAG_VIVO, "openAutostartSettings: attempting cascade")

        val targets = listOf(
            ComponentName(
                AppConstants.VIVO_AUTOSTART_PKG_PRIMARY,
                AppConstants.VIVO_AUTOSTART_CLASS_PRIMARY
            ),
            ComponentName(
                AppConstants.VIVO_AUTOSTART_PKG_LEGACY,
                AppConstants.VIVO_AUTOSTART_CLASS_LEGACY
            ),
            ComponentName(
                AppConstants.VIVO_AUTOSTART_PKG_PERM,
                AppConstants.VIVO_AUTOSTART_CLASS_PERM
            )
        )

        val launched = launchFirstAvailable(context, targets, "AutostartManager")

        if (!launched) {
            Log.w(AppConstants.TAG_VIVO,
                "All Vivo autostart targets failed — falling back to app details settings")
            openAppDetailsSettings(context)
        }
    }

    /**
     * Opens the i-Manager power saving whitelist so the user can add this app
     * to the "High background power consumption" exception list. This is
     * the critical grant that stops i-Manager from killing the timer service
     * when the screen turns off.
     *
     * Cascade order:
     *   1. com.iqoo.secure / PowerSavingManagerActivity
     *   2. Standard ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
     *   3. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS for our package
     */
    fun openBatteryOptimizationSettings(context: Context) {
        Log.i(AppConstants.TAG_VIVO, "openBatteryOptimizationSettings: attempting cascade")

        val targets = listOf(
            ComponentName(
                AppConstants.VIVO_POWER_PKG,
                AppConstants.VIVO_POWER_CLASS
            )
        )

        val launched = launchFirstAvailable(context, targets, "PowerSavingManager")

        if (!launched) {
            // Try the standard AOSP battery optimization request for our specific package
            val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val aosp = tryLaunchIntent(context, directIntent, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")

            if (!aosp) {
                Log.w(AppConstants.TAG_VIVO,
                    "Battery optimization direct request failed — opening general battery settings")
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                tryLaunchIntent(context, fallback, "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
            }
        }
    }

    /**
     * Opens the Vivo overlay permission screen so the user can enable
     * "Display Over Other Apps" / "Display Pop-up Windows" for Max20.
     * This is the prerequisite for KioskOverlayService to function.
     *
     * Cascade order (matches report §3 "Overcoming Vivo's Custom Overlay" section):
     *   1. com.vivo.permissionmanager / SoftPermissionDetailActivity
     *   2. com.iqoo.secure / SoftPermissionDetailActivity
     *   3. Standard ACTION_MANAGE_OVERLAY_PERMISSION for our package
     */
    fun openOverlayPermissionSettings(context: Context) {
        Log.i(AppConstants.TAG_VIVO, "openOverlayPermissionSettings: attempting cascade")

        val targets = listOf(
            ComponentName(
                AppConstants.VIVO_OVERLAY_PKG_PRIMARY,
                AppConstants.VIVO_OVERLAY_CLASS_PRIMARY
            ),
            ComponentName(
                AppConstants.VIVO_OVERLAY_PKG_LEGACY,
                AppConstants.VIVO_OVERLAY_CLASS_LEGACY
            )
        )

        val launched = launchFirstAvailable(context, targets, "SoftPermissionDetail")

        if (!launched) {
            Log.w(AppConstants.TAG_VIVO,
                "Vivo overlay targets failed — using standard MANAGE_OVERLAY_PERMISSION")
            val standardIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tryLaunchIntent(context, standardIntent, "ACTION_MANAGE_OVERLAY_PERMISSION")
        }
    }

    /**
     * Convenience: opens ALL three permission screens in sequence.
     * Called from the onboarding flow when the device is identified as Vivo.
     * Each screen is shown with a 200ms delay to avoid intent overlap.
     */
    fun openAllVivoPermissionScreens(context: Context) {
        if (!isVivoDevice) {
            Log.i(AppConstants.TAG_VIVO,
                "openAllVivoPermissionScreens: not a Vivo device (manufacturer=${Build.MANUFACTURER}) — skipping")
            return
        }
        Log.i(AppConstants.TAG_VIVO,
            "openAllVivoPermissionScreens: manufacturer=${Build.MANUFACTURER}, " +
            "model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}")

        openAutostartSettings(context)
    }

    // ── Intent Launch Utilities ────────────────────────────────────────────

    private fun launchFirstAvailable(
        context: Context,
        targets: List<ComponentName>,
        label: String
    ): Boolean {
        for (component in targets) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryLaunchIntent(context, intent, "$label:${component.className}")) {
                return true
            }
        }
        return false
    }

    private fun tryLaunchIntent(context: Context, intent: Intent, label: String): Boolean {
        return runCatching {
            context.startActivity(intent)
            Log.i(AppConstants.TAG_VIVO, "Successfully launched: $label")
            true
        }.getOrElse { ex ->
            Log.w(AppConstants.TAG_VIVO, "Failed to launch $label: ${ex.javaClass.simpleName} — ${ex.message}")
            false
        }
    }

    private fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryLaunchIntent(context, intent, "ACTION_APPLICATION_DETAILS_SETTINGS")
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrElse { false }

    // ── Diagnostic Report ──────────────────────────────────────────────────

    /**
     * Returns a human-readable diagnostic string for the onboarding checklist.
     * Each item is ✓ (granted) or ✗ (missing) with the remediation action.
     */
    fun buildDiagnosticReport(context: Context): String {
        val batteryOk  = isBatteryOptimizationDisabled(context)
        val overlayOk  = isOverlayPermissionGranted(context)
        val iManagerOk = isAutostartPackagePresent(context)

        return buildString {
            appendLine("Max20 Permission Diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("Is Vivo: $isVivoDevice")
            appendLine()
            appendLine("${if (batteryOk) "✓" else "✗"} Battery optimization disabled")
            appendLine("${if (overlayOk) "✓" else "✗"} Display Over Other Apps granted")
            appendLine("${if (iManagerOk) "✓" else "✗"} i-Manager / Permission Manager detected")
            if (isVivoDevice) {
                appendLine()
                appendLine("Vivo-specific: Autostart grant must be confirmed manually in i-Manager.")
            }
        }
    }
}

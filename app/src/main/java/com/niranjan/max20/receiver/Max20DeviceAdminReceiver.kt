package com.niranjan.max20.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.niranjan.max20.AppConstants

/**
 * Max20DeviceAdminReceiver — Device Administrator declaration.
 *
 * When the user grants Device Admin status, the Android OS blocks uninstallation
 * until admin rights are explicitly revoked via Settings → Security → Device
 * admin apps → Deactivate. The LockdownAccessibilityService intercepts any
 * navigation to that screen and redirects HOME before the user can deactivate.
 *
 * All callbacks are logged so we can detect Vivo-specific behaviors where
 * i-Manager might attempt to revoke admin permissions silently.
 */
class Max20DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(AppConstants.TAG_ADMIN,
            "onEnabled: Device Administrator rights GRANTED. Uninstallation is now blocked.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(AppConstants.TAG_ADMIN,
            "onDisabled: Device Administrator rights REVOKED. " +
            "The app is now vulnerable to uninstallation. This event should have been " +
            "intercepted by LockdownAccessibilityService — check if it was active.")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(AppConstants.TAG_ADMIN,
            "onDisableRequested: User is attempting to revoke Device Admin rights. " +
            "AccessibilityService should redirect HOME to abort this attempt.")
        return "Deactivating Max20's administrator access will allow uninstallation " +
               "and disable lockdown enforcement. This action is blocked during active lockdown periods."
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        Log.i(AppConstants.TAG_ADMIN, "onPasswordChanged: Device password was changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.w(AppConstants.TAG_ADMIN, "onPasswordFailed: Incorrect password attempt detected")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        Log.i(AppConstants.TAG_ADMIN, "onPasswordSucceeded: Device successfully unlocked")
    }
}

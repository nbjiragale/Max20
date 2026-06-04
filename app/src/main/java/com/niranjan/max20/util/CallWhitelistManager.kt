package com.niranjan.max20.util

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager
import java.util.concurrent.Executor

/**
 * CallWhitelistManager — supplemental call-state detection layer.
 *
 * The primary call-state mechanism is Max20InCallService.onCallAdded/Removed.
 * This class provides a safety-net listener via TelephonyManager for cases
 * where the InCallService is not yet bound or the ROLE_DIALER hasn't been
 * granted. It also exposes utility functions for querying the dynamic dialer
 * package list used by TimeEnforcerService when constructing the DPM whitelist.
 *
 * API levels:
 *   API 31+: TelephonyCallback (non-deprecated)
 *   API < 31: PhoneStateListener (deprecated but functional)
 */
class CallWhitelistManager(private val context: Context) {

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var legacyPhoneStateListener: PhoneStateListener? = null
    private var modernTelephonyCallback: TelephonyCallback? = null

    // ── Listener Registration ──────────────────────────────────────────────

    fun startListening() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            registerModernCallback()
        } else {
            registerLegacyListener()
        }
        Log.i(AppConstants.TAG_CALL, "CallWhitelistManager: TelephonyManager listener registered")
    }

    fun stopListening() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            modernTelephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            modernTelephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
            legacyPhoneStateListener = null
        }
        Log.i(AppConstants.TAG_CALL, "CallWhitelistManager: TelephonyManager listener unregistered")
    }

    // ── API 31+ Callback ───────────────────────────────────────────────────

    private fun registerModernCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        val executor: Executor = context.mainExecutor
        telephonyManager.registerTelephonyCallback(executor, callback)
        modernTelephonyCallback = callback
    }

    // ── API < 31 Listener ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun registerLegacyListener() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state)
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyPhoneStateListener = listener
    }

    // ── State Handling ─────────────────────────────────────────────────────

    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!AppStateManager.isCallActive) {
                    Log.i(AppConstants.TAG_CALL,
                        "TelephonyManager: call state=${stateLabel(state)} — broadcasting CALL_STARTED")
                    AppStateManager.onCallStateChanged(active = true)
                    context.sendBroadcast(
                        Intent(AppConstants.ACTION_CALL_STARTED).setPackage(context.packageName),
                        AppConstants.PERMISSION_INTERNAL
                    )
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (AppStateManager.isCallActive) {
                    Log.i(AppConstants.TAG_CALL,
                        "TelephonyManager: IDLE — broadcasting CALL_ENDED")
                    AppStateManager.onCallStateChanged(active = false)
                    context.sendBroadcast(
                        Intent(AppConstants.ACTION_CALL_ENDED).setPackage(context.packageName),
                        AppConstants.PERMISSION_INTERNAL
                    )
                }
            }
        }
    }

    // ── Dynamic Dialer Package Resolution ─────────────────────────────────

    /**
     * Builds the complete telephony package whitelist for DPM.setLockTaskPackages().
     * Queries the active default dialer at runtime so the list is accurate even
     * if the user changed their dialer before lockdown began.
     *
     * Includes Vivo-specific dialer packages as the report §2, Strategy A details.
     */
    fun buildTelephonyWhitelist(appPackageName: String): Array<String> {
        val packages = mutableSetOf<String>()

        packages.add(appPackageName) // Our own app is always in the whitelist

        // Always include core telecom infrastructure
        packages.addAll(AppConstants.TELEPHONY_WHITELIST)

        // Query the current default dialer dynamically
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        runCatching {
            val defaultDialer = telecomManager.defaultDialerPackage
            if (defaultDialer != null) {
                packages.add(defaultDialer)
                Log.i(AppConstants.TAG_CALL,
                    "Dynamic default dialer resolved: $defaultDialer — added to whitelist")
            }
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_CALL,
                "Failed to query defaultDialerPackage: ${ex.message} — using static whitelist only")
        }

        val result = packages.toTypedArray()
        Log.i(AppConstants.TAG_CALL, "Telephony whitelist built: ${result.joinToString()}")
        return result
    }

    private fun stateLabel(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE    -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else                                -> "UNKNOWN($state)"
    }
}

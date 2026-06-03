package com.niranjan.max20.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager

/**
 * Max20InCallService — Strategy B telephony integration.
 *
 * Research report §2: "Strategy B: Native InCallService Implementation (The Dialer Role)"
 *
 * By requesting ROLE_DIALER via RoleManager in LockdownActivity, Android routes
 * all call state to THIS service instead of the stock phone application. This
 * means calls are handled entirely within our package, completely avoiding the
 * Lock Task Mode violation:
 *   "Attempted Lock Task Mode violation ... com.android.server.telecom/.components.UserCallActivity"
 *
 * The service manages the call lifecycle and sends internal broadcasts so:
 *   - KioskOverlayService hides the visual shield during the call
 *   - AppStateManager.isCallActive gates the AccessibilityService enforcement
 *   - LockdownActivity restores its lockdown UI when the call ends
 *
 * InCallService contract:
 *   - onCallAdded()   → called when a new call is created (incoming or outgoing)
 *   - onCallRemoved() → called when the call disconnects
 *   - The service must NOT call stopSelf() — the Telecom framework manages its lifecycle.
 */
class Max20InCallService : InCallService() {

    private val activeCalls = mutableSetOf<Call>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.i(AppConstants.TAG_CALL,
                "Call.Callback.onStateChanged: handle=${call.details?.handle}, state=${stateLabel(state)}")
            when (state) {
                Call.STATE_ACTIVE -> {
                    Log.i(AppConstants.TAG_CALL, "Call is ACTIVE — suspending lockdown overlay")
                    notifyCallStarted()
                }
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    Log.i(AppConstants.TAG_CALL,
                        "Call DISCONNECTED — re-checking remaining active calls")
                    evaluateCallCountAfterDisconnect()
                }
                Call.STATE_RINGING -> {
                    Log.i(AppConstants.TAG_CALL, "Incoming call RINGING — suspending lockdown overlay")
                    notifyCallStarted()
                }
            }
        }

        override fun onCallDestroyed(call: Call) {
            Log.i(AppConstants.TAG_CALL, "onCallDestroyed — unregistering callback")
            call.unregisterCallback(this)
        }
    }

    // ── InCallService API ──────────────────────────────────────────────────

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(AppConstants.TAG_CALL,
            "onCallAdded: state=${stateLabel(call.state)}, handle=${call.details?.handle}")
        activeCalls.add(call)
        call.registerCallback(callCallback)

        // Any call state addition (ringing, dialing, holding) suspends lockdown enforcement
        notifyCallStarted()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(AppConstants.TAG_CALL,
            "onCallRemoved: handle=${call.details?.handle}, activeCalls remaining=${activeCalls.size - 1}")
        activeCalls.remove(call)
        call.unregisterCallback(callCallback)

        if (activeCalls.isEmpty()) {
            Log.i(AppConstants.TAG_CALL, "No active calls remaining — restoring lockdown state")
            notifyCallEnded()
        } else {
            Log.i(AppConstants.TAG_CALL,
                "Still ${activeCalls.size} call(s) active — lockdown remains suspended")
        }
    }

    override fun onDestroy() {
        Log.w(AppConstants.TAG_CALL, "Max20InCallService.onDestroy — cleaning up ${activeCalls.size} dangling calls")
        activeCalls.forEach { call ->
            runCatching { call.unregisterCallback(callCallback) }.onFailure { ex ->
                Log.w(AppConstants.TAG_CALL, "Failed to unregister callback: ${ex.message}")
            }
        }
        activeCalls.clear()

        if (AppStateManager.isCallActive) {
            Log.w(AppConstants.TAG_CALL,
                "InCallService destroyed while isCallActive=true — broadcasting call-ended to restore lockdown")
            notifyCallEnded()
        }
        super.onDestroy()
    }

    // ── Call Control Utilities ─────────────────────────────────────────────

    /**
     * Accepts the first ringing call. Called from the overlay's Accept button.
     */
    fun acceptFirstRingingCall() {
        val ringingCall = activeCalls.firstOrNull { it.state == Call.STATE_RINGING }
        if (ringingCall != null) {
            Log.i(AppConstants.TAG_CALL, "Accepting ringing call")
            ringingCall.answer(VideoProfile.STATE_AUDIO_ONLY)
        } else {
            Log.w(AppConstants.TAG_CALL, "acceptFirstRingingCall: no ringing call found")
        }
    }

    /**
     * Rejects / hangs up all active calls. Called from the overlay's End button.
     */
    fun endAllCalls() {
        Log.i(AppConstants.TAG_CALL, "endAllCalls: terminating ${activeCalls.size} call(s)")
        activeCalls.toList().forEach { call ->
            runCatching {
                when (call.state) {
                    Call.STATE_RINGING -> call.reject(false, null)
                    else               -> call.disconnect()
                }
            }.onFailure { ex ->
                Log.e(AppConstants.TAG_CALL, "Failed to end call: ${ex.message}")
            }
        }
    }

    // ── Internal Broadcast Helpers ─────────────────────────────────────────

    private fun notifyCallStarted() {
        AppStateManager.onCallStateChanged(active = true)
        sendBroadcast(Intent(AppConstants.ACTION_CALL_STARTED).setPackage(packageName))
        Log.d(AppConstants.TAG_CALL, "Broadcast ACTION_CALL_STARTED sent")
    }

    private fun notifyCallEnded() {
        AppStateManager.onCallStateChanged(active = false)
        sendBroadcast(Intent(AppConstants.ACTION_CALL_ENDED).setPackage(packageName))
        Log.d(AppConstants.TAG_CALL, "Broadcast ACTION_CALL_ENDED sent")
    }

    private fun evaluateCallCountAfterDisconnect() {
        val remainingActive = activeCalls.count { call ->
            call.state !in listOf(Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING)
        }
        Log.i(AppConstants.TAG_CALL, "evaluateCallCountAfterDisconnect: $remainingActive active call(s)")
        if (remainingActive == 0) {
            notifyCallEnded()
        }
    }

    private fun stateLabel(state: Int): String = when (state) {
        Call.STATE_RINGING       -> "RINGING"
        Call.STATE_DIALING       -> "DIALING"
        Call.STATE_ACTIVE        -> "ACTIVE"
        Call.STATE_HOLDING       -> "HOLDING"
        Call.STATE_DISCONNECTED  -> "DISCONNECTED"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_NEW           -> "NEW"
        Call.STATE_CONNECTING    -> "CONNECTING"
        else                     -> "UNKNOWN($state)"
    }
}

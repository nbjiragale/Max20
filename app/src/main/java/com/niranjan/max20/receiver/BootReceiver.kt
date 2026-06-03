package com.niranjan.max20.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.niranjan.max20.AppConstants
import com.niranjan.max20.AppStateManager
import com.niranjan.max20.data.TimerPhase
import com.niranjan.max20.data.TimerStateStore
import com.niranjan.max20.service.KioskOverlayService
import com.niranjan.max20.service.TimeEnforcerService
import com.niranjan.max20.ui.LockdownActivity

/**
 * BootReceiver — Direct Boot-aware resurrection point.
 *
 * ACTION_LOCKED_BOOT_COMPLETED fires the moment Device Encrypted (DE)
 * storage is accessible — BEFORE the lock screen appears. TimerStateStore
 * reads from DE storage (createDeviceProtectedStorageContext), so we have
 * the full timer state before the user can even enter their PIN.
 *
 * getStateSync() uses runBlocking internally. This is intentional:
 * DataStore reads local storage in < 5ms, well within the BroadcastReceiver
 * ANR window (~10 seconds). goAsync() is unnecessary for this duration.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: run {
            Log.w(AppConstants.TAG_BOOT, "BootReceiver.onReceive: null action — ignoring")
            return
        }

        Log.i(AppConstants.TAG_BOOT, "BootReceiver.onReceive: action=$action")

        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED                  -> handleLockedBoot(context)
            Intent.ACTION_BOOT_COMPLETED                         -> handleFullBoot(context)
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"            -> {
                Log.i(AppConstants.TAG_BOOT, "Vivo/HTC QuickBoot detected — treating as BOOT_COMPLETED")
                handleFullBoot(context)
            }
        }
    }

    // ── LOCKED_BOOT_COMPLETED — fires before PIN entry ─────────────────────

    private fun handleLockedBoot(context: Context) {
        Log.i(AppConstants.TAG_BOOT,
            "handleLockedBoot: DE storage accessible — reading timer state before lock screen")

        val store = TimerStateStore.getInstance(context)
        val state = store.getStateSync()

        if (state == null) {
            Log.i(AppConstants.TAG_BOOT, "No persisted state in DE storage — skipping pre-unlock enforcement")
            return
        }

        Log.i(AppConstants.TAG_BOOT,
            "Persisted state: phase=${state.phase}, isActive=${state.isActive}, " +
            "remaining=${state.remainingMs / 1000}s, expired=${state.isExpired}")

        AppStateManager.onPhaseChanged(state.phase)

        if (state.isActive && state.phase == TimerPhase.LOCKDOWN) {
            if (state.isExpired) {
                Log.w(AppConstants.TAG_BOOT,
                    "Lockdown phase expired during reboot downtime — marking inactive for full boot restart")
                store.saveStateSync(state.copy(isActive = false))
            } else {
                Log.i(AppConstants.TAG_BOOT,
                    "ACTIVE LOCKDOWN at boot — enforcing IMMEDIATELY before lock screen. " +
                    "Remaining: ${state.remainingMs / 1000}s")
                enforcePreUnlockLockdown(context)
            }
        } else if (state.isActive && state.phase == TimerPhase.WORK) {
            Log.i(AppConstants.TAG_BOOT, "WORK phase active at boot — starting TimeEnforcerService")
            startEnforcerService(context)
        }
    }

    // ── BOOT_COMPLETED — fires after credential unlock ──────────────────────

    private fun handleFullBoot(context: Context) {
        Log.i(AppConstants.TAG_BOOT, "handleFullBoot: CE storage now accessible — full initialization")

        val store = TimerStateStore.getInstance(context)
        val state = store.getStateSync()

        if (state == null || !state.isActive) {
            Log.i(AppConstants.TAG_BOOT, "No active state on full boot — starting fresh via TimeEnforcerService")
            startEnforcerService(context)
            return
        }

        Log.i(AppConstants.TAG_BOOT,
            "Active state on full boot: phase=${state.phase}, remaining=${state.remainingMs / 1000}s")
        AppStateManager.onPhaseChanged(state.phase)
        startEnforcerService(context)

        if (state.phase == TimerPhase.LOCKDOWN && !state.isExpired) {
            Log.i(AppConstants.TAG_BOOT, "Lockdown still active on full boot — starting KioskOverlayService")
            startOverlayService(context)
            bringLockdownActivityToFront(context)
        }
    }

    // ── Service Launchers ──────────────────────────────────────────────────

    private fun enforcePreUnlockLockdown(context: Context) {
        startEnforcerService(context)
        startOverlayService(context)
        bringLockdownActivityToFront(context)
        Log.i(AppConstants.TAG_BOOT, "Pre-unlock lockdown enforcement complete")
    }

    private fun startEnforcerService(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, TimeEnforcerService::class.java))
            Log.i(AppConstants.TAG_BOOT, "TimeEnforcerService started from boot")
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_BOOT,
                "Failed to start TimeEnforcerService: ${ex.message}")
        }
    }

    private fun startOverlayService(context: Context) {
        runCatching {
            context.startForegroundService(
                Intent(context, KioskOverlayService::class.java)
                    .apply { action = AppConstants.ACTION_START_LOCKDOWN }
            )
            Log.i(AppConstants.TAG_BOOT, "KioskOverlayService started from boot")
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_BOOT, "Failed to start KioskOverlayService: ${ex.message}")
        }
    }

    private fun bringLockdownActivityToFront(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, LockdownActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
            Log.i(AppConstants.TAG_BOOT, "LockdownActivity brought to front on boot")
        }.onFailure { ex ->
            Log.e(AppConstants.TAG_BOOT,
                "Failed to start LockdownActivity: ${ex.message} — KioskOverlayService covers the screen")
        }
    }
}

package com.niranjan.max20.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import com.niranjan.max20.AppConstants

/**
 * TimerStateStore — DataStore-backed replacement for Room.
 *
 * Stores four scalar values representing the current timer phase. DataStore
 * requires zero annotation processing (no KSP, no KAPT), making it fully
 * compatible with AGP 9.x's built-in Kotlin mode.
 *
 * Device Encrypted storage:
 *   The DataStore file is created via createDeviceProtectedStorageContext(),
 *   placing it in DE storage exactly as the Room database was. This preserves
 *   Direct Boot compatibility: the BootReceiver can read the lockdown state
 *   from DE storage before the user enters their PIN.
 *
 * Synchronous access (getStateSync / saveStateSync):
 *   Two callers require synchronous reads/writes:
 *     - BootReceiver.onReceive() — main thread, no coroutine context
 *     - TimeEnforcerService.onDestroy() — scope already cancelled
 *   runBlocking is intentional and safe here: DataStore reads local storage
 *   (~1ms), well within the ANR limit for both contexts.
 */
class TimerStateStore private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "Max20:TimerStore"

        private val KEY_PHASE         = stringPreferencesKey("phase")
        private val KEY_START_EPOCH   = longPreferencesKey("start_epoch")
        private val KEY_DURATION      = longPreferencesKey("duration")
        private val KEY_IS_ACTIVE     = booleanPreferencesKey("is_active")

        // Emergency-unlock daily accounting
        private val KEY_EMERGENCY_DAY   = longPreferencesKey("emergency_day")    // local epoch-day
        private val KEY_EMERGENCY_COUNT = intPreferencesKey("emergency_count")

        @Volatile private var instance: TimerStateStore? = null

        fun getInstance(context: Context): TimerStateStore =
            instance ?: synchronized(this) {
                instance ?: TimerStateStore(context.applicationContext).also { instance = it }
            }
    }

    // DataStore file lives in DE storage — readable before user credential unlock
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = {
            context.createDeviceProtectedStorageContext()
                .preferencesDataStoreFile("timer_state")
        }
    )

    // ── Reactive ───────────────────────────────────────────────────────────

    fun observeState(): Flow<TimerState?> = dataStore.data.map { prefs ->
        val phase = prefs[KEY_PHASE]?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
            ?: return@map null
        TimerState(
            phase            = phase,
            phaseStartEpochMs = prefs[KEY_START_EPOCH] ?: return@map null,
            phaseDurationMs   = prefs[KEY_DURATION]     ?: return@map null,
            isActive          = prefs[KEY_IS_ACTIVE]    ?: false
        )
    }

    // ── Suspend ────────────────────────────────────────────────────────────

    suspend fun getState(): TimerState? = dataStore.data.first().toTimerState()

    suspend fun saveState(state: TimerState) {
        dataStore.edit { prefs ->
            prefs[KEY_PHASE]       = state.phase.name
            prefs[KEY_START_EPOCH] = state.phaseStartEpochMs
            prefs[KEY_DURATION]    = state.phaseDurationMs
            prefs[KEY_IS_ACTIVE]   = state.isActive
        }
        Log.d(TAG, "saveState: phase=${state.phase}, startEpoch=${state.phaseStartEpochMs}, " +
                   "duration=${state.phaseDurationMs}ms, active=${state.isActive}")
    }

    suspend fun clearState() {
        dataStore.edit { it.clear() }
        Log.d(TAG, "clearState: all keys removed")
    }

    // ── Emergency-Unlock Daily Cap ───────────────────────────────────────────

    /** Emergency unlocks still available today (resets at local midnight). */
    suspend fun emergencyUsesRemaining(limit: Int): Int {
        val prefs = dataStore.data.first()
        val count = if (prefs[KEY_EMERGENCY_DAY] == currentLocalDay()) {
            prefs[KEY_EMERGENCY_COUNT] ?: 0
        } else 0
        return (limit - count).coerceAtLeast(0)
    }

    /**
     * Atomically consumes one emergency-unlock credit for today if any remain.
     * @return true if a credit was consumed (unlock allowed), false if the daily
     *         limit is already reached.
     */
    suspend fun tryConsumeEmergency(limit: Int): Boolean {
        var allowed = false
        dataStore.edit { prefs ->
            val today = currentLocalDay()
            val count = if (prefs[KEY_EMERGENCY_DAY] == today) (prefs[KEY_EMERGENCY_COUNT] ?: 0) else 0
            prefs[KEY_EMERGENCY_DAY] = today
            if (count < limit) {
                prefs[KEY_EMERGENCY_COUNT] = count + 1
                allowed = true
            } else {
                prefs[KEY_EMERGENCY_COUNT] = count
            }
        }
        Log.i(TAG, "tryConsumeEmergency: allowed=$allowed (limit=$limit)")
        return allowed
    }

    /** Local-time epoch day (days since epoch, adjusted for the device time zone). */
    private fun currentLocalDay(): Long {
        val now = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        return (now + offset) / 86_400_000L
    }

    // ── Synchronous (runBlocking — only for onDestroy / BootReceiver) ─────

    fun getStateSync(): TimerState? = runCatching {
        runBlocking { dataStore.data.first() }.toTimerState()
    }.getOrElse { ex ->
        Log.e(TAG, "getStateSync failed: ${ex.message}")
        null
    }

    fun saveStateSync(state: TimerState) = runCatching {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[KEY_PHASE]       = state.phase.name
                prefs[KEY_START_EPOCH] = state.phaseStartEpochMs
                prefs[KEY_DURATION]    = state.phaseDurationMs
                prefs[KEY_IS_ACTIVE]   = state.isActive
            }
        }
        Log.d(TAG, "saveStateSync: phase=${state.phase}")
    }.getOrElse { ex ->
        Log.e(TAG, "saveStateSync failed: ${ex.message}")
    }

    // ── Internal Helper ────────────────────────────────────────────────────

    private fun Preferences.toTimerState(): TimerState? {
        val phase = this[KEY_PHASE]?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
            ?: return null
        return TimerState(
            phase             = phase,
            phaseStartEpochMs = this[KEY_START_EPOCH] ?: return null,
            phaseDurationMs   = this[KEY_DURATION]    ?: return null,
            isActive          = this[KEY_IS_ACTIVE]   ?: false
        )
    }
}

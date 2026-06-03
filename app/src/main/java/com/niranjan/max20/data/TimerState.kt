package com.niranjan.max20.data

/**
 * Plain data class representing the timer phase state.
 * No Room annotations — persisted via DataStore Preferences in TimerStateStore.
 */
data class TimerState(
    val phase: TimerPhase,
    val phaseStartEpochMs: Long,
    val phaseDurationMs: Long,
    val isActive: Boolean
) {
    val elapsedMs: Long
        get() = System.currentTimeMillis() - phaseStartEpochMs

    val remainingMs: Long
        get() = maxOf(0L, phaseDurationMs - elapsedMs)

    val isExpired: Boolean
        get() = remainingMs <= 0L
}

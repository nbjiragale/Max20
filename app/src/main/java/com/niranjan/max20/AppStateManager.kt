package com.niranjan.max20

import com.niranjan.max20.data.TimerPhase

/**
 * In-memory singleton for hot-path state reads by the AccessibilityService.
 *
 * The AccessibilityService's onAccessibilityEvent() fires dozens of times per
 * second during active window transitions. Reading Room (even with
 * allowMainThreadQueries) on every event would introduce measurable latency
 * and drain CPU. This volatile singleton lets the AccessibilityService make
 * an O(1) memory read per event while the TimeEnforcerService drives all
 * state mutations.
 *
 * Thread-safety contract:
 *   - Writes: performed only by TimeEnforcerService (single writer)
 *   - Reads: performed by AccessibilityService and KioskOverlayService
 *   - @Volatile provides the happens-before relationship required for
 *     safe cross-thread visibility without locking.
 */
object AppStateManager {

    @Volatile var currentPhase: TimerPhase = TimerPhase.WORK
        private set

    @Volatile var isCallActive: Boolean = false
        private set

    @Volatile var isServiceAlive: Boolean = false
        private set

    fun onPhaseChanged(phase: TimerPhase) {
        currentPhase = phase
    }

    fun onCallStateChanged(active: Boolean) {
        isCallActive = active
    }

    fun onServiceLifecycle(alive: Boolean) {
        isServiceAlive = alive
    }

    val isLockdownActive: Boolean
        get() = currentPhase == TimerPhase.LOCKDOWN

    /** True during a temporary emergency unlock — all enforcement is suspended. */
    val isEmergencyActive: Boolean
        get() = currentPhase == TimerPhase.EMERGENCY
}

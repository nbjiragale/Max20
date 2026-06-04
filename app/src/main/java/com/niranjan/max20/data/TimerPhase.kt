package com.niranjan.max20.data

enum class TimerPhase {
    /** 20-minute active usage window. All apps accessible. */
    WORK,
    /** 20-minute mandatory rest. All UI except native calls is blocked. */
    LOCKDOWN,
    /**
     * Temporary emergency unlock — the device is fully open (no overlay, no
     * enforcement, no anti-tamper). Time-boxed; when it ends the device relocks
     * into a fresh LOCKDOWN so the break is still served.
     */
    EMERGENCY
}

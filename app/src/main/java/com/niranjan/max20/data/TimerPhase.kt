package com.niranjan.max20.data

enum class TimerPhase {
    /** 20-minute active usage window. All apps accessible. */
    WORK,
    /** 20-minute mandatory rest. All UI except native calls is blocked. */
    LOCKDOWN
}

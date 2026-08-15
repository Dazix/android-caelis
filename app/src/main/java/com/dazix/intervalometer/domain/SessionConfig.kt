package com.dazix.intervalometer.domain

data class SessionConfig(
    val mode: TriggerMode,
    val intervalMs: Long,
    val startDelayMs: Long = 0L,
    val ramp: RampSpec? = null,
    val stop: StopConditions = StopConditions(),
    val target: TapTarget? = null,
    val verifyCaptures: Boolean = false,
    val minIntervalFloorMs: Long = 0L,
)

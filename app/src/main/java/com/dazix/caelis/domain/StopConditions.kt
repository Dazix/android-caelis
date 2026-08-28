package com.dazix.caelis.domain

data class StopConditions(
    val maxShots: Int? = null,
    val maxDurationMs: Long? = null,
    val minBatteryPercent: Int? = null,
    val minFreeBytes: Long? = null,
)

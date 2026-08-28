package com.dazix.caelis.domain

enum class RampDomain { SHOT_INDEX, ELAPSED_TIME }

enum class RampCurve { LINEAR, EXPONENTIAL }

data class RampSpec(
    val curve: RampCurve,
    val endIntervalMs: Long,
    val domain: RampDomain,
    val overShots: Int? = null,
    val overDurationMs: Long? = null,
)

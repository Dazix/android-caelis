package com.dazix.caelis.domain

enum class StopReason { COMPLETED, USER, MAX_DURATION, LOW_BATTERY, LOW_STORAGE, SERVICE_LOST, ERROR }

sealed interface SessionState {
    data object Idle : SessionState

    data class WaitingToStart(val startsAtElapsed: Long) : SessionState

    data object Running : SessionState

    data object Paused : SessionState

    data class Stopped(val reason: StopReason, val message: String? = null) : SessionState
}

data class SessionSnapshot(
    val state: SessionState = SessionState.Idle,
    val shotsTaken: Int = 0,
    val shotsPlanned: Int? = null,
    val nextShotAtElapsed: Long? = null,
    val lastIntervalMs: Long = 0L,
    val warning: String? = null,
    /**
     * The gyroscope says the phone is held in the hand (see
     * [com.dazix.caelis.domain.stability.HandheldDetector]). A field of its own and not a [warning]:
     * that slot is overwritten by per-shot warnings and cleared by a resume, while this one lasts
     * exactly as long as the hand does.
     */
    val handheld: Boolean = false,
)

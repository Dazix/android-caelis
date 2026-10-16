package com.dazix.caelis.domain.horizon

import kotlin.math.sqrt

/**
 * When Sun AR aligns itself to the skyline without being asked.
 *
 * Found on the device: a calibration does not survive a restart. Once aligned, the heading is held
 * by the gyro against the compass reading *of that session*; the next session starts from a fresh
 * compass reading, which is somewhere else, so the stored correction no longer fits and the ridge
 * came back 11° out. Re-aligning after every start is unavoidable, so it happens by itself: as soon
 * as there is a profile, a camera and a phone held still, and again after a refusal until a fit is
 * accepted. A manual alignment outranks all of it for the rest of the session — the user looked and
 * decided, and an automatic fit must not undo that. An explicit reset starts it again.
 *
 * Pure and clock-free (the caller passes `nowMs`), so every transition is a JVM test.
 */
class AutoAlignPolicy(
    private val firstRetryMs: Long = FIRST_RETRY_MS,
    private val maxRetryMs: Long = MAX_RETRY_MS,
) {

    enum class Decision { WAIT, ATTEMPT }

    /** Aligned this session, by a fit or by hand: nothing more to do until a reset. */
    var isSettled: Boolean = false
        private set

    private var inFlight = false
    private var failures = 0
    private var lastFinishedMs = Long.MIN_VALUE

    /**
     * @param ready a profile is drawn, a camera frame can be grabbed and the sensors own the pose.
     * @param still the phone has been held still long enough for a sharp frame and a settled pose.
     */
    fun decide(nowMs: Long, ready: Boolean, still: Boolean): Decision {
        if (isSettled || inFlight || !ready || !still) return Decision.WAIT
        if (failures > 0 && nowMs - lastFinishedMs < retryDelayMs()) return Decision.WAIT
        return Decision.ATTEMPT
    }

    fun onAttemptStarted() {
        inFlight = true
    }

    /** [accepted]: a fit was applied. Anything else (refused, no frame, timed out) is retried. */
    fun onAttemptFinished(nowMs: Long, accepted: Boolean) {
        inFlight = false
        lastFinishedMs = nowMs
        if (accepted) {
            isSettled = true
        } else {
            failures++
        }
    }

    /** The user dragged or nudged the overlay onto the ridge, or pressed the button and it worked. */
    fun onManualAlignment() {
        isSettled = true
    }

    /** An explicit reset, or a new session: back to aligning by itself, with no backoff. */
    fun reset() {
        isSettled = false
        inFlight = false
        failures = 0
        lastFinishedMs = Long.MIN_VALUE
    }

    /** 3 s after the first refusal, doubling to at most [maxRetryMs]: a phone pointed at a wall should not be asked every frame. */
    private fun retryDelayMs(): Long {
        var delay = firstRetryMs
        repeat((failures - 1).coerceAtMost(8)) { delay = (delay * 2).coerceAtMost(maxRetryMs) }
        return delay
    }

    companion object {
        const val FIRST_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 10_000L
    }
}

/**
 * Whether the camera has pointed the same way for a while, from its optical axis sampled over time.
 *
 * A frame grabbed mid-pan is smeared and its pose is interpolated across a fast turn; both make a
 * fit worse and a refusal likelier. Allocation-free after construction.
 */
class StillnessDetector(
    private val windowMs: Long = WINDOW_MS,
    maxDriftDegrees: Double = MAX_DRIFT_DEGREES,
) {
    private val minCos = kotlin.math.cos(Math.toRadians(maxDriftDegrees))
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchorZ = 0.0
    private var anchorMs = Long.MIN_VALUE
    private var hasAnchor = false

    /** Feeds one sample of the camera axis (any length but zero); returns whether the phone is still. */
    fun add(nowMs: Long, x: Double, y: Double, z: Double): Boolean {
        val length = sqrt(x * x + y * y + z * z)
        if (length <= 0.0 || length.isNaN()) {
            hasAnchor = false
            return false
        }
        val nx = x / length
        val ny = y / length
        val nz = z / length
        // The axis has moved away from where it settled: that is the new anchor and the clock restarts.
        if (!hasAnchor || nx * anchorX + ny * anchorY + nz * anchorZ < minCos) {
            anchorX = nx
            anchorY = ny
            anchorZ = nz
            anchorMs = nowMs
            hasAnchor = true
            return false
        }
        return nowMs - anchorMs >= windowMs
    }

    fun reset() {
        hasAnchor = false
    }

    companion object {
        const val WINDOW_MS = 700L
        const val MAX_DRIFT_DEGREES = 1.0
    }
}

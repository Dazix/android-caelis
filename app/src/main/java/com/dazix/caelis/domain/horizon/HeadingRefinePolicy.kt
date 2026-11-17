package com.dazix.caelis.domain.horizon

import kotlin.math.abs

/**
 * When Sun AR checks an aligned heading against the skyline again, and how much of the answer it takes.
 *
 * Found on the device (Pixel 10 Pro, aligned to a ridge, then a fast look around and back): the
 * game rotation vector's yaw came back 0.6-1.8° off after swings of 300-380 °/s (8-25° in earlier
 * runs), with the gyroscope nowhere near its range. The heading lock follows the game vector by
 * design, so the overlay stood beside the ridge until the user dragged it back. Held still after an
 * alignment, the lock's correction also crept 0.05 °/s, and nothing on the phone says whether that
 * is the game vector or the compass.
 *
 * The skyline is the only truth there is, so once an alignment is in, a still phone after a swing
 * (and every [periodMs] otherwise) is fitted again, and the overlay is moved by what the fit says
 * has changed since the alignment. Not by what the fit says absolutely: a hand alignment is the
 * user's decision and outranks a fit ([AutoAlignPolicy]), and where a hand put the ridge the fit may
 * have put it a few tenths elsewhere (trees on the ridge, a different bit of it). So right after a
 * hand alignment, before anything has moved, the fit is measured once as the baseline: the fit's
 * answer minus the calibration. A swing before that leaves the session without a baseline (it
 * would measure the jump it exists to remove) until the next alignment. An accepted fit is its own
 * baseline, zero.
 *
 * Only a heading the gyro holds is refined: without the lock the compass moves the overlay anyway.
 * A step beyond [maxStepDegrees] is not a swing's jump but a different ridge or a wrong fit, and is
 * refused; one under [minStepDegrees] is the fit's own noise and is left alone, so a steady view does
 * not tick. A fit is taken [settleMs] after the last swing, when the tilt has been brought back
 * ([com.dazix.caelis.domain.arframe.TiltRecovery]) and the frame is sharp; a result for a frame
 * from before a later swing is dropped.
 *
 * Pure and clock-free (the caller passes `nowMs` and a swing counter), so every transition is a JVM
 * test. Degrees throughout.
 */
class HeadingRefinePolicy(
    private val settleMs: Long = SETTLE_MS,
    private val retryMs: Long = RETRY_MS,
    private val periodMs: Long = PERIOD_MS,
    private val maxStepDegrees: Double = MAX_STEP_DEGREES,
    private val minStepDegrees: Double = MIN_STEP_DEGREES,
) {

    enum class Decision { WAIT, MEASURE_BASELINE, REFINE }

    /** The fit's answer minus the calibration at the alignment; only meaningful with [hasBaseline]. */
    var baselineDegrees: Double = 0.0
        private set

    var hasBaseline: Boolean = false
        private set

    private var awaitingBaseline = false
    private var swingSinceCheck = false
    private var knownSwingCount = 0
    private var countAtStart = 0
    private var lastSwingMs = Long.MIN_VALUE
    private var lastCheckMs = Long.MIN_VALUE
    private var lastFailureMs = Long.MIN_VALUE
    private var inFlight = false

    /**
     * @param ready a profile is drawn, a frame can be grabbed, the sensors own the pose and nothing
     *   else is fitting.
     * @param still the camera has pointed the same way for a while.
     * @param swingCount the tracker's count of fast swings so far; any change is a swing.
     * @param headingHeld the gyro holds the heading ([com.dazix.caelis.domain.arframe.HeadingLock]).
     */
    fun decide(nowMs: Long, ready: Boolean, still: Boolean, swingCount: Int, headingHeld: Boolean): Decision {
        if (swingCount != knownSwingCount) {
            knownSwingCount = swingCount
            lastSwingMs = nowMs
            // Measured now, the baseline would contain the jump it is there to take out.
            if (awaitingBaseline) awaitingBaseline = false else swingSinceCheck = true
        }
        if (inFlight || !ready || !still || !headingHeld) return Decision.WAIT
        if (lastSwingMs != Long.MIN_VALUE && nowMs - lastSwingMs < settleMs) return Decision.WAIT
        if (lastFailureMs != Long.MIN_VALUE && nowMs - lastFailureMs < retryMs) return Decision.WAIT
        if (awaitingBaseline) return Decision.MEASURE_BASELINE
        if (!hasBaseline) return Decision.WAIT
        if (swingSinceCheck || lastCheckMs == Long.MIN_VALUE || nowMs - lastCheckMs >= periodMs) return Decision.REFINE
        return Decision.WAIT
    }

    fun onAttemptStarted() {
        inFlight = true
        countAtStart = knownSwingCount
    }

    /** The attempt ended without a fit to judge: no frame, a timeout, or it was abandoned. */
    fun onAttemptAbandoned(nowMs: Long) {
        if (!inFlight) return
        inFlight = false
        lastFailureMs = nowMs
    }

    /**
     * A fit accepted as the whole calibration (automatic, or the button): the overlay is where the
     * fit put it, so the baseline is zero and nothing needs re-measuring.
     */
    fun onFitAccepted(nowMs: Long, swingCount: Int) {
        reset()
        knownSwingCount = swingCount
        hasBaseline = true
        baselineDegrees = 0.0
        lastCheckMs = nowMs
    }

    /**
     * The user moved the overlay in azimuth by hand. The old baseline was measured against a heading
     * the user has just said was wrong; a new one is measured before the next swing.
     */
    fun onManualAlignment(swingCount: Int) {
        reset()
        knownSwingCount = swingCount
        awaitingBaseline = true
    }

    /**
     * The baseline attempt's answer: [fitAzimuthDegrees] is the fit's absolute azimuth calibration,
     * null when it refused. [calibrationDegrees] is the calibration the fit is compared with.
     */
    fun onBaseline(nowMs: Long, fitAzimuthDegrees: Double?, calibrationDegrees: Double) {
        if (!inFlight) return
        inFlight = false
        if (!awaitingBaseline || countAtStart != knownSwingCount) return
        if (fitAzimuthDegrees == null) {
            lastFailureMs = nowMs
            return
        }
        awaitingBaseline = false
        hasBaseline = true
        baselineDegrees = wrap(fitAzimuthDegrees - calibrationDegrees)
        lastCheckMs = nowMs
        swingSinceCheck = false
    }

    /**
     * A refine attempt's answer. Returns the azimuth step to add to the calibration, 0.0 when the
     * heading is still right, or null when nothing is to be done (a refusal, a step too large to be
     * a swing's jump, or a frame from before a later swing).
     */
    fun onRefine(nowMs: Long, fitAzimuthDegrees: Double?, calibrationDegrees: Double): Double? {
        if (!inFlight) return null
        inFlight = false
        if (!hasBaseline || countAtStart != knownSwingCount) return null
        val step = fitAzimuthDegrees?.let { wrap(it - baselineDegrees - calibrationDegrees) }
        if (step == null || abs(step) > maxStepDegrees) {
            lastFailureMs = nowMs
            // A refused periodic check waits for the next period; only a swing's is retried sooner.
            if (!swingSinceCheck) lastCheckMs = nowMs
            return null
        }
        lastCheckMs = nowMs
        swingSinceCheck = false
        return if (abs(step) < minStepDegrees) 0.0 else step
    }

    /** A calibration reset or a new session: nothing to refine until the next alignment. */
    fun reset() {
        hasBaseline = false
        baselineDegrees = 0.0
        awaitingBaseline = false
        swingSinceCheck = false
        lastSwingMs = Long.MIN_VALUE
        lastCheckMs = Long.MIN_VALUE
        lastFailureMs = Long.MIN_VALUE
        inFlight = false
    }

    companion object {
        /** The tilt recovery has the elevation back within ~1 s of a swing; the fit wants it in. */
        const val SETTLE_MS = 1_500L

        /** As the automatic alignment's first retry. */
        const val RETRY_MS = 3_000L

        /** The creep seen at rest, 0.05 °/s, is a degree by then. */
        const val PERIOD_MS = 20_000L

        /** The largest jump seen in the latest runs was 1.8°; 8° in earlier ones is a fit worth refusing. */
        const val MAX_STEP_DEGREES = 3.0

        /** Below this a fit-to-fit difference is noise, and a step would make a steady view tick. */
        const val MIN_STEP_DEGREES = 0.2

        private fun wrap(deg: Double): Double {
            var a = deg % 360.0
            if (a > 180.0) a -= 360.0
            if (a <= -180.0) a += 360.0
            return a
        }
    }
}

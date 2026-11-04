package com.dazix.caelis.domain.stability

import kotlin.math.sqrt

/** What the gyroscope says about how the phone is mounted. */
enum class HandheldState {
    /** Not enough samples yet, a gap in them, or no gyroscope: say nothing. */
    UNKNOWN,

    /** Quiet enough for a tripod, a table or a wall. */
    STEADY,

    /** Moving the way a hand moves: a time-lapse shot from here will jump frame to frame. */
    HANDHELD,
}

/**
 * Tells a phone held in the hand from one standing on a tripod, from the gyroscope over a sliding
 * window (3 s by default).
 *
 * A hand is never still: physiological tremor (8–12 Hz) keeps the angular rate above a few tenths of
 * a degree per second *all the time*, while a tripod sits at the sensor's noise floor. The decision
 * is therefore the **fraction of samples above a threshold**, not the RMS or the peak: the tap that
 * presses the shutter, or a finger on the screen of a phone that is on a tripod, is one short spike
 * that a peak or an RMS would turn into "handheld", but that covers well under a fifth of the window.
 *
 * Hysteresis between [enterFraction] and [exitFraction] keeps a borderline phone (a light tripod in
 * the wind) from flickering the warning on and off. A window that does not yet cover
 * [minCoverageMs], or one broken by a gap longer than [maxGapMs] (the sensor stopped, the CPU slept),
 * is [HandheldState.UNKNOWN]: an answer from half a window would be a guess, and a guess here is a
 * warning nobody can explain.
 *
 * Allocation-free after construction; samples older than the window are dropped from a fixed ring.
 */
class HandheldDetector(
    private val windowMs: Long = WINDOW_MS,
    private val minCoverageMs: Long = MIN_COVERAGE_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    thresholdDegPerSec: Double = THRESHOLD_DEG_PER_SEC,
    private val enterFraction: Double = ENTER_FRACTION,
    private val exitFraction: Double = EXIT_FRACTION,
    capacity: Int = CAPACITY,
) {
    private val thresholdRadPerSec = Math.toRadians(thresholdDegPerSec)
    private val times = LongArray(capacity)
    private val above = BooleanArray(capacity)
    private val rates = FloatArray(capacity)
    private var head = 0 // index of the oldest sample
    private var size = 0
    private var aboveCount = 0
    private var lastMs = Long.MIN_VALUE

    /** The current decision; changes only through [add] and [reset]. */
    var state: HandheldState = HandheldState.UNKNOWN
        private set

    /** Fraction of the window above the threshold, for the debug log; NaN when the window is empty. */
    val aboveFraction: Double
        get() = if (size == 0) Double.NaN else aboveCount.toDouble() / size

    /**
     * Median angular rate over the window in °/s, for the debug log the thresholds are tuned
     * against; NaN when the window is empty. Sorts a copy, so it allocates — never call it per sample.
     */
    fun medianDegPerSec(): Double {
        if (size == 0) return Double.NaN
        val copy = FloatArray(size) { rates[(head + it) % rates.size] }
        copy.sort()
        return Math.toDegrees(copy[size / 2].toDouble())
    }

    /**
     * Feeds one gyroscope sample (rad/s about the device axes) taken at [nowMs] and returns the
     * resulting state.
     */
    fun add(nowMs: Long, x: Float, y: Float, z: Float): HandheldState {
        val rate = sqrt((x * x + y * y + z * z).toDouble())
        if (rate.isNaN()) return state
        if (lastMs != Long.MIN_VALUE && (nowMs - lastMs > maxGapMs || nowMs < lastMs)) reset()
        lastMs = nowMs

        if (size == times.size) dropOldest()
        val tail = (head + size) % times.size
        times[tail] = nowMs
        val isAbove = rate > thresholdRadPerSec
        above[tail] = isAbove
        rates[tail] = rate.toFloat()
        if (isAbove) aboveCount++
        size++

        while (size > 0 && nowMs - times[head] > windowMs) dropOldest()

        // Only samples older than the window are dropped, so a regularly fed window that has once
        // covered the minimum stays just under [windowMs] until a gap resets it.
        if (nowMs - times[head] < minCoverageMs) return state
        val fraction = aboveCount.toDouble() / size
        // Between the two fractions the previous answer stands; from UNKNOWN that means no answer yet,
        // because a borderline first window is exactly the guess this class refuses to make.
        if (fraction >= enterFraction) {
            state = HandheldState.HANDHELD
        } else if (fraction <= exitFraction) {
            state = HandheldState.STEADY
        }
        return state
    }

    fun reset() {
        head = 0
        size = 0
        aboveCount = 0
        lastMs = Long.MIN_VALUE
        state = HandheldState.UNKNOWN
    }

    private fun dropOldest() {
        if (above[head]) aboveCount--
        head = (head + 1) % times.size
        size--
    }

    companion object {
        const val WINDOW_MS = 3_000L

        /** 90 % of the window: a sensor that runs a little slow must still produce an answer. */
        const val MIN_COVERAGE_MS = 2_700L
        const val MAX_GAP_MS = 500L

        /** Above a tripod's noise floor, below the tremor of a hand trying to hold still. */
        const val THRESHOLD_DEG_PER_SEC = 0.3
        const val ENTER_FRACTION = 0.6
        const val EXIT_FRACTION = 0.3

        /** 3 s at 200 Hz: enough for SENSOR_DELAY_GAME on any phone, and for FASTEST on most. */
        const val CAPACITY = 600
    }
}

package com.dazix.caelis.domain.arframe

import kotlin.math.abs

/** What the compass's own behaviour says about it, independent of what it reports. */
enum class CompassEvidence {
    /** Not enough turning seen yet; the reported accuracy has to decide. */
    UNKNOWN,

    /** A turn of at least [CompassConsistency.MIN_COVERAGE_DEG] kept ψ steady. */
    CONSISTENT,

    /** ψ swung with direction, or the field is not the Earth's. */
    INCONSISTENT,
}

/**
 * Whether the compass agrees with itself as the phone turns.
 *
 * Neither thing the platform reports could tell a good compass from a bad one (device logs of
 * 2026-11-11). The rotation vector's heading accuracy (`values[4]`) is a coarse step per session:
 * 30° all along on a Pixel 7 Pro whose compass was right, 60° or 120° on a Pixel 10 Pro, and 30°
 * there too when its overlay once stood 180° out. `event.accuracy` said MEDIUM or HIGH on both.
 *
 * What did tell them apart is ψ, the heading of the rotation vector minus that of the game vector
 * ([YawOffset]). The game vector has no magnetometer, so for a good compass ψ stays put whichever
 * way the phone points: within 1.8° over a full turn on the Pixel 7 Pro. A magnetometer bias the
 * calibration has not removed turns with the phone and bends north by direction: on the Pixel 10 Pro
 * ψ swung by ±22° over a turn, the same on every turn, and the overlay was tens of degrees out
 * wherever it had not been aligned. At an inclination of 67° the horizontal field is under 20 µT, so
 * a residual bias of ~7 µT is enough for that.
 *
 * So ψ is compared across directions: binned by the game vector's camera azimuth, over the last
 * [WINDOW_NS], from slow turns only. A still phone adds nothing — ψ drifts slowly with the game
 * vector itself (a few degrees per minute), and a radiator moved it 3° with the phone still; neither
 * is a direction-dependent error. A fast turn adds nothing either: the game vector itself drifts 8–25°
 * after one. A verdict needs [MIN_COVERAGE_DEG] of directions; between the two spread thresholds the
 * previous verdict stands, and without new turning it stands indefinitely.
 *
 * A field strength far from the Earth's ([addField]) is inconsistent at once, whatever the turn says:
 * a magnet beside the phone (202 µT on the Pixel 10 Pro) or a bias of the size its built-in magnets
 * cause. It must last [FIELD_HOLD_NS], and so must its return, so passing a magnet does not flicker.
 *
 * Angles are degrees, field µT, time the sensors' nanoseconds. Allocation-free.
 */
class CompassConsistency {

    private val binPsi = FloatArray(BIN_COUNT)
    private val binTimeNs = LongArray(BIN_COUNT) { NEVER }

    private var rateRefNs = NEVER
    private var rateRefAz = 0f
    private var turningSlowly = false

    private var headingVerdict = CompassEvidence.UNKNOWN

    private var expectedFieldUt = Float.NaN
    private var fieldAbnormal = false
    private var fieldFlipSinceNs = NEVER

    val evidence: CompassEvidence
        get() = if (fieldAbnormal) CompassEvidence.INCONSISTENT else headingVerdict

    /**
     * One same-instant pair: the game vector's camera azimuth and elevation, and ψ. Only a camera
     * near the horizon has an azimuth worth binning.
     */
    fun addPair(timestampNs: Long, cameraAzimuthDeg: Float, cameraElevationDeg: Float, psiDeg: Float) {
        updateRate(timestampNs, cameraAzimuthDeg)
        if (!turningSlowly || abs(cameraElevationDeg) > MAX_ELEVATION_DEG) return
        val bin = binOf(cameraAzimuthDeg)
        binPsi[bin] = psiDeg
        binTimeNs[bin] = timestampNs
        evaluate(timestampNs)
    }

    /** The calibrated magnetometer's field strength. */
    fun addField(timestampNs: Long, magnitudeMicroTesla: Float) {
        val abnormal = isAbnormal(magnitudeMicroTesla)
        if (abnormal == fieldAbnormal) {
            fieldFlipSinceNs = NEVER
            return
        }
        if (fieldFlipSinceNs == NEVER) fieldFlipSinceNs = timestampNs
        if (timestampNs - fieldFlipSinceNs >= FIELD_HOLD_NS) {
            fieldAbnormal = abnormal
            fieldFlipSinceNs = NEVER
        }
    }

    /** The field strength the geomagnetic model expects where the phone is; NaN when unknown. */
    fun setExpectedField(magnitudeMicroTesla: Float) {
        expectedFieldUt = magnitudeMicroTesla
    }

    /** A new session. The expected field belongs to the position and is kept. */
    fun reset() {
        binTimeNs.fill(NEVER)
        rateRefNs = NEVER
        turningSlowly = false
        headingVerdict = CompassEvidence.UNKNOWN
        fieldAbnormal = false
        fieldFlipSinceNs = NEVER
    }

    private fun isAbnormal(ut: Float): Boolean {
        if (ut.isNaN()) return false
        val expected = expectedFieldUt
        return if (expected.isNaN() || expected <= 0f) {
            ut < EARTH_MIN_UT || ut > EARTH_MAX_UT
        } else {
            val ratio = ut / expected
            ratio < 1f - FIELD_TOLERANCE || ratio > 1f + FIELD_TOLERANCE
        }
    }

    /** The turn rate over the last [RATE_STEP_NS] or more; a single 5 ms step is mostly noise. */
    private fun updateRate(timestampNs: Long, azimuthDeg: Float) {
        if (rateRefNs == NEVER) {
            rateRefNs = timestampNs
            rateRefAz = azimuthDeg
            turningSlowly = false
            return
        }
        val dtNs = timestampNs - rateRefNs
        if (dtNs < RATE_STEP_NS) return
        val rate = abs(wrap(azimuthDeg - rateRefAz)) / (dtNs / 1e9f)
        turningSlowly = rate in MIN_RATE_DEG_PER_S..MAX_RATE_DEG_PER_S
        rateRefNs = timestampNs
        rateRefAz = azimuthDeg
    }

    private fun evaluate(nowNs: Long) {
        var reference = Float.NaN
        var low = 0f
        var high = 0f
        var firstBin = -1
        var lastBin = -1
        var largestGap = 0
        for (i in 0 until BIN_COUNT) {
            if (nowNs - binTimeNs[i] > WINDOW_NS || binTimeNs[i] == NEVER) continue
            if (reference.isNaN()) {
                reference = binPsi[i]
                firstBin = i
            } else {
                val d = wrap(binPsi[i] - reference)
                if (d < low) low = d
                if (d > high) high = d
                if (i - lastBin > largestGap) largestGap = i - lastBin
            }
            lastBin = i
        }
        if (firstBin < 0) return
        val wrapGap = firstBin + BIN_COUNT - lastBin
        if (wrapGap > largestGap) largestGap = wrapGap
        val coverageDeg = (BIN_COUNT - largestGap) * BIN_DEG
        if (coverageDeg < MIN_COVERAGE_DEG) return
        val spread = high - low
        if (spread > INCONSISTENT_SPREAD_DEG) {
            headingVerdict = CompassEvidence.INCONSISTENT
        } else if (spread <= CONSISTENT_SPREAD_DEG) {
            headingVerdict = CompassEvidence.CONSISTENT
        }
    }

    private fun binOf(azimuthDeg: Float): Int {
        var a = azimuthDeg % 360f
        if (a < 0f) a += 360f
        return (a / BIN_DEG).toInt().coerceIn(0, BIN_COUNT - 1)
    }

    private fun wrap(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    companion object {
        const val BIN_DEG = 10
        const val BIN_COUNT = 360 / BIN_DEG

        /** Directions a verdict needs. */
        const val MIN_COVERAGE_DEG = 90

        /** ψ spread across directions above which the compass is bent: Pixel 10 Pro 22°+, Pixel 7 Pro 1.8°. */
        const val INCONSISTENT_SPREAD_DEG = 8f

        /** ψ spread within which a turn proves the compass. */
        const val CONSISTENT_SPREAD_DEG = 4f

        /** How long a ψ sample counts: a slow full turn took about 20 s on the device. */
        const val WINDOW_NS = 20_000_000_000L

        /** Slower than this is a still phone in the hand. */
        const val MIN_RATE_DEG_PER_S = 3f

        /** Faster than this the game vector itself is not trusted. */
        const val MAX_RATE_DEG_PER_S = 120f

        const val MAX_ELEVATION_DEG = 60f

        const val RATE_STEP_NS = 100_000_000L

        /** ±40 %: a radiator gave +12 %, a room on the Pixel 10 Pro up to +35 %, a magnet +305 %. */
        const val FIELD_TOLERANCE = 0.4f

        /** The Earth's surface field, roughly, for when the position is not known yet. */
        const val EARTH_MIN_UT = 20f
        const val EARTH_MAX_UT = 75f

        const val FIELD_HOLD_NS = 2_000_000_000L

        private const val NEVER = Long.MIN_VALUE
    }
}

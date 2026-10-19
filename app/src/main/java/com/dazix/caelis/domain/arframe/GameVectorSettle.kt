package com.dazix.caelis.domain.arframe

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Whether the game rotation vector has settled after the sensors started, from how far its tilt
 * disagrees with the rotation vector's.
 *
 * Found on the device (Pixel, Sun AR towards a ridge): right after a start the game vector's tilt
 * stood 10.8° away from the rotation vector's and closed in with a time constant of about 3.5 s.
 * Its yaw settles with it, so ψ ([YawOffset]) kept moving after an automatic alignment had locked
 * the heading 10 s in, and [HeadingLock] cancelled that as if it were a compass excursion: 5.8° of
 * heading in 12 s, which on a sloping ridge looks like the horizon sinking below the real one. On a
 * start where the two already agreed (0.2°) ψ moved by 0.006° in 6 s. The rotation vector is the one
 * with an absolute reference for the tilt (gravity and the compass), so the two agreeing means the
 * game vector has converged.
 *
 * Settled once the tilts have agreed within [maxDisagreementDegrees] for [holdNs] without a break,
 * or [timeoutNs] after the first sample on a device whose two vectors never quite agree (a constant
 * offset would otherwise keep the heading on the compass for ever). One-way until [reset]: a later
 * disagreement during a fast turn is the responsive rotation vector leading, not the game vector
 * starting over. Allocation-free; the matrices are `SensorManager` row-major, 9 or 16 elements.
 */
class GameVectorSettle(
    maxDisagreementDegrees: Double = MAX_DISAGREEMENT_DEGREES,
    private val holdNs: Long = HOLD_NS,
    private val timeoutNs: Long = TIMEOUT_NS,
) {
    private val minCos = kotlin.math.cos(Math.toRadians(maxDisagreementDegrees))
    private var firstNs = Long.MIN_VALUE
    private var agreeingSinceNs = Long.MIN_VALUE

    var isSettled: Boolean = false
        private set

    /** The latest tilt disagreement in degrees, for diagnostics; NaN before the first sample. */
    var disagreementDegrees: Double = Double.NaN
        private set

    /** Feeds one same-instant pair; returns [isSettled]. */
    fun add(timestampNs: Long, compassMatrix: FloatArray, gameMatrix: FloatArray): Boolean {
        val cos = upDot(compassMatrix, gameMatrix)
        disagreementDegrees = Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
        if (isSettled) return true
        if (firstNs == Long.MIN_VALUE) firstNs = timestampNs
        if (cos >= minCos) {
            if (agreeingSinceNs == Long.MIN_VALUE) agreeingSinceNs = timestampNs
        } else {
            agreeingSinceNs = Long.MIN_VALUE
        }
        if ((agreeingSinceNs != Long.MIN_VALUE && timestampNs - agreeingSinceNs >= holdNs) ||
            timestampNs - firstNs >= timeoutNs
        ) {
            isSettled = true
        }
        return isSettled
    }

    /** A new session: the game vector starts over. */
    fun reset() {
        isSettled = false
        firstNs = Long.MIN_VALUE
        agreeingSinceNs = Long.MIN_VALUE
        disagreementDegrees = Double.NaN
    }

    companion object {
        /** About a tenth of the 3.5 s decay's ψ rate is left here: a quarter of a degree in all. */
        const val MAX_DISAGREEMENT_DEGREES = 0.1
        const val HOLD_NS = 2_000_000_000L
        const val TIMEOUT_NS = 20_000_000_000L

        /**
         * Cosine between the two matrices' world up, in device coordinates: the third row. A
         * rotation about the vertical (the heading, which is what the two disagree on by design)
         * leaves it unchanged.
         */
        private fun upDot(a: FloatArray, b: FloatArray): Double {
            val stride = if (a.size >= 16) 4 else 3
            val row = 2 * stride
            val ax = a[row].toDouble()
            val ay = a[row + 1].toDouble()
            val az = a[row + 2].toDouble()
            val bx = b[row].toDouble()
            val by = b[row + 1].toDouble()
            val bz = b[row + 2].toDouble()
            val norm = sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz))
            return if (norm > 0.0) (ax * bx + ay * by + az * bz) / norm else -1.0
        }
    }
}

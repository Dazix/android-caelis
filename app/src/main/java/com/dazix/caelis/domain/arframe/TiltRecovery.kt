package com.dazix.caelis.domain.arframe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A world-frame tilt correction that brings the rotation vector's up back onto gravity as soon as
 * the phone is still, instead of waiting for the platform fusion to do it.
 *
 * Found on the device (Pixel 10 Pro, Sun AR towards a ridge): after a fast swing (300-380 °/s) the
 * rotation vector's camera elevation stood 1-2° above the accelerometer's and took about 4 s to come
 * back, with the phone held still and the accelerometer reading a clean 9.8 m/s². The overlay was
 * drawn exactly from the rotation vector, so it sank 1.4° below the ridge on a screenshot and crept
 * back up. The camera was not it: the capture results reported OIS and video stabilisation off.
 *
 * Each accelerometer sample is turned into the world frame with the fused pose of the same moment,
 * and the rotation that takes that "up" onto world Z is the error. It is smoothed in the world frame,
 * not in the device frame, so a slow pan does not drag a lagging gravity vector behind the phone.
 * Only a still phone counts: turning slower than [maxStillRateRadS] for [stillHoldNs] (the swing's
 * own deceleration is in the first samples after it) and the accelerometer within
 * [maxGravityDeviation] of 1 g. While it moves the correction is held: the accelerometer then
 * measures the hand.
 *
 * Two speeds, because one was wrong both ways. With a quarter-second time constant throughout the
 * 2° came back at once, but at rest the hand's tremor (0.2-0.5° of apparent tilt from a few
 * hundredths of a g) went straight into the overlay, which visibly bobbed around the ridge. The
 * second version took the fast speed whenever the error stood 0.3° off the correction, and restarted
 * its error filter from a single raw sample after every small movement of the hand: that sample was
 * the tremor, so the fast speed fired on nearly every twitch and the line shook. The third followed
 * gravity slowly at all times and fast only after a swing; the line was steadier, but next to the
 * fusion alone (an A/B switch in the debug options) a slight unrest was still visible. So gravity
 * is now consulted only within [fastWindowNs] after a swing faster than [swingRateRadS], the one
 * thing seen to leave the fusion lagging. There the error is low-passed over [prefilterSec] and
 * followed with [fastTimeConstantSec] while the two are more than [fastThresholdRad] apart and with
 * [slowTimeConstantSec] closer in; outside it the correction fades out with [fadeTimeConstantSec] and
 * nothing of the accelerometer reaches the overlay. An error beyond [maxCorrectionRad] is not a fusion lag but
 * something else (a phone being put down, a bump) and is ignored rather than followed.
 *
 * The correction is a small rotation about a horizontal axis, pre-multiplied onto the device->world
 * matrix like every other world-frame correction, so the heading is not touched. Allocation-free;
 * the matrices are `SensorManager` row-major, 9 or 16 elements.
 */
class TiltRecovery(
    private val fastTimeConstantSec: Double = FAST_TIME_CONSTANT_SEC,
    private val slowTimeConstantSec: Double = SLOW_TIME_CONSTANT_SEC,
    private val fastThresholdRad: Double = FAST_THRESHOLD_RAD,
    private val prefilterSec: Double = PREFILTER_SEC,
    private val swingRateRadS: Double = SWING_RATE_RAD_S,
    private val fastWindowNs: Long = FAST_WINDOW_NS,
    private val fadeTimeConstantSec: Double = FADE_TIME_CONSTANT_SEC,
    private val stillHoldNs: Long = STILL_HOLD_NS,
    private val maxStillRateRadS: Double = MAX_STILL_RATE_RAD_S,
    private val maxGravityDeviation: Double = MAX_GRAVITY_DEVIATION,
    private val maxCorrectionRad: Double = MAX_CORRECTION_RAD,
) {
    /** Rotation vector of the correction about world X (east), radians. */
    var correctionX: Double = 0.0
        private set

    /** Rotation vector of the correction about world Y (north), radians. */
    var correctionY: Double = 0.0
        private set

    /** The size of the correction in degrees, for diagnostics. */
    val correctionDegrees: Double
        get() = Math.toDegrees(sqrt(correctionX * correctionX + correctionY * correctionY))

    private var lastNs = Long.MIN_VALUE
    private var stillSinceNs = Long.MIN_VALUE
    private var errorX = 0.0
    private var errorY = 0.0
    private var hasError = false
    private var lastSwingNs = Long.MIN_VALUE

    /**
     * Feeds one accelerometer sample [ax], [ay], [az] (device frame, m/s²) taken at [timestampNs]
     * with the fused device->world pose [fused] of that moment and the phone's angular rate
     * [rateRadS]. Returns whether the sample moved the correction.
     */
    fun add(timestampNs: Long, fused: FloatArray, ax: Float, ay: Float, az: Float, rateRadS: Double): Boolean {
        val previousNs = lastNs
        lastNs = timestampNs
        if (previousNs == Long.MIN_VALUE || timestampNs <= previousNs || timestampNs - previousNs > MAX_STEP_NS) {
            // No interval to integrate over, or a gap (a paused sensor) that must not count as stillness.
            restartStillness()
            return false
        }
        val dtSec = (timestampNs - previousNs) / 1e9
        if (rateRadS > swingRateRadS) {
            lastSwingNs = timestampNs
            // The fusion's error is a new one now; the old estimate would only steer the first samples.
            hasError = false
        }
        if (lastSwingNs == Long.MIN_VALUE || timestampNs - lastSwingNs > fastWindowNs) {
            // Outside a swing's aftermath the fusion is trusted and gravity is not consulted at all:
            // whatever is left of the correction fades out smoothly, with nothing of the hand in it.
            val keep = exp(-dtSec / fadeTimeConstantSec)
            correctionX *= keep
            correctionY *= keep
            return false
        }

        val norm = sqrt(ax.toDouble() * ax + ay.toDouble() * ay + az.toDouble() * az)
        if (abs(norm - STANDARD_GRAVITY) > maxGravityDeviation || rateRadS > maxStillRateRadS) {
            restartStillness()
            return false
        }
        if (stillSinceNs == Long.MIN_VALUE) stillSinceNs = timestampNs
        if (timestampNs - stillSinceNs < stillHoldNs) return false

        val stride = if (fused.size >= 16) 4 else 3
        // World direction of the measured up: the fused pose applied to the gravity reading.
        val ux = (fused[0] * ax + fused[1] * ay + fused[2] * az) / norm
        val uy = (fused[stride] * ax + fused[stride + 1] * ay + fused[stride + 2] * az) / norm
        val uz = (fused[2 * stride] * ax + fused[2 * stride + 1] * ay + fused[2 * stride + 2] * az) / norm
        // The rotation taking it onto world Z: axis u x Z = (uy, -ux, 0), angle between u and Z.
        val horizontal = sqrt(ux * ux + uy * uy)
        val angle = atan2(horizontal, uz)
        if (angle > maxCorrectionRad) return false
        val scale = if (horizontal > 1e-12) angle / horizontal else 0.0
        val sampleX = uy * scale
        val sampleY = -ux * scale

        if (!hasError) {
            errorX = sampleX
            errorY = sampleY
            hasError = true
        } else {
            val beta = 1.0 - exp(-dtSec / prefilterSec)
            errorX += beta * (sampleX - errorX)
            errorY += beta * (sampleY - errorY)
        }

        val dx = errorX - correctionX
        val dy = errorY - correctionY
        val tau = if (dx * dx + dy * dy > fastThresholdRad * fastThresholdRad) {
            fastTimeConstantSec
        } else {
            slowTimeConstantSec
        }
        val alpha = 1.0 - exp(-dtSec / tau)
        correctionX += alpha * dx
        correctionY += alpha * dy
        return true
    }

    private fun restartStillness() {
        stillSinceNs = Long.MIN_VALUE
    }

    /**
     * Pre-multiplies the correction onto the device->world matrix [matrix] in place (its 3x3 part):
     * `M <- C * M`. Leaves it untouched while there is no correction.
     */
    fun applyTo(matrix: FloatArray) {
        val wx = correctionX
        val wy = correctionY
        val angle = sqrt(wx * wx + wy * wy)
        if (angle < 1e-9) return
        val kx = wx / angle
        val ky = wy / angle
        val c = cos(angle)
        val s = sin(angle)
        val t = 1.0 - c
        // Rodrigues for the axis (kx, ky, 0).
        val c00 = c + t * kx * kx
        val c01 = t * kx * ky
        val c02 = s * ky
        val c10 = t * kx * ky
        val c11 = c + t * ky * ky
        val c12 = -s * kx
        val c20 = -s * ky
        val c21 = s * kx
        val c22 = c
        val stride = if (matrix.size >= 16) 4 else 3
        for (col in 0..2) {
            val m0 = matrix[col].toDouble()
            val m1 = matrix[stride + col].toDouble()
            val m2 = matrix[2 * stride + col].toDouble()
            matrix[col] = (c00 * m0 + c01 * m1 + c02 * m2).toFloat()
            matrix[stride + col] = (c10 * m0 + c11 * m1 + c12 * m2).toFloat()
            matrix[2 * stride + col] = (c20 * m0 + c21 * m1 + c22 * m2).toFloat()
        }
    }

    /** A new session: the fusion starts over, and so does the correction. */
    fun reset() {
        correctionX = 0.0
        correctionY = 0.0
        lastNs = Long.MIN_VALUE
        lastSwingNs = Long.MIN_VALUE
        hasError = false
        restartStillness()
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665

        /** The fusion took ~4 s; a quarter of a second brings 2° down to the slow band at once. */
        const val FAST_TIME_CONSTANT_SEC = 0.25

        /** Long enough to average the hand's tremor away, short next to the fusion's own 4 s. */
        const val SLOW_TIME_CONSTANT_SEC = 2.0

        /** Above the tremor left after the prefilter (~0.1°), well below the 1-2° of a swing. */
        val FAST_THRESHOLD_RAD = Math.toRadians(0.3)

        /** The swings that left the fusion 1-2° behind turned at 300-380 °/s; a hand's twitch, 5-25. */
        val SWING_RATE_RAD_S = Math.toRadians(100.0)

        /** The fusion's own lag lasted about 4 s. */
        const val FAST_WINDOW_NS = 4_000_000_000L

        /** By then the fusion has caught up itself; what is left (a tenth or two) goes unnoticed. */
        const val FADE_TIME_CONSTANT_SEC = 3.0

        /** Takes the 8-12 Hz of a hand's tremor out of the error before it is compared. */
        const val PREFILTER_SEC = 0.1

        /** The swing's own deceleration is still in the accelerometer right after it. */
        const val STILL_HOLD_NS = 250_000_000L

        /** 5 °/s: a hand holding the phone on the ridge, not a swing. */
        val MAX_STILL_RATE_RAD_S = Math.toRadians(5.0)

        /** m/s² of hand acceleration that still reads as gravity alone. */
        const val MAX_GRAVITY_DEVIATION = 0.3

        /** The observed lag was 2°; ten degrees is a different event. */
        val MAX_CORRECTION_RAD = Math.toRadians(5.0)

        /** A longer silence (a paused sensor) starts the stillness over. */
        private const val MAX_STEP_NS = 100_000_000L
    }
}

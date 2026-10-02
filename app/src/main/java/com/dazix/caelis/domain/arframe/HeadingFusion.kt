package com.dazix.caelis.domain.arframe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates the yaw between ARCore's world and ENU ([ArWorldAlignment]) from a stream of noisy
 * measurements.
 *
 * Why fuse at all: ARCore holds its own heading rock-steady but does not know where north is; the
 * magnetometer knows north but wanders by degrees near metal and jitters sample to sample. The yaw
 * between the two frames is a *constant* for as long as ARCore keeps tracking, so it can be averaged
 * down hard — the result is ARCore's stability pointed at the compass's north.
 *
 * Rules, each one a failure it prevents:
 * - A compass sample below MEDIUM accuracy is ignored: an uncalibrated magnetometer is not noise,
 *   it is a bias, and averaging a bias does not remove it.
 * - Once converged, a sample more than [outlierDegrees] off is rejected (a car driving past, a
 *   steel railing). [resetAfterRejects] rejections in a row mean the estimate itself is wrong
 *   (ARCore relocalised and its world jumped) and it starts over.
 * - A Geospatial yaw with accuracy under [geospatialMaxAccuracyDegrees] outranks the compass for
 *   [geospatialHoldNanos]: VPS is measured against imagery, the compass against a field.
 * - Not converged means "not known": [isConverged] gates the hand-over to ARCore.
 *
 * Angles are radians, counter-clockwise about Up (the `psi` of [ArWorldAlignment]). Zero allocations.
 */
class HeadingFusion(
    private val compassAlpha: Float = 0.05f,
    private val geospatialAlpha: Float = 0.25f,
    private val outlierDegrees: Float = 25f,
    private val resetAfterRejects: Int = 30,
    private val minSamples: Int = 30,
    private val maxUncertaintyDegrees: Float = 1f,
    private val geospatialMaxAccuracyDegrees: Float = 10f,
    private val geospatialHoldNanos: Long = 2_000_000_000L,
) {
    enum class Source { NONE, COMPASS, GEOSPATIAL }

    private var cx = 0f
    private var sy = 0f
    private var meanSqDeviation = 0f
    private var samples = 0
    private var rejectsInRow = 0
    private var lastGeospatialNs = Long.MIN_VALUE
    private var lastAlpha = 1f

    var source: Source = Source.NONE
        private set

    /** Current yaw estimate; meaningful only when [samples] > 0. */
    val yaw: Float get() = atan2(sy, cx)

    val spreadDegrees: Float get() = Math.toDegrees(sqrt(meanSqDeviation).toDouble()).toFloat()

    /**
     * Standard error of the estimate, not the spread of the samples: an exponential average with
     * weight `a` shrinks sample noise by `sqrt(a / (2 - a))` (0.16 at 0.05), so a compass jittering
     * by 5 deg still yields a sub-degree heading once enough samples are in.
     */
    val uncertaintyDegrees: Float
        get() = spreadDegrees * sqrt(lastAlpha / (2f - lastAlpha))

    val isConverged: Boolean
        get() = samples >= minSamples && uncertaintyDegrees <= maxUncertaintyDegrees

    /** @param accuracy Android `SensorManager.SENSOR_STATUS_*` of the magnetometer. */
    fun addCompass(yaw: Float, accuracy: Int, timestampNs: Long) {
        if (accuracy < SENSOR_STATUS_ACCURACY_MEDIUM) return
        if (lastGeospatialNs != Long.MIN_VALUE && timestampNs - lastGeospatialNs < geospatialHoldNanos) return
        if (accept(yaw, compassAlpha) && source != Source.GEOSPATIAL) source = Source.COMPASS
    }

    fun addGeospatial(yaw: Float, yawAccuracyDegrees: Float, timestampNs: Long) {
        if (!(yawAccuracyDegrees <= geospatialMaxAccuracyDegrees)) return
        if (source != Source.GEOSPATIAL) {
            // The first trustworthy VPS fix replaces a compass estimate outright rather than being
            // averaged into its bias.
            reset()
        }
        lastGeospatialNs = timestampNs
        if (accept(yaw, geospatialAlpha)) source = Source.GEOSPATIAL
    }

    fun reset() {
        cx = 0f; sy = 0f; meanSqDeviation = 0f
        samples = 0; rejectsInRow = 0; lastAlpha = 1f
        source = Source.NONE
        lastGeospatialNs = Long.MIN_VALUE
    }

    private fun accept(measured: Float, alpha: Float): Boolean {
        if (samples == 0) {
            cx = cos(measured); sy = sin(measured)
            samples = 1
            meanSqDeviation = 0f
            return true
        }
        val deviation = wrap(measured - yaw)
        if (isConverged && abs(deviation) > Math.toRadians(outlierDegrees.toDouble()).toFloat()) {
            rejectsInRow++
            if (rejectsInRow >= resetAfterRejects) {
                reset()
                return accept(measured, alpha)
            }
            return false
        }
        rejectsInRow = 0
        // Early samples get a larger weight so the estimate is not stuck on the first reading.
        val a = maxOf(alpha, 1f / (samples + 1))
        lastAlpha = a
        cx = (1 - a) * cx + a * cos(measured)
        sy = (1 - a) * sy + a * sin(measured)
        val n = sqrt(cx * cx + sy * sy)
        if (n > 1e-6f) { cx /= n; sy /= n }
        meanSqDeviation = (1 - a) * meanSqDeviation + a * deviation * deviation
        samples++
        return true
    }

    companion object {
        /** `SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM`, repeated to keep this file Android-free. */
        const val SENSOR_STATUS_ACCURACY_MEDIUM = 2

        fun wrap(angle: Float): Float {
            var a = angle
            val twoPi = (2 * PI).toFloat()
            while (a > PI) a -= twoPi
            while (a < -PI) a += twoPi
            return a
        }
    }
}

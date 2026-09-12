package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * How far out it is worth looking for terrain, decided from the data already on disk.
 *
 * The tile the observer is standing in is downloaded whatever happens and covers a whole degree —
 * about 111 km by 71 km at our latitudes — so this estimate costs nothing. That matters: without it
 * the search radius is a number the user has to guess, and guessing high means fetching up to nine
 * more tiles, a couple of hundred megabytes, to discover they changed nothing.
 *
 * The bound is a real one rather than a heuristic. Past the distance where a peak of the highest
 * elevation anywhere nearby would sink below the skyline already found, nothing further out can
 * change the answer on that azimuth, no matter what is there.
 */
object HorizonRangeEstimator {

    /** Sample the grid this many times across each axis when looking for the tallest thing nearby. */
    private const val RELIEF_SAMPLES = 96

    /** How far across the surrounding terrain is scanned for its highest point, in degrees. */
    private const val RELIEF_SPAN_DEGREES = 0.9

    /**
     * Suggests a search radius in metres.
     *
     * @param probeRangeMeters how far the estimate itself may look. Bounded by the data actually
     *   loaded, not by what the user might download: the whole point is to answer before downloading.
     * @param maxRangeMeters never suggest more than this, whatever the terrain says.
     */
    fun suggestRangeMeters(
        grid: ElevationGrid,
        observer: GeoLocation,
        eyeHeightMeters: Double,
        probeRangeMeters: Double,
        maxRangeMeters: Double,
    ): Double {
        require(probeRangeMeters > 0.0) { "probeRangeMeters must be positive, got $probeRangeMeters" }

        val probe = HorizonProfileCalculator.compute(
            grid = grid,
            observer = observer,
            eyeHeightMeters = eyeHeightMeters,
            maxRangeMeters = probeRangeMeters,
        )
        val eyeElevation = probe.observerGroundElevationMeters + eyeHeightMeters
        val highest = highestNearby(grid, observer)

        // Nothing around stands above the eye: no distant peak can clear the curvature either, and
        // the useful radius is however far the probe already looked.
        if (highest <= eyeElevation) return probeRangeMeters.coerceAtMost(maxRangeMeters)

        var furthest = 0.0
        for (index in 0 until HorizonProfile.SAMPLE_COUNT) {
            val skyline = probe.elevationsDegrees[index].toDouble()
            furthest = max(furthest, reachOf(highest - eyeElevation, skyline))
        }

        return furthest.coerceIn(probeRangeMeters.coerceAtMost(maxRangeMeters), maxRangeMeters)
    }

    /**
     * The distance at which terrain [heightAboveEye] metres above the observer would sink to
     * [skylineDegrees], and so stop being able to raise the horizon any further.
     *
     * Solving `(h - d²/(2R)) / d = tan(skyline)` for d, with R the refraction-corrected radius the
     * rest of the pipeline uses. With a skyline of zero it reduces to the familiar `sqrt(2Rh)`.
     */
    internal fun reachOf(heightAboveEye: Double, skylineDegrees: Double): Double {
        if (heightAboveEye <= 0.0) return 0.0
        val radius = HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS
        val slope = tan(skylineDegrees * Math.PI / 180.0)
        return radius * (sqrt(slope * slope + 2.0 * heightAboveEye / radius) - slope)
    }

    /**
     * The highest elevation on a coarse grid around the observer.
     *
     * Subsampled hard on purpose: a summit is hundreds of metres across and the answer only has to be
     * good enough to bound a distance, while reading every post of a 3601² tile would take longer
     * than the profile itself.
     */
    private fun highestNearby(grid: ElevationGrid, observer: GeoLocation): Double {
        var highest = Double.NEGATIVE_INFINITY
        val step = RELIEF_SPAN_DEGREES / (RELIEF_SAMPLES - 1)
        val originLat = observer.latitude - RELIEF_SPAN_DEGREES / 2.0
        val originLon = observer.longitude - RELIEF_SPAN_DEGREES / 2.0
        for (row in 0 until RELIEF_SAMPLES) {
            val latitude = originLat + row * step
            for (column in 0 until RELIEF_SAMPLES) {
                val value = grid.elevationAt(latitude, originLon + column * step) ?: continue
                if (value > highest) highest = value
            }
        }
        return if (highest == Double.NEGATIVE_INFINITY) 0.0 else highest
    }
}

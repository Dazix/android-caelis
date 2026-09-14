package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.atan2
import kotlin.math.cos
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
 * elevation in that azimuth sector would sink below the skyline already found, nothing further out
 * can change the answer on that azimuth, no matter what is there.
 */
object HorizonRangeEstimator {

    /** Number of directional sectors partitioning the 360° field (5° per sector). */
    const val SECTOR_COUNT: Int = 72

    /** Angular width of each sector in degrees. */
    const val DEGREES_PER_SECTOR: Double = 360.0 / SECTOR_COUNT

    /** Number of 0.25° horizon profile samples mapped into a single 5° sector. */
    const val SAMPLES_PER_SECTOR: Int = HorizonProfile.SAMPLE_COUNT / SECTOR_COUNT

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
        val sectorPeaks = highestNearbyPerAzimuth(grid, observer, eyeElevation)

        var furthest = 0.0
        for (index in 0 until HorizonProfile.SAMPLE_COUNT) {
            val skyline = probe.elevationsDegrees[index].toDouble()
            val clampedSkyline = max(0.0, skyline)
            val sector = index / SAMPLES_PER_SECTOR
            val sectorPeak = sectorPeaks[sector]
            if (sectorPeak > eyeElevation) {
                furthest = max(furthest, reachOf(sectorPeak - eyeElevation, clampedSkyline))
            }
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
     * Finds the highest elevation in each of the 72 directional 5° sectors around the observer,
     * smoothed across adjacent sectors.
     *
     * Subsampled on a coarse 96x96 grid: a summit is hundreds of metres across and the answer only
     * needs to bound the distance per direction, avoiding reading every post of a 3601² tile.
     */
    internal fun highestNearbyPerAzimuth(
        grid: ElevationGrid,
        observer: GeoLocation,
        eyeElevation: Double,
    ): DoubleArray {
        val rawPeaks = DoubleArray(SECTOR_COUNT) { eyeElevation }
        val step = RELIEF_SPAN_DEGREES / (RELIEF_SAMPLES - 1)
        val originLat = observer.latitude - RELIEF_SPAN_DEGREES / 2.0
        val originLon = observer.longitude - RELIEF_SPAN_DEGREES / 2.0
        val cosLat = cos(observer.latitude * Math.PI / 180.0)

        for (row in 0 until RELIEF_SAMPLES) {
            val latitude = originLat + row * step
            val dLat = latitude - observer.latitude
            for (column in 0 until RELIEF_SAMPLES) {
                val longitude = originLon + column * step
                val value = grid.elevationAt(latitude, longitude) ?: continue
                if (value <= eyeElevation) continue

                val dLon = longitude - observer.longitude
                val x = dLon * cosLat
                val y = dLat
                if (x == 0.0 && y == 0.0) continue

                var azDeg = Math.toDegrees(atan2(x, y))
                if (azDeg < 0.0) azDeg += 360.0
                if (azDeg >= 360.0) azDeg -= 360.0

                val sector = (azDeg / DEGREES_PER_SECTOR).toInt().coerceIn(0, SECTOR_COUNT - 1)
                if (value > rawPeaks[sector]) {
                    rawPeaks[sector] = value
                }
            }
        }

        val smoothed = DoubleArray(SECTOR_COUNT)
        for (s in 0 until SECTOR_COUNT) {
            val prev = if (s == 0) rawPeaks[SECTOR_COUNT - 1] else rawPeaks[s - 1]
            val next = if (s == SECTOR_COUNT - 1) rawPeaks[0] else rawPeaks[s + 1]
            smoothed[s] = max(rawPeaks[s], max(prev, next))
        }
        return smoothed
    }
}

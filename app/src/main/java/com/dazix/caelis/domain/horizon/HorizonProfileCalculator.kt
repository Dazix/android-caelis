package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an [ElevationGrid] into a [HorizonProfile] by marching a ray outwards along every azimuth
 * and keeping the highest apparent altitude any terrain sample subtends.
 *
 * Pure Kotlin and deterministic, so the geometry can be checked against synthetic terrain with known
 * answers instead of against a screenshot.
 */
object HorizonProfileCalculator {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    /** Mean Earth radius in metres. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /**
     * Standard atmospheric refraction coefficient for surveying. Light bends towards the Earth, so
     * distant terrain appears higher than geometry alone predicts; folding it into an enlarged
     * effective radius is the usual approximation and is what surveyors and PeakFinder-style tools use.
     */
    const val REFRACTION_COEFFICIENT: Double = 0.13

    /** Earth radius corrected for refraction: everything beyond a few kilometres uses this, not the real one. */
    const val EFFECTIVE_EARTH_RADIUS_METERS: Double = EARTH_RADIUS_METERS / (1.0 - REFRACTION_COEFFICIENT)

    /** Nothing on Earth stands higher than this; used only to stop a ray that can no longer win. */
    private const val MAX_TERRAIN_ELEVATION_METERS = 9_000.0

    /**
     * How many samples in a row may come back `null` before the ray is abandoned.
     *
     * A void pixel or two is noise inside otherwise good data; a long run of them means the ray has
     * walked off the edge of the loaded tiles, and there is nothing further out to find.
     */
    private const val MAX_CONSECUTIVE_GAPS = 64

    /** Defaults chosen so the whole profile stays under a second on a phone. */
    const val DEFAULT_MAX_RANGE_METERS: Double = 60_000.0
    const val DEFAULT_EYE_HEIGHT_METERS: Double = 1.6

    /**
     * Computes the skyline around [observer].
     *
     * The observer's own elevation is read from [grid] and [eyeHeightMeters] added on top.
     * `GeoLocation.altitudeMeters` is used only as a fallback, because a phone's GPS altitude is
     * routinely tens of metres out — enough to tilt the whole horizon visibly — while the DEM value
     * is the same surface the rest of the ray march is measured against.
     *
     * @param onProgress optional 0..1 callback, called a few dozen times over the run.
     */
    fun compute(
        grid: ElevationGrid,
        observer: GeoLocation,
        eyeHeightMeters: Double = DEFAULT_EYE_HEIGHT_METERS,
        maxRangeMeters: Double = DEFAULT_MAX_RANGE_METERS,
        nowEpochMillis: Long = System.currentTimeMillis(),
        onProgress: ((Float) -> Unit)? = null,
    ): HorizonProfile {
        require(maxRangeMeters > 0.0) { "maxRangeMeters must be positive, got $maxRangeMeters" }

        val groundElevation = grid.elevationAt(observer.latitude, observer.longitude)
            ?: observer.altitudeMeters
        val eyeElevation = groundElevation + eyeHeightMeters

        val distances = buildDistanceLadder(maxRangeMeters)
        val sinDelta = DoubleArray(distances.size)
        val cosDelta = DoubleArray(distances.size)
        for (i in distances.indices) {
            val delta = distances[i] / EARTH_RADIUS_METERS
            sinDelta[i] = sin(delta)
            cosDelta[i] = cos(delta)
        }
        // Curvature plus refraction drop, precomputed per rung: it depends only on distance.
        val drop = DoubleArray(distances.size) { i ->
            distances[i] * distances[i] / (2.0 * EFFECTIVE_EARTH_RADIUS_METERS)
        }

        val latRad = observer.latitude * RAD
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val lonRad = observer.longitude * RAD

        val floorAltitude = geometricHorizonDipDegrees(eyeElevation)
        val elevations = FloatArray(HorizonProfile.SAMPLE_COUNT)

        var progressMark = 0
        var coveredSamples = 0
        for (azIndex in 0 until HorizonProfile.SAMPLE_COUNT) {
            val bearingRad = HorizonProfile.azimuthForIndex(azIndex) * RAD
            val sinBearing = sin(bearingRad)
            val cosBearing = cos(bearingRad)

            var best = floorAltitude
            var gaps = 0
            // An azimuth counts as covered when its ray was allowed to finish — either it ran the
            // full range or the ceiling test cut it off because nothing further could win. Running
            // out of elevation data is the one ending that does not count, and it is exactly what
            // happens on every azimuth when the observer's own tile is missing.
            var ranOutOfData = false

            for (step in distances.indices) {
                // Once even a 9 km peak at this distance could not out-top what we already have,
                // nothing further along this ray can either — the ceiling only falls from here.
                val ceiling = atan2(MAX_TERRAIN_ELEVATION_METERS - eyeElevation - drop[step], distances[step]) * DEG
                if (ceiling <= best) break

                val sinLat2 = sinLat * cosDelta[step] + cosLat * sinDelta[step] * cosBearing
                val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
                val lon2 = lonRad + atan2(
                    sinBearing * sinDelta[step] * cosLat,
                    cosDelta[step] - sinLat * sinLat2,
                )

                val terrain = grid.elevationAt(lat2 * DEG, lon2 * DEG)
                if (terrain == null) {
                    if (++gaps >= MAX_CONSECUTIVE_GAPS) {
                        ranOutOfData = true
                        break
                    }
                    continue
                }
                gaps = 0

                val altitude = atan2(terrain - eyeElevation - drop[step], distances[step]) * DEG
                if (altitude > best) best = altitude
            }

            elevations[azIndex] = best.toFloat()
            if (!ranOutOfData) coveredSamples++

            if (onProgress != null && azIndex - progressMark >= PROGRESS_STRIDE) {
                progressMark = azIndex
                onProgress((azIndex + 1).toFloat() / HorizonProfile.SAMPLE_COUNT)
            }
        }
        onProgress?.invoke(1f)

        return HorizonProfile(
            elevationsDegrees = elevations,
            observer = observer,
            observerGroundElevationMeters = groundElevation,
            eyeHeightMeters = eyeHeightMeters,
            maxRangeMeters = maxRangeMeters,
            generatedAtEpochMillis = nowEpochMillis,
            coveredSamples = coveredSamples,
        )
    }

    /**
     * How far below the astronomical horizon a smooth Earth falls away, seen from [eyeElevationMeters]
     * above sea level. Negative, and the value every azimuth starts from: terrain can only raise the
     * skyline above it, never lower it.
     */
    fun geometricHorizonDipDegrees(eyeElevationMeters: Double): Double {
        val height = max(0.0, eyeElevationMeters)
        return -sqrt(2.0 * height / EFFECTIVE_EARTH_RADIUS_METERS) * DEG
    }

    /**
     * Sample distances along a ray, spaced finely near the observer and coarsely far away.
     *
     * A constant 30 m step out to 60 km would be 2000 samples per azimuth for no gain: at 40 km a
     * 30 m step is a thousandth of a degree, far below what the profile stores, while near the
     * observer the same step is what keeps a close ridge from being missed entirely.
     */
    internal fun buildDistanceLadder(maxRangeMeters: Double): DoubleArray {
        val distances = ArrayList<Double>(800)
        var distance = NEAR_STEP_METERS
        while (distance <= maxRangeMeters) {
            distances += distance
            distance += when {
                distance < NEAR_RANGE_METERS -> NEAR_STEP_METERS
                distance < MID_RANGE_METERS -> MID_STEP_METERS
                else -> FAR_STEP_METERS
            }
        }
        if (distances.isEmpty()) distances += maxRangeMeters
        return distances.toDoubleArray()
    }

    private const val NEAR_STEP_METERS = 30.0
    private const val MID_STEP_METERS = 60.0
    private const val FAR_STEP_METERS = 120.0
    private const val NEAR_RANGE_METERS = 5_000.0
    private const val MID_RANGE_METERS = 20_000.0

    private const val PROGRESS_STRIDE = HorizonProfile.SAMPLE_COUNT / 40
}

/** Angular difference between two azimuths in degrees, always in [0, 180]. */
internal fun azimuthDelta(a: Double, b: Double): Double {
    val diff = abs(HorizonProfile.normalizeAzimuth(a) - HorizonProfile.normalizeAzimuth(b))
    return if (diff > 180.0) 360.0 - diff else diff
}

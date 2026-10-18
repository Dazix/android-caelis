package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation

/**
 * The apparent altitude of the terrain skyline, sampled uniformly all the way around the observer.
 *
 * Index `i` holds the altitude in degrees at azimuth `i * 360 / SAMPLE_COUNT`, measured clockwise
 * from true North. Values are typically small and may be negative: from a hilltop the whole skyline
 * sits below the astronomical horizon.
 *
 * This is the only artefact of the terrain pipeline that is kept. The elevation tiles it came from
 * are cache, deletable at any moment; a profile is 1440 floats and is what the renderer, the
 * terrain rise/set calculator and the night sky occlusion shader all read.
 */
class HorizonProfile(
    val elevationsDegrees: FloatArray,
    val observer: GeoLocation,
    val observerGroundElevationMeters: Double,
    val eyeHeightMeters: Double,
    val maxRangeMeters: Double,
    val generatedAtEpochMillis: Long,
    /**
     * How many of the [SAMPLE_COUNT] azimuths actually found terrain in the elevation model.
     *
     * The rest fell back to the geometric horizon dip, which is the right answer over open sea and
     * a lie everywhere else. This number is what tells the two apart. It exists because a profile
     * computed with the observer's own tile missing came out as a flat ring at the dip — a wrong
     * answer that drew exactly like a right one.
     */
    val coveredSamples: Int = SAMPLE_COUNT,
    /**
     * Named peaks that stand on this skyline, chosen by [LandmarkVisibility] when the profile was made.
     *
     * Kept with the profile, not looked up at draw time: the peak tiles are cache and Android may
     * reclaim them, while a drawn skyline has to keep its names with no signal.
     */
    val landmarks: List<HorizonLandmark> = emptyList(),
    /**
     * Whether every peak tile covering the profile gave an answer, and at least one of them was real
     * data (a tile that exists, even with no peaks in it) rather than "this region is not covered".
     *
     * An empty [landmarks] means different things: a covered region with no visible named peak, a fetch
     * that never happened (no network, no consent), and a region the data does not cover (yet). Only the
     * first is final; this flag is what lets the repository fill in the others later without asking
     * again about the first.
     */
    val landmarksResolved: Boolean = false,
) {

    init {
        require(elevationsDegrees.size == SAMPLE_COUNT) {
            "expected $SAMPLE_COUNT samples, got ${elevationsDegrees.size}"
        }
        require(coveredSamples in 0..SAMPLE_COUNT) {
            "coveredSamples must be in [0, $SAMPLE_COUNT], got $coveredSamples"
        }
    }

    /** The same skyline with a (new) set of peak names. The samples array is shared, not copied. */
    fun withLandmarks(landmarks: List<HorizonLandmark>, resolved: Boolean) = HorizonProfile(
        elevationsDegrees = elevationsDegrees,
        observer = observer,
        observerGroundElevationMeters = observerGroundElevationMeters,
        eyeHeightMeters = eyeHeightMeters,
        maxRangeMeters = maxRangeMeters,
        generatedAtEpochMillis = generatedAtEpochMillis,
        coveredSamples = coveredSamples,
        landmarks = landmarks,
        landmarksResolved = resolved,
    )

    /** Fraction of the sky whose skyline came from real elevation data, in [0, 1]. */
    val coverage: Float get() = coveredSamples.toFloat() / SAMPLE_COUNT

    /**
     * Whether this profile is worth drawing as terrain.
     *
     * Below the threshold it is mostly the mathematical horizon wearing a terrain colour, and
     * showing it would be worse than showing nothing: the user cannot tell a flat profile that
     * means "you are on a plain" from one that means "the data never loaded".
     */
    val isUsable: Boolean get() = coveredSamples >= MIN_USABLE_SAMPLES

    /** Height of the observer's eye above sea level — ground elevation plus eye height. */
    val observerEyeElevationMeters: Double get() = observerGroundElevationMeters + eyeHeightMeters

    /** The highest point of the skyline, in degrees. Useful for telling a plain from a valley. */
    val maxElevationDegrees: Float by lazy { elevationsDegrees.max() }

    /** The lowest point of the skyline, in degrees. */
    val minElevationDegrees: Float by lazy { elevationsDegrees.min() }

    /**
     * Terrain altitude in degrees at an arbitrary [azimuthDegrees], linearly interpolated between
     * the two neighbouring samples and wrapping across North.
     */
    fun elevationAt(azimuthDegrees: Double): Double {
        val normalized = normalizeAzimuth(azimuthDegrees)
        val position = normalized / STEP_DEGREES
        val index = position.toInt()
        val fraction = position - index

        val a = elevationsDegrees[index % SAMPLE_COUNT]
        val b = elevationsDegrees[(index + 1) % SAMPLE_COUNT]
        return a + (b - a) * fraction
    }

    /**
     * True when a body at ([azimuthDegrees], [elevationDegrees]) is behind the terrain.
     *
     * The comparison is strict: a body exactly on the skyline counts as visible, so that a
     * trajectory grazing a ridge is not reported as a set followed immediately by a rise.
     */
    fun isOccluded(azimuthDegrees: Double, elevationDegrees: Double): Boolean =
        elevationDegrees < elevationAt(azimuthDegrees)

    companion object {

        /**
         * 1440 samples, one every 0.25°. At 10 km that is an 44 m arc — finer than the 30 m DEM can
         * resolve at that distance, and it matches the 1440-point capacity the sky dome renderer
         * already allocates for trajectory polylines.
         */
        const val SAMPLE_COUNT: Int = 1440

        const val STEP_DEGREES: Double = 360.0 / SAMPLE_COUNT

        /**
         * Half the sky. A coastal observer legitimately has open water over a wide arc and still
         * has a real skyline inland, so the bar is not "all of it"; a profile with less than this
         * is one whose elevation data mostly did not arrive.
         */
        const val MIN_USABLE_SAMPLES: Int = SAMPLE_COUNT / 2

        /** The azimuth, in degrees, that sample [index] was taken at. */
        fun azimuthForIndex(index: Int): Double = index * STEP_DEGREES

        /** Normalises an azimuth into [0.0, 360.0). */
        fun normalizeAzimuth(azimuthDegrees: Double): Double {
            val result = azimuthDegrees % 360.0
            return if (result < 0.0) result + 360.0 else result
        }
    }
}

package com.dazix.caelis.domain.horizon

import kotlin.math.abs

/**
 * How far the overlay has to move to sit on the skyline the camera can see, and how sure of it we are.
 *
 * @param azimuthOffsetDegrees add this to the standing calibration's azimuth.
 * @param elevationOffsetDegrees add this to the standing calibration's elevation.
 * @param residualDegrees median distance between the photographed skyline and the terrain profile
 *   after the offsets are applied. A good fit on real terrain is a tenth of a degree or two.
 * @param usedColumns how many image columns carried a usable sky/terrain boundary.
 * @param azimuthDetermined false when the visible skyline is too flat to say anything about the
 *   compass. The elevation is still worth applying; the azimuth in that case is zero, not a guess.
 */
data class SkylineMatch(
    val azimuthOffsetDegrees: Double,
    val elevationOffsetDegrees: Double,
    val residualDegrees: Double,
    val usedColumns: Int,
    val azimuthDetermined: Boolean,
)

/**
 * Matches the sky/terrain boundary seen by the camera against the computed [HorizonProfile].
 *
 * This is the same procedure that was run offline against a screenshot while diagnosing the
 * misalignment: extract the boundary per image column, ask what direction each of those pixels looks
 * at, and find the shift that puts the two skylines on top of each other. On a real photograph of
 * Ještěd it converged to 2 px from three different starting points.
 *
 * Pure Kotlin: the camera-specific work (getting a luma plane, unprojecting pixels) happens in `ar/`,
 * so everything decided here can be held down by tests with no device.
 */
object SkylineMatcher {

    /** How far either way the compass is allowed to be wrong. Beyond this it is not a calibration. */
    const val SEARCH_AZIMUTH_DEGREES = 8.0

    private const val COARSE_STEP_DEGREES = 0.25
    private const val FINE_STEP_DEGREES = 0.02

    /** Below this the skyline in view carries no compass information — a flat sea or a plain. */
    private const val MIN_PROFILE_RELIEF_DEGREES = 0.4

    /** A fit worse than this is not a fit. Half a degree is about a sun's width. */
    const val MAX_ACCEPTABLE_RESIDUAL_DEGREES = 0.5

    /** The optimum has to stand out this much from the average candidate to count as a minimum. */
    private const val MAX_MINIMUM_RATIO = 0.7

    /**
     * Vegetation and buildings only ever stand *above* the bare-earth model, never below it, so the
     * vertical offset is taken from the low end of the differences rather than their middle. Using
     * the median would quietly fold the average tree height into the calibration.
     */
    private const val ELEVATION_QUANTILE = 0.2

    /**
     * @param azimuthsDeg direction of each sampled skyline pixel, as the phone currently believes it.
     * @param elevationsDeg elevation of the same pixels.
     * @param count how many entries of the two arrays are filled.
     * @return the offsets to apply, or null when the image does not support an answer.
     */
    fun match(
        azimuthsDeg: DoubleArray,
        elevationsDeg: DoubleArray,
        count: Int,
        profile: HorizonProfile,
    ): SkylineMatch? {
        if (count < MIN_COLUMNS) return null
        if (!profile.isUsable) return null

        // A skyline with no relief across the visible span pins the elevation but says nothing about
        // the azimuth: every horizontal shift fits equally well. Saying so beats inventing a number.
        val relief = profileRelief(azimuthsDeg, count, profile)
        if (relief < MIN_PROFILE_RELIEF_DEGREES) {
            val gap = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, 0.0)
            val residual = residualFor(azimuthsDeg, elevationsDeg, count, profile, 0.0, gap)
            if (residual > MAX_ACCEPTABLE_RESIDUAL_DEGREES) return null
            return SkylineMatch(0.0, -gap, residual, count, azimuthDetermined = false)
        }

        var bestAzimuth = 0.0
        var bestResidual = Double.MAX_VALUE
        var residualSum = 0.0
        var candidates = 0

        var azimuth = -SEARCH_AZIMUTH_DEGREES
        while (azimuth <= SEARCH_AZIMUTH_DEGREES + 1e-9) {
            val elevation = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, azimuth)
            val residual = residualFor(azimuthsDeg, elevationsDeg, count, profile, azimuth, elevation)
            residualSum += residual
            candidates++
            if (residual < bestResidual) {
                bestResidual = residual
                bestAzimuth = azimuth
            }
            azimuth += COARSE_STEP_DEGREES
        }

        // Refine around the coarse winner. The coarse grid is deliberately wider than the precision
        // anyone can see, so this is what turns "about right" into "on the ridge".
        var refined = bestAzimuth
        var step = COARSE_STEP_DEGREES / 2.0
        while (step >= FINE_STEP_DEGREES) {
            for (side in intArrayOf(-1, 1)) {
                val candidate = refined + side * step
                if (abs(candidate) > SEARCH_AZIMUTH_DEGREES) continue
                val elevation = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, candidate)
                val residual = residualFor(azimuthsDeg, elevationsDeg, count, profile, candidate, elevation)
                if (residual < bestResidual) {
                    bestResidual = residual
                    refined = candidate
                }
            }
            step /= 2.0
        }

        // A straight line of roofs or a cloud base can land on a low residual by accident if the
        // terrain happens to be gentle there. It cannot, however, have the *shape* of a ridge: what
        // the camera sees must rise and fall about as much as the terrain it is claimed to be.
        val observedRelief = spread(elevationsDeg, count)
        if (observedRelief < relief * MIN_OBSERVED_RELIEF_RATIO) return null

        val averageResidual = residualSum / candidates
        // A real ridge produces a sharp dip in the residual curve. A cloud line, a wall or a row of
        // roofs produces a curve that is flat to within noise, and picking its lowest point would be
        // picking noise.
        if (averageResidual <= 0.0 || bestResidual > averageResidual * MAX_MINIMUM_RATIO) return null
        if (bestResidual > MAX_ACCEPTABLE_RESIDUAL_DEGREES) return null

        // Negated on the way out: the gap is how much higher the camera *believes* the skyline sits,
        // and the calibration has to bring that belief down by the same amount.
        val gap = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, refined)
        return SkylineMatch(
            azimuthOffsetDegrees = refined,
            elevationOffsetDegrees = -gap,
            residualDegrees = bestResidual,
            usedColumns = count,
            azimuthDetermined = true,
        )
    }

    /** Fewer than this and the image is mostly sky, mostly wall, or mostly fog. */
    const val MIN_COLUMNS = 24

    /** How much of the terrain's relief the photographed skyline has to show to be that terrain. */
    private const val MIN_OBSERVED_RELIEF_RATIO = 0.5

    /** Spread of a sample, from the 10th to the 90th percentile, so a single outlier cannot set it. */
    private fun spread(values: DoubleArray, count: Int): Double {
        val sorted = values.copyOf(count)
        sorted.sort()
        val low = sorted[((count - 1) * 0.1).toInt().coerceIn(0, count - 1)]
        val high = sorted[((count - 1) * 0.9).toInt().coerceIn(0, count - 1)]
        return high - low
    }

    /** How much the terrain profile rises and falls across the azimuths the camera can see. */
    private fun profileRelief(azimuthsDeg: DoubleArray, count: Int, profile: HorizonProfile): Double {
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (i in 0 until count) {
            val value = profile.elevationAt(azimuthsDeg[i])
            if (value < min) min = value
            if (value > max) max = value
        }
        return max - min
    }

    private val scratch = ThreadLocal.withInitial { DoubleArray(0) }

    private fun differences(
        azimuthsDeg: DoubleArray,
        elevationsDeg: DoubleArray,
        count: Int,
        profile: HorizonProfile,
        azimuthOffset: Double,
    ): DoubleArray {
        var buffer = scratch.get()
        if (buffer.size < count) {
            buffer = DoubleArray(count)
            scratch.set(buffer)
        }
        // The sampled directions are what the phone *believes*; adding the candidate correction turns
        // them into true-world azimuths, which is the frame the profile is stored in.
        for (i in 0 until count) {
            buffer[i] = elevationsDeg[i] - profile.elevationAt(azimuthsDeg[i] + azimuthOffset)
        }
        return buffer
    }

    private fun elevationGapFor(
        azimuthsDeg: DoubleArray,
        elevationsDeg: DoubleArray,
        count: Int,
        profile: HorizonProfile,
        azimuthOffset: Double,
    ): Double {
        val diffs = differences(azimuthsDeg, elevationsDeg, count, profile, azimuthOffset)
        val sorted = diffs.copyOf(count)
        sorted.sort()
        val index = ((count - 1) * ELEVATION_QUANTILE).toInt().coerceIn(0, count - 1)
        return sorted[index]
    }

    private fun residualFor(
        azimuthsDeg: DoubleArray,
        elevationsDeg: DoubleArray,
        count: Int,
        profile: HorizonProfile,
        azimuthOffset: Double,
        elevationOffset: Double,
    ): Double {
        val diffs = differences(azimuthsDeg, elevationsDeg, count, profile, azimuthOffset)
        val sorted = DoubleArray(count) { abs(diffs[it] - elevationOffset) }
        sorted.sort()
        return sorted[count / 2]
    }
}

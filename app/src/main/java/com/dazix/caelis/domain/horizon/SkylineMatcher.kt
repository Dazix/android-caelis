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

/** Which gate turned a fit down, so "no horizon" can say what was actually missing. */
enum class SkylineRefusal {
    /** The frame or the geometry could not be turned into skyline directions at all. */
    NO_GEOMETRY,

    /** Too few image columns carried a sky/terrain boundary: sky, a wall, fog or darkness. */
    TOO_FEW_COLUMNS,

    /** The profile is not backed by enough elevation data to match against. */
    PROFILE_UNUSABLE,

    /** The photographed skyline is much flatter than the terrain it would have to be. */
    NOT_TERRAIN_SHAPED,

    /** The residual curve has no dip: every shift fits about equally badly. */
    NO_DISTINCT_MINIMUM,

    /** Another, separate shift fits almost as well: a repeated ridge, and no way to tell which. */
    AMBIGUOUS_MINIMUM,

    /** The best shift is at the edge of the search window, so the real one is probably beyond it. */
    OUT_OF_RANGE,

    /** Even the best shift leaves the two skylines too far apart. */
    RESIDUAL_TOO_HIGH,
}

/**
 * Everything the matcher found out, accepted or not. [match] is non-null exactly when [refusal] is
 * null. The numbers are for diagnosis (logs, the debug dump); NaN where a gate fired before them.
 */
data class SkylineFit(
    val match: SkylineMatch?,
    val refusal: SkylineRefusal?,
    val usedColumns: Int,
    val profileReliefDegrees: Double = Double.NaN,
    val observedReliefDegrees: Double = Double.NaN,
    val bestAzimuthDegrees: Double = Double.NaN,
    val bestResidualDegrees: Double = Double.NaN,
    val averageResidualDegrees: Double = Double.NaN,
    val runnerUpResidualDegrees: Double = Double.NaN,
) {
    companion object {
        fun refused(refusal: SkylineRefusal, usedColumns: Int) = SkylineFit(null, refusal, usedColumns)
    }
}

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

    /**
     * How far either way the compass is allowed to be wrong: the whole range a calibration can take
     * (`OrientationTracker.MAX_CALIBRATION_AZIMUTH`). It was ±8° once, and on the device a restart
     * left the heading 11° out (the gyro-held heading of the last session is not this session's), so
     * the one tool meant to fix that refused every time. A wider window makes a wrong dip likelier;
     * [MIN_RUNNER_UP_RATIO] is what pays for it.
     */
    const val SEARCH_AZIMUTH_DEGREES = 20.0

    /**
     * Another local minimum at least [RUNNER_UP_SEPARATION_DEGREES] away from the winner has to fit
     * clearly worse than it, or the fit is refused. Ridges repeat; a shift that lands on the wrong
     * one still has a small residual, and from the outside it looks exactly like a measured answer.
     */
    private const val MIN_RUNNER_UP_RATIO = 1.5
    private const val RUNNER_UP_SEPARATION_DEGREES = 2.0
    private const val MIN_RUNNER_UP_MARGIN_DEGREES = 0.05

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

    /** Further below the median than this, a column is a misread boundary, not bare ground. */
    private const val BELOW_MEDIAN_OUTLIER_DEGREES = 2.0

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
    ): SkylineMatch? = fit(azimuthsDeg, elevationsDeg, count, profile).match

    /** [match], with the reason for a refusal and the numbers each gate looked at. */
    fun fit(
        azimuthsDeg: DoubleArray,
        elevationsDeg: DoubleArray,
        count: Int,
        profile: HorizonProfile,
    ): SkylineFit {
        if (count < MIN_COLUMNS) return SkylineFit.refused(SkylineRefusal.TOO_FEW_COLUMNS, count)
        if (!profile.isUsable) return SkylineFit.refused(SkylineRefusal.PROFILE_UNUSABLE, count)

        // A skyline with no relief across the visible span pins the elevation but says nothing about
        // the azimuth: every horizontal shift fits equally well. Saying so beats inventing a number.
        val relief = profileRelief(azimuthsDeg, count, profile)
        if (relief < MIN_PROFILE_RELIEF_DEGREES) {
            val gap = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, 0.0)
            val residual = residualFor(azimuthsDeg, elevationsDeg, count, profile, 0.0, gap)
            val match = if (residual > MAX_ACCEPTABLE_RESIDUAL_DEGREES) {
                null
            } else {
                SkylineMatch(0.0, -gap, residual, count, azimuthDetermined = false)
            }
            return SkylineFit(
                match = match,
                refusal = if (match == null) SkylineRefusal.RESIDUAL_TOO_HIGH else null,
                usedColumns = count,
                profileReliefDegrees = relief,
                bestAzimuthDegrees = 0.0,
                bestResidualDegrees = residual,
            )
        }

        val steps = (2.0 * SEARCH_AZIMUTH_DEGREES / COARSE_STEP_DEGREES).toInt() + 1
        val curve = DoubleArray(steps)
        var bestIndex = 0
        var residualSum = 0.0
        for (index in 0 until steps) {
            val azimuth = -SEARCH_AZIMUTH_DEGREES + index * COARSE_STEP_DEGREES
            val elevation = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, azimuth)
            val residual = residualFor(azimuthsDeg, elevationsDeg, count, profile, azimuth, elevation)
            curve[index] = residual
            residualSum += residual
            if (residual < curve[bestIndex]) bestIndex = index
        }
        val bestAzimuth = -SEARCH_AZIMUTH_DEGREES + bestIndex * COARSE_STEP_DEGREES
        var bestResidual = curve[bestIndex]
        val runnerUp = runnerUpMinimum(curve, bestIndex)

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
        val averageResidual = residualSum / steps

        fun refused(refusal: SkylineRefusal) = SkylineFit(
            match = null,
            refusal = refusal,
            usedColumns = count,
            profileReliefDegrees = relief,
            observedReliefDegrees = observedRelief,
            bestAzimuthDegrees = refined,
            bestResidualDegrees = bestResidual,
            averageResidualDegrees = averageResidual,
            runnerUpResidualDegrees = runnerUp,
        )

        if (observedRelief < relief * MIN_OBSERVED_RELIEF_RATIO) return refused(SkylineRefusal.NOT_TERRAIN_SHAPED)

        // A real ridge produces a sharp dip in the residual curve. A cloud line, a wall or a row of
        // roofs produces a curve that is flat to within noise, and picking its lowest point would be
        // picking noise.
        if (averageResidual <= 0.0 || bestResidual > averageResidual * MAX_MINIMUM_RATIO) {
            return refused(SkylineRefusal.NO_DISTINCT_MINIMUM)
        }
        // The winner on the window's edge is most likely the slope down to a minimum beyond it.
        if (abs(refined) > SEARCH_AZIMUTH_DEGREES - COARSE_STEP_DEGREES) return refused(SkylineRefusal.OUT_OF_RANGE)
        // The absolute margin is for the near-zero residuals of a clean fit, where any ratio of
        // nothing is still nothing and two equally perfect shifts would both pass.
        if (runnerUp < maxOf(bestResidual * MIN_RUNNER_UP_RATIO, bestResidual + MIN_RUNNER_UP_MARGIN_DEGREES)) {
            return refused(SkylineRefusal.AMBIGUOUS_MINIMUM)
        }
        if (bestResidual > MAX_ACCEPTABLE_RESIDUAL_DEGREES) return refused(SkylineRefusal.RESIDUAL_TOO_HIGH)

        // Negated on the way out: the gap is how much higher the camera *believes* the skyline sits,
        // and the calibration has to bring that belief down by the same amount.
        val gap = elevationGapFor(azimuthsDeg, elevationsDeg, count, profile, refined)
        return SkylineFit(
            match = SkylineMatch(
                azimuthOffsetDegrees = refined,
                elevationOffsetDegrees = -gap,
                residualDegrees = bestResidual,
                usedColumns = count,
                azimuthDetermined = true,
            ),
            refusal = null,
            usedColumns = count,
            profileReliefDegrees = relief,
            observedReliefDegrees = observedRelief,
            bestAzimuthDegrees = refined,
            bestResidualDegrees = bestResidual,
            averageResidualDegrees = averageResidual,
            runnerUpResidualDegrees = runnerUp,
        )
    }

    /**
     * The lowest local minimum of the coarse residual [curve] that lies more than
     * [RUNNER_UP_SEPARATION_DEGREES] from [bestIndex]; infinity when there is none. The window's ends
     * count as minima when the curve is still falling into them.
     */
    private fun runnerUpMinimum(curve: DoubleArray, bestIndex: Int): Double {
        val separation = (RUNNER_UP_SEPARATION_DEGREES / COARSE_STEP_DEGREES).toInt()
        var runnerUp = Double.POSITIVE_INFINITY
        for (index in curve.indices) {
            if (abs(index - bestIndex) <= separation) continue
            val left = if (index > 0) curve[index - 1] else Double.POSITIVE_INFINITY
            val right = if (index < curve.size - 1) curve[index + 1] else Double.POSITIVE_INFINITY
            if (curve[index] <= left && curve[index] <= right && curve[index] < runnerUp) runnerUp = curve[index]
        }
        return runnerUp
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
        var buffer = scratch.get() ?: DoubleArray(0)
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
        // The low quantile assumes every wrong column stands above the terrain. A column whose
        // boundary was found far *below* it (a wall, a window frame) is not a tree but a misread,
        // and a fifth of those pulled the quantile 25° down on the device; they are left out.
        val floor = sorted[count / 2] - BELOW_MEDIAN_OUTLIER_DEGREES
        var start = 0
        while (start < count - 1 && sorted[start] < floor) start++
        val index = start + ((count - 1 - start) * ELEVATION_QUANTILE).toInt()
        return sorted[index.coerceIn(0, count - 1)]
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

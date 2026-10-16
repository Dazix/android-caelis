package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic alignment is only worth having if it refuses when it is unsure. A wrong offset looks
 * exactly like a measured one from the outside — the overlay simply sits somewhere else — so every
 * case where the image cannot support an answer is tested as carefully as the case where it can.
 */
class SkylineMatcherTest {

    /**
     * A skyline shaped like the one this feature was built against: a dominant peak with a lesser
     * shoulder beside it. The shape is what pins the compass — a smooth slope would fit equally well
     * at any horizontal shift, and the matcher is supposed to refuse that case, not guess at it.
     */
    private fun ridgeProfile(): HorizonProfile {
        val elevations = FloatArray(HorizonProfile.SAMPLE_COUNT) { index ->
            val azimuth = HorizonProfile.azimuthForIndex(index)
            (0.5 + peak(azimuth, centre = 250.0, height = 4.0, width = 3.0) +
                peak(azimuth, centre = 262.0, height = 1.6, width = 5.0)).toFloat()
        }
        return profileOf(elevations)
    }

    private fun peak(azimuth: Double, centre: Double, height: Double, width: Double): Double {
        val delta = ((azimuth - centre + 540.0) % 360.0) - 180.0
        return height * kotlin.math.exp(-(delta * delta) / (2.0 * width * width))
    }

    /** Open water: the same elevation whichever way you look. */
    private fun flatProfile(elevationDegrees: Float = -0.5f): HorizonProfile =
        profileOf(FloatArray(HorizonProfile.SAMPLE_COUNT) { elevationDegrees })

    private fun profileOf(elevations: FloatArray) = HorizonProfile(
        elevationsDegrees = elevations,
        observer = GeoLocation(50.75, 15.08, altitudeMeters = 430.0),
        observerGroundElevationMeters = 430.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 1L,
        coveredSamples = HorizonProfile.SAMPLE_COUNT,
    )

    /**
     * Samples across a 36° window centred on [centreAzimuth], as if the phone's compass were wrong by
     * [azimuthError] and its pitch by [elevationError]. Those are exactly the offsets the matcher has
     * to recover.
     */
    private fun observed(
        profile: HorizonProfile,
        centreAzimuth: Double = 250.0,
        azimuthError: Double,
        elevationError: Double,
        columns: Int = 160,
        noise: (Int) -> Double = { 0.0 },
    ): Pair<DoubleArray, DoubleArray> {
        val azimuths = DoubleArray(columns)
        val elevations = DoubleArray(columns)
        for (i in 0 until columns) {
            val believedAzimuth = centreAzimuth - 18.0 + 36.0 * i / (columns - 1)
            val trueAzimuth = believedAzimuth + azimuthError
            azimuths[i] = believedAzimuth
            elevations[i] = profile.elevationAt(trueAzimuth) + elevationError + noise(i)
        }
        return azimuths to elevations
    }

    @Test
    fun `it recovers the compass error the image was built with`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = 2.9, elevationError = 1.3)

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)

        assertNotNull("a clean synthetic ridge must match", match)
        match!!
        assertTrue(match.azimuthDetermined)
        assertEquals(2.9, match.azimuthOffsetDegrees, 0.1)
        assertEquals(-1.3, match.elevationOffsetDegrees, 0.1)
        assertTrue("residual should be tiny on noiseless input, was ${match.residualDegrees}", match.residualDegrees < 0.05)
    }

    @Test
    fun `it recovers an error the other way round`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = -4.0, elevationError = -0.8)

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)!!

        assertEquals(-4.0, match.azimuthOffsetDegrees, 0.1)
        assertEquals(0.8, match.elevationOffsetDegrees, 0.1)
    }

    /**
     * The reason the vertical offset comes from a low quantile rather than the middle: trees and
     * roofs stand above the bare-earth model on some columns and never below it, and a median would
     * fold their average height into the calibration.
     */
    @Test
    fun `vegetation on some columns does not drag the height offset up`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(
            profile,
            azimuthError = 1.0,
            elevationError = 0.0,
            // A stand of trees over part of the frame, half a degree above the bare ground. Trees
            // come in blocks, not in alternating columns, and a block is the harder case: it changes
            // the shape the fit sees rather than just adding noise to it.
            noise = { index -> if (index in 20 until 80) 0.5 else 0.0 },
        )

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)!!

        assertEquals(1.0, match.azimuthOffsetDegrees, 0.25)
        assertEquals("the bare ground columns are what sets the height", 0.0, match.elevationOffsetDegrees, 0.15)
    }

    /**
     * Found on the device: a fifth of the columns read a wall far below the ridge, the low quantile
     * landed on them, and every fit was refused with a 24° residual in front of a clear Ještěd.
     */
    @Test
    fun `columns misread far below the ridge do not drag the height down`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(
            profile,
            azimuthError = 1.5,
            elevationError = 0.4,
            noise = { index -> if (index % 4 == 0) -25.0 else 0.0 },
        )

        val fit = SkylineMatcher.fit(azimuths, elevations, azimuths.size, profile)

        assertNull("refused with ${fit.refusal}", fit.refusal)
        assertEquals(1.5, fit.match!!.azimuthOffsetDegrees, 0.1)
        assertEquals(-0.4, fit.match!!.elevationOffsetDegrees, 0.1)
    }

    @Test
    fun `a flat skyline gives the height but refuses to invent a compass correction`() {
        val profile = flatProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = 3.0, elevationError = 1.0)

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)!!

        assertFalse("open water says nothing about north", match.azimuthDetermined)
        assertEquals(0.0, match.azimuthOffsetDegrees, 1e-9)
        assertEquals(-1.0, match.elevationOffsetDegrees, 0.05)
    }

    @Test
    fun `a skyline that is nothing like the terrain is refused`() {
        val profile = ridgeProfile()
        // A row of roofs: a straight line at an elevation the terrain never reaches.
        val azimuths = DoubleArray(160) { 232.0 + 36.0 * it / 159.0 }
        val elevations = DoubleArray(160) { 14.0 }

        assertNull(SkylineMatcher.match(azimuths, elevations, azimuths.size, profile))
    }

    @Test
    fun `too few usable columns is refused rather than fitted`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = 2.0, elevationError = 0.0, columns = 160)

        assertNull(SkylineMatcher.match(azimuths, elevations, SkylineMatcher.MIN_COLUMNS - 1, profile))
    }

    @Test
    fun `an unusable profile is never matched against`() {
        val unusable = HorizonProfile(
            elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { 1f },
            observer = GeoLocation(50.75, 15.08),
            observerGroundElevationMeters = 430.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 1L,
            coveredSamples = 0,
        )
        val azimuths = DoubleArray(160) { 232.0 + 36.0 * it / 159.0 }
        val elevations = DoubleArray(160) { 1.0 }

        assertNull(SkylineMatcher.match(azimuths, elevations, azimuths.size, unusable))
    }

    /** A compass is wrong by degrees. An offset past the search window is a different problem. */
    @Test
    fun `an error larger than the search window is refused, not clamped to its edge`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = 25.0, elevationError = 0.0)

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)

        if (match != null) {
            assertTrue(
                "a match at the edge of the window is a guess, was ${match.azimuthOffsetDegrees}",
                kotlin.math.abs(match.azimuthOffsetDegrees) < SkylineMatcher.SEARCH_AZIMUTH_DEGREES - 0.5,
            )
        }
    }

    /**
     * Found on the device: a restart left the heading 11° out, the old ±8° window could not reach
     * it, and the button said "no skyline" in front of a perfectly clear Ještěd.
     */
    @Test
    fun `a compass error well past the old window is found`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, centreAzimuth = 235.0, azimuthError = 15.0, elevationError = 2.5)

        val fit = SkylineMatcher.fit(azimuths, elevations, azimuths.size, profile)

        assertNull("refused with ${fit.refusal}", fit.refusal)
        assertEquals(15.0, fit.match!!.azimuthOffsetDegrees, 0.1)
        assertEquals(-2.5, fit.match!!.elevationOffsetDegrees, 0.1)
    }

    @Test
    fun `a large error the other way is found too`() {
        val profile = ridgeProfile()
        // Believed 268°, really looking at 250°: the ridge is in view, just not where the compass says.
        val (azimuths, elevations) = observed(profile, centreAzimuth = 268.0, azimuthError = -18.0, elevationError = -7.0)

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)!!

        assertEquals(-18.0, match.azimuthOffsetDegrees, 0.1)
        assertEquals(7.0, match.elevationOffsetDegrees, 0.1)
    }

    /**
     * A wider window means more chances to land on the wrong ridge. A skyline that repeats itself
     * every few degrees fits just as well one tooth over, and the matcher must say so, not pick one.
     */
    @Test
    fun `a skyline that repeats within the window is refused as ambiguous`() {
        val elevations = FloatArray(HorizonProfile.SAMPLE_COUNT) { index ->
            val azimuth = HorizonProfile.azimuthForIndex(index)
            (2.0 + 1.5 * sin(Math.toRadians(azimuth * 45.0))).toFloat() // one tooth every 8°
        }
        val profile = profileOf(elevations)
        val (azimuths, observedElevations) = observed(profile, azimuthError = 3.0, elevationError = 0.0)

        val fit = SkylineMatcher.fit(azimuths, observedElevations, azimuths.size, profile)

        assertNull("a repeating skyline must not be matched, got ${fit.match}", fit.match)
        assertEquals(SkylineRefusal.AMBIGUOUS_MINIMUM, fit.refusal)
    }

    @Test
    fun `every refusal says which gate refused it`() {
        val profile = ridgeProfile()
        val (azimuths, elevations) = observed(profile, azimuthError = 2.0, elevationError = 0.0)
        assertEquals(
            SkylineRefusal.TOO_FEW_COLUMNS,
            SkylineMatcher.fit(azimuths, elevations, SkylineMatcher.MIN_COLUMNS - 1, profile).refusal,
        )

        val roofs = DoubleArray(160) { 14.0 }
        val fit = SkylineMatcher.fit(azimuths, roofs, azimuths.size, profile)
        assertNull(fit.match)
        assertEquals(SkylineRefusal.NOT_TERRAIN_SHAPED, fit.refusal)

        val accepted = SkylineMatcher.fit(azimuths, elevations, azimuths.size, profile)
        assertNull(accepted.refusal)
        assertNotNull(accepted.match)
    }

    @Test
    fun `moderate noise still lands on the right answer`() {
        val profile = ridgeProfile()
        val random = java.util.Random(20260912L)
        val (azimuths, elevations) = observed(
            profile,
            azimuthError = 2.0,
            elevationError = 0.5,
            noise = { random.nextGaussian() * 0.1 },
        )

        val match = SkylineMatcher.match(azimuths, elevations, azimuths.size, profile)!!

        assertEquals(2.0, match.azimuthOffsetDegrees, 0.3)
        assertEquals(-0.5, match.elevationOffsetDegrees, 0.3)
    }
}

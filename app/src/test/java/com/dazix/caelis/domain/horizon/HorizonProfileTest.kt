package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonProfileTest {

    private fun profileOf(fill: (Int) -> Float): HorizonProfile = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { fill(it) },
        observer = GeoLocation(50.0755, 14.4378),
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
    )

    @Test
    fun `samples map to azimuths at a quarter degree`() {
        assertEquals(1440, HorizonProfile.SAMPLE_COUNT)
        assertEquals(0.25, HorizonProfile.STEP_DEGREES, 1e-12)
        assertEquals(0.0, HorizonProfile.azimuthForIndex(0), 1e-12)
        assertEquals(90.0, HorizonProfile.azimuthForIndex(360), 1e-12)
        assertEquals(359.75, HorizonProfile.azimuthForIndex(1439), 1e-12)
    }

    @Test
    fun `reads a sample back at its own azimuth`() {
        val profile = profileOf { it * 0.01f }

        assertEquals(0.0, profile.elevationAt(0.0), 1e-6)
        assertEquals(3.60, profile.elevationAt(90.0), 1e-4)
        assertEquals(14.39, profile.elevationAt(359.75), 1e-4)
    }

    @Test
    fun `interpolates linearly between two samples`() {
        val profile = profileOf { if (it == 0) 0f else if (it == 1) 4f else 0f }

        // Halfway between sample 0 (0 deg) and sample 1 (0.25 deg)
        assertEquals(2.0, profile.elevationAt(0.125), 1e-5)
        assertEquals(1.0, profile.elevationAt(0.0625), 1e-5)
    }

    @Test
    fun `wraps across north rather than clamping at the end of the array`() {
        val profile = profileOf { if (it == HorizonProfile.SAMPLE_COUNT - 1) 8f else 0f }

        // Between the last sample (359.75 deg) and sample 0 (360 deg == 0 deg)
        assertEquals(8.0, profile.elevationAt(359.75), 1e-5)
        assertEquals(4.0, profile.elevationAt(359.875), 1e-5)
        assertEquals(0.0, profile.elevationAt(360.0), 1e-5)
    }

    @Test
    fun `normalises azimuths outside zero to three sixty`() {
        val profile = profileOf { if (it == 0) 5f else 0f }

        assertEquals(5.0, profile.elevationAt(720.0), 1e-5)
        assertEquals(5.0, profile.elevationAt(-360.0), 1e-5)
        assertEquals(0.0, HorizonProfile.normalizeAzimuth(-360.0), 1e-9)
        assertEquals(350.0, HorizonProfile.normalizeAzimuth(-10.0), 1e-9)
    }

    @Test
    fun `treats a body exactly on the skyline as visible`() {
        val profile = profileOf { 10f }

        assertTrue("below the ridge", profile.isOccluded(180.0, 9.99))
        assertFalse("exactly on the ridge", profile.isOccluded(180.0, 10.0))
        assertFalse("above the ridge", profile.isOccluded(180.0, 10.01))
    }

    @Test
    fun `reports the extremes of the skyline`() {
        val profile = profileOf { if (it == 100) 12.5f else if (it == 900) -3.5f else 0f }

        assertEquals(12.5f, profile.maxElevationDegrees)
        assertEquals(-3.5f, profile.minElevationDegrees)
    }

    @Test
    fun `exposes the eye elevation it was computed for`() {
        val profile = profileOf { 0f }

        assertEquals(201.6, profile.observerEyeElevationMeters, 1e-9)
    }

    @Test
    fun `refuses a sample count it cannot index`() {
        val error = runCatching {
            HorizonProfile(
                elevationsDegrees = FloatArray(360),
                observer = GeoLocation(0.0, 0.0),
                observerGroundElevationMeters = 0.0,
                eyeHeightMeters = 1.6,
                maxRangeMeters = 1.0,
                generatedAtEpochMillis = 0L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}

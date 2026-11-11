package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RidgeRecomputeTest {

    private fun profile(ridges: RidgeSamples?) = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT),
        observer = GeoLocation(50.0, 14.0),
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
        ridges = ridges,
    )

    private val someRidges = RidgeSamples(IntArray(HorizonProfile.SAMPLE_COUNT + 1), FloatArray(0), FloatArray(0))

    @Test
    fun `a profile with ridges is never recomputed`() {
        assertFalse(RidgeRecompute.needed(profile(someRidges), missingTiles = 0))
    }

    @Test
    fun `an old profile with every tile cached is recomputed`() {
        assertTrue(RidgeRecompute.needed(profile(null), missingTiles = 0))
    }

    @Test
    fun `an old profile missing a tile is left alone rather than downloading`() {
        assertFalse(RidgeRecompute.needed(profile(null), missingTiles = 1))
    }
}

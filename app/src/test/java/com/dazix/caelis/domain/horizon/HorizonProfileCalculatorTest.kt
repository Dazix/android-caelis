package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos

class HorizonProfileCalculatorTest {

    private val observer = GeoLocation(50.0, 14.0)
    private val eyeHeight = 1.6
    private val eyeElevation = eyeHeight // sea-level ground in every fixture below

    /** Terrain defined by a function of position, so a fixture is one line and has a known answer. */
    private class FunctionGrid(private val f: (Double, Double) -> Double?) : ElevationGrid {
        override fun elevationAt(latitude: Double, longitude: Double): Double? = f(latitude, longitude)
    }

    private val seaLevelEverywhere = FunctionGrid { _, _ -> 0.0 }

    /** Degrees of latitude corresponding to a north-south ground distance. */
    private fun metresToLatitudeDegrees(metres: Double): Double =
        metres / HorizonProfileCalculator.EARTH_RADIUS_METERS * (180.0 / Math.PI)

    private fun expectedAltitudeDegrees(terrainMetres: Double, distanceMetres: Double): Double {
        val drop = distanceMetres * distanceMetres /
            (2.0 * HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS)
        return atan2(terrainMetres - eyeElevation - drop, distanceMetres) * (180.0 / Math.PI)
    }

    @Test
    fun `refraction enlarges the effective earth radius`() {
        assertTrue(
            "refraction must make the Earth look flatter, not rounder",
            HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS > HorizonProfileCalculator.EARTH_RADIUS_METERS,
        )
        assertEquals(
            6_371_000.0 / 0.87,
            HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS,
            1.0,
        )
    }

    @Test
    fun `flat sea level terrain reproduces the geometric horizon dip on every azimuth`() {
        val profile = HorizonProfileCalculator.compute(
            grid = seaLevelEverywhere,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )
        val dip = HorizonProfileCalculator.geometricHorizonDipDegrees(eyeElevation)

        assertTrue("dip must be below the astronomical horizon, was $dip", dip < 0.0)
        for (index in 0 until HorizonProfile.SAMPLE_COUNT) {
            assertEquals(
                "azimuth ${HorizonProfile.azimuthForIndex(index)}",
                dip,
                profile.elevationsDegrees[index].toDouble(),
                0.001,
            )
        }
    }

    @Test
    fun `a wall of known height at a known distance subtends the angle geometry predicts`() {
        val wallDistance = 10_000.0
        val wallHeight = 500.0
        val wallLatitude = observer.latitude + metresToLatitudeDegrees(wallDistance)
        val grid = FunctionGrid { lat, _ -> if (lat >= wallLatitude) wallHeight else 0.0 }

        val profile = HorizonProfileCalculator.compute(
            grid = grid,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )

        // Due north, into the wall. The tolerance covers the sampling ladder's step near 10 km.
        assertEquals(
            expectedAltitudeDegrees(wallHeight, wallDistance),
            profile.elevationAt(0.0),
            0.05,
        )
        // Due south, away from it: nothing but sea level, so the skyline is the plain dip.
        assertEquals(
            HorizonProfileCalculator.geometricHorizonDipDegrees(eyeElevation),
            profile.elevationAt(180.0),
            0.001,
        )
    }

    @Test
    fun `curvature hides a hill that is too low to clear the bulge`() {
        // 50 m at 40 km: the hill's own horizon reaches 27 km, the observer's 4.8 km, so the two
        // never meet and the hill stays invisible no matter how clear the air is.
        val profile = computeWithHill(heightMetres = 50.0, distanceMetres = 40_000.0)
        val dip = HorizonProfileCalculator.geometricHorizonDipDegrees(eyeElevation)

        assertEquals(dip, profile.elevationAt(0.0), 0.001)
    }

    @Test
    fun `the same hill becomes visible once it is tall enough`() {
        val profile = computeWithHill(heightMetres = 200.0, distanceMetres = 40_000.0)

        assertTrue(
            "a 200 m hill at 40 km must stand above the horizon, was ${profile.elevationAt(0.0)}",
            profile.elevationAt(0.0) > 0.1,
        )
    }

    private fun computeWithHill(heightMetres: Double, distanceMetres: Double): HorizonProfile {
        val centreLatitude = observer.latitude + metresToLatitudeDegrees(distanceMetres)
        val radiusDegrees = metresToLatitudeDegrees(2_000.0)
        val cosLat = cos(observer.latitude * Math.PI / 180.0)
        val grid = FunctionGrid { lat, lon ->
            val dLat = lat - centreLatitude
            val dLon = (lon - observer.longitude) * cosLat
            if (dLat * dLat + dLon * dLon <= radiusDegrees * radiusDegrees) heightMetres else 0.0
        }
        return HorizonProfileCalculator.compute(
            grid = grid,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )
    }

    /**
     * The bug this guards against, seen on a device: the tile the observer was standing in had been
     * evicted from the cache mid-download, every ray ran out of data within the first two kilometres,
     * and the profile came back as a perfectly flat ring at the geometric dip — which drew exactly
     * like a real skyline. Coverage is what tells "you are on a plain" from "the data never arrived".
     */
    @Test
    fun `a grid with no data at all reports zero coverage and is not usable`() {
        val noData = FunctionGrid { _, _ -> null }

        val profile = HorizonProfileCalculator.compute(
            grid = noData,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )

        assertEquals(0, profile.coveredSamples)
        assertEquals(0f, profile.coverage, 1e-6f)
        assertTrue("a profile backed by nothing must not be drawn", !profile.isUsable)
    }

    @Test
    fun `terrain everywhere reports full coverage and is usable`() {
        val profile = HorizonProfileCalculator.compute(
            grid = seaLevelEverywhere,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )

        assertEquals(HorizonProfile.SAMPLE_COUNT, profile.coveredSamples)
        assertEquals(1f, profile.coverage, 1e-6f)
        assertTrue(profile.isUsable)
    }

    /**
     * A coastal observer has open water over a wide arc and a real skyline inland. That has to stay
     * usable, or the feature would refuse to work at exactly the places people photograph sunsets.
     */
    @Test
    fun `data over most of the sky is still usable`() {
        // Terrain over a bit more than the eastern half; west of the observer the model simply ends,
        // as it does at the edge of a loaded tile.
        val coast = FunctionGrid { _, lon -> if (lon >= observer.longitude - 0.01) 0.0 else null }

        val profile = HorizonProfileCalculator.compute(
            grid = coast,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )

        assertTrue(
            "expected part of the sky uncovered, got ${profile.coveredSamples}",
            profile.coveredSamples < HorizonProfile.SAMPLE_COUNT,
        )
        assertTrue(
            "expected more than half covered, got ${profile.coveredSamples}",
            profile.coveredSamples >= HorizonProfile.MIN_USABLE_SAMPLES,
        )
        assertTrue("a coast must stay usable", profile.isUsable)
    }

    @Test
    fun `an azimuth with no data falls back to the dip instead of inventing sea level`() {
        val noData = FunctionGrid { _, _ -> null }

        val profile = HorizonProfileCalculator.compute(
            grid = noData,
            observer = GeoLocation(50.0, 14.0, altitudeMeters = 300.0),
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 60_000.0,
        )

        // With no DEM at all the observer's own height comes from the location's altitude.
        assertEquals(300.0, profile.observerGroundElevationMeters, 1e-9)
        val dip = HorizonProfileCalculator.geometricHorizonDipDegrees(300.0 + eyeHeight)
        for (value in profile.elevationsDegrees) {
            assertEquals(dip, value.toDouble(), 1e-4)
        }
    }

    @Test
    fun `records the inputs it was computed from`() {
        val profile = HorizonProfileCalculator.compute(
            grid = seaLevelEverywhere,
            observer = observer,
            eyeHeightMeters = 2.5,
            maxRangeMeters = 30_000.0,
            nowEpochMillis = 1_234_567L,
        )

        assertEquals(observer, profile.observer)
        assertEquals(0.0, profile.observerGroundElevationMeters, 1e-9)
        assertEquals(2.5, profile.eyeHeightMeters, 1e-9)
        assertEquals(30_000.0, profile.maxRangeMeters, 1e-9)
        assertEquals(1_234_567L, profile.generatedAtEpochMillis)
    }

    @Test
    fun `reports progress and finishes at one`() {
        var last = -1f
        var calls = 0

        HorizonProfileCalculator.compute(
            grid = seaLevelEverywhere,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 10_000.0,
            onProgress = {
                assertTrue("progress must not go backwards", it >= last)
                last = it
                calls++
            },
        )

        assertTrue("expected several progress callbacks, got $calls", calls > 10)
        assertEquals(1f, last, 1e-6f)
    }

    @Test
    fun `the distance ladder is monotonic, bounded and fine near the observer`() {
        val ladder = HorizonProfileCalculator.buildDistanceLadder(60_000.0)

        assertTrue(ladder.isNotEmpty())
        assertTrue("first rung must be close in, was ${ladder.first()}", ladder.first() <= 30.0)
        assertTrue("last rung must stay in range, was ${ladder.last()}", ladder.last() <= 60_000.0)
        for (i in 1 until ladder.size) {
            assertTrue("ladder must increase at $i", ladder[i] > ladder[i - 1])
        }
        // Fine near the observer, coarse far away: the whole point of the adaptive step.
        assertEquals(30.0, ladder[1] - ladder[0], 1e-9)
        assertEquals(120.0, ladder[ladder.size - 1] - ladder[ladder.size - 2], 1e-9)
    }

    @Test
    fun `a shorter range never sees further than it was asked to`() {
        val wallDistance = 20_000.0
        val wallLatitude = observer.latitude + metresToLatitudeDegrees(wallDistance)
        val grid = FunctionGrid { lat, _ -> if (lat >= wallLatitude) 1_000.0 else 0.0 }

        val short = HorizonProfileCalculator.compute(
            grid = grid,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 10_000.0,
        )
        val long = HorizonProfileCalculator.compute(
            grid = grid,
            observer = observer,
            eyeHeightMeters = eyeHeight,
            maxRangeMeters = 30_000.0,
        )

        val dip = HorizonProfileCalculator.geometricHorizonDipDegrees(eyeElevation)
        assertEquals("a 10 km range must not find a wall at 20 km", dip, short.elevationAt(0.0), 0.001)
        assertTrue("a 30 km range must find it", long.elevationAt(0.0) > 2.0)
    }
}

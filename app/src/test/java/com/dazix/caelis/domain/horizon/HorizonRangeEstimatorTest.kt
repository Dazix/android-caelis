package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search radius decides how many 26 MB tiles get downloaded, so guessing it high is expensive and
 * guessing it low is wrong. These hold the estimate to the geometry it claims to be.
 */
class HorizonRangeEstimatorTest {

    private val observer = GeoLocation(50.0, 14.0)
    private val eyeHeight = 1.6

    private class FunctionGrid(private val f: (Double, Double) -> Double?) : ElevationGrid {
        override fun elevationAt(latitude: Double, longitude: Double): Double? = f(latitude, longitude)
    }

    private fun suggest(
        grid: ElevationGrid,
        probeRangeMeters: Double = 40_000.0,
        maxRangeMeters: Double = 100_000.0,
    ) = HorizonRangeEstimator.suggestRangeMeters(
        grid = grid,
        observer = observer,
        eyeHeightMeters = eyeHeight,
        probeRangeMeters = probeRangeMeters,
        maxRangeMeters = maxRangeMeters,
    )

    private fun metresToDegrees(metres: Double): Double =
        metres / HorizonProfileCalculator.EARTH_RADIUS_METERS * (180.0 / Math.PI)

    @Test
    fun `the reach of a peak against a level skyline is the classic horizon distance`() {
        val radius = HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS

        assertEquals(
            sqrt(2.0 * radius * 1000.0),
            HorizonRangeEstimator.reachOf(heightAboveEye = 1000.0, skylineDegrees = 0.0),
            1.0,
        )
    }

    @Test
    fun `a skyline already above the horizon shortens the reach`() {
        val level = HorizonRangeEstimator.reachOf(1000.0, 0.0)
        val raised = HorizonRangeEstimator.reachOf(1000.0, 2.0)

        assertTrue("terrain already 2° up hides more, was $raised vs $level", raised < level * 0.5)
    }

    @Test
    fun `nothing above eye level anywhere needs no more than what is loaded`() {
        val seaLevel = FunctionGrid { _, _ -> 0.0 }

        assertEquals(20_000.0, suggest(seaLevel, probeRangeMeters = 20_000.0), 1.0)
    }

    /**
     * A plain with one distant mountain is the case the estimate exists for: the skyline is almost
     * flat, so a tall peak stays relevant a long way out and the radius has to reflect that.
     */
    @Test
    fun `a tall peak on an otherwise flat plain asks for a long radius`() {
        val peakLatitude = observer.latitude + metresToDegrees(30_000.0)
        val radiusDegrees = metresToDegrees(1_500.0)
        val grid = FunctionGrid { lat, lon ->
            val dLat = lat - peakLatitude
            val dLon = (lon - observer.longitude) * 0.64
            if (dLat * dLat + dLon * dLon <= radiusDegrees * radiusDegrees) 1_600.0 else 0.0
        }

        val suggested = suggest(grid, probeRangeMeters = 40_000.0)

        assertTrue("expected a long radius, got ${suggested / 1000} km", suggested > 60_000.0)
        assertTrue("but never more than the cap", suggested <= 100_000.0)
    }

    /**
     * In a bowl the skyline is high all the way round, so distant terrain is hidden by what is close
     * and there is nothing to gain from downloading the neighbours.
     */
    @Test
    fun `a valley wall close by keeps the radius short`() {
        // A ridge 400 m above the observer at 2 km, which puts the skyline above 11° everywhere.
        val wallDistance = metresToDegrees(2_000.0)
        val grid = FunctionGrid { lat, lon ->
            val dLat = lat - observer.latitude
            val dLon = (lon - observer.longitude) * 0.64
            if (dLat * dLat + dLon * dLon >= wallDistance * wallDistance) 400.0 else 0.0
        }

        val suggested = suggest(grid, probeRangeMeters = 20_000.0)

        assertTrue("a bowl needs nothing distant, got ${suggested / 1000} km", suggested <= 25_000.0)
    }

    /**
     * A prominent peak in one direction with a steep local skyline (like Ještěd from Liberec)
     * must not inflate the search radius in opposite directions where the horizon is flat.
     */
    @Test
    fun `an isolated prominent peak with steep local skyline does not inflate range in opposite flat directions`() {
        // Observer at (50.0, 14.0) at ground level 370 m.
        // Peak is 5 km South (lat = 50.0 - 5 km), height 1000 m (630 m above ground).
        // In the South, local skyline is ~7°. In the North, ground is flat 370 m (0° skyline).
        val peakLatitude = observer.latitude - metresToDegrees(5_000.0)
        val peakRadiusDeg = metresToDegrees(1_000.0)
        val grid = FunctionGrid { lat, lon ->
            val dLat = lat - peakLatitude
            val dLon = (lon - observer.longitude) * 0.64
            if (dLat * dLat + dLon * dLon <= peakRadiusDeg * peakRadiusDeg) 1_000.0 else 370.0
        }

        val suggested = suggest(grid, probeRangeMeters = 20_000.0, maxRangeMeters = 100_000.0)

        assertTrue(
            "azimuth-aware estimate should keep radius short for local steep peak, was ${suggested / 1000} km",
            suggested <= 25_000.0,
        )
    }

    @Test
    fun `highestNearbyPerAzimuth assigns peaks to directional sectors and applies adjacent smoothing`() {
        // Place a peak 20 km East (azimuth 90°, sector 18)
        val peakLongitude = observer.longitude + metresToDegrees(20_000.0) / 0.64
        val peakRadiusDeg = metresToDegrees(1_000.0)
        val grid = FunctionGrid { lat, lon ->
            val dLat = lat - observer.latitude
            val dLon = (lon - peakLongitude) * 0.64
            if (dLat * dLat + dLon * dLon <= peakRadiusDeg * peakRadiusDeg) 1_200.0 else 200.0
        }

        val peaks = HorizonRangeEstimator.highestNearbyPerAzimuth(
            grid = grid,
            observer = observer,
            eyeElevation = 201.6,
        )

        // Sector 18 is 90° (East). Sector 17 is 85°-90°, Sector 19 is 90°-95°.
        assertEquals(1_200.0, peaks[18], 1.0)
        assertEquals("adjacent sector 17 should be smoothed with peak", 1_200.0, peaks[17], 1.0)
        assertEquals("adjacent sector 19 should be smoothed with peak", 1_200.0, peaks[19], 1.0)

        // Sector 54 is 270° (West) - should remain at eye elevation baseline
        assertEquals(201.6, peaks[54], 1.0)
    }

    @Test
    fun `the suggestion never drops below what has already been loaded`() {
        val bowl = FunctionGrid { lat, lon ->
            val dLat = lat - observer.latitude
            val dLon = (lon - observer.longitude) * 0.64
            if (dLat * dLat + dLon * dLon >= metresToDegrees(1_000.0).let { it * it }) 900.0 else 0.0
        }

        assertTrue(suggest(bowl, probeRangeMeters = 30_000.0) >= 30_000.0)
    }

    @Test
    fun `the cap is honoured even when the terrain would justify more`() {
        val peakLatitude = observer.latitude + metresToDegrees(30_000.0)
        val radiusDegrees = metresToDegrees(2_000.0)
        val grid = FunctionGrid { lat, _ ->
            if (kotlin.math.abs(lat - peakLatitude) <= radiusDegrees) 4_000.0 else 0.0
        }

        assertEquals(60_000.0, suggest(grid, probeRangeMeters = 40_000.0, maxRangeMeters = 60_000.0), 1.0)
    }

    @Test
    fun `a grid with no data at all still returns something usable`() {
        val nothing = FunctionGrid { _, _ -> null }

        val suggested = suggest(nothing, probeRangeMeters = 20_000.0)

        assertTrue(suggested > 0.0)
        assertTrue(suggested <= 100_000.0)
    }
}

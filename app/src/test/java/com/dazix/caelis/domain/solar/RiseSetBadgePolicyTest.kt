package com.dazix.caelis.domain.solar

import com.dazix.caelis.domain.horizon.HorizonProfile
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiseSetBadgePolicyTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val zone = ZoneId.of("Europe/Prague")
    private val summerDay: LocalDate = LocalDate.of(2026, 6, 21)
    private val trajectory = SolarTrajectoryPlanner.planTrajectory(summerDay, prague, zoneId = zone)
    private val lunar = LunarTrajectoryPlanner.planTrajectory(summerDay, prague, zoneId = zone)

    private fun profile(elevationAt: (Double) -> Float) = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) {
            elevationAt(HorizonProfile.azimuthForIndex(it))
        },
        observer = prague,
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
    )

    /** A 10 degree wall across the whole western half of the sky. */
    private val westRidge = profile { azimuth -> if (azimuth in 180.0..359.99) 10f else -0.0379f }

    @Test
    fun `without terrain the badge is the almanac's`() {
        val marker = trajectory.sunsetMarker!!
        val ridgeSet = TerrainRiseSetCalculator.terrainSunset(trajectory, westRidge, zone)

        val badge = RiseSetBadgePolicy.sun(marker, ridgeSet, terrainActive = false, hiddenAllDay = false)!!

        assertEquals(marker.formattedTime, badge.formattedTime)
        assertEquals(marker.coordinates.azimuthDegrees, badge.azimuthDegrees, 0.0)
        assertEquals(marker.coordinates.apparentElevationDegrees, badge.apparentElevationDegrees, 0.0)
        assertFalse(badge.onTerrain)
    }

    @Test
    fun `with terrain the sunset badge sits where and when the sun meets the ridge`() {
        val marker = trajectory.sunsetMarker!!
        val ridgeSet = TerrainRiseSetCalculator.terrainSunset(trajectory, westRidge, zone)
        assertNotNull("fixture must set behind the ridge", ridgeSet)

        val badge = RiseSetBadgePolicy.sun(marker, ridgeSet, terrainActive = true, hiddenAllDay = false)!!

        assertTrue(badge.onTerrain)
        assertEquals(ridgeSet!!.localTime.format(DateTimeFormatter.ofPattern("HH:mm")), badge.formattedTime)
        assertNotEquals("the ridge must bring the time forward", marker.formattedTime, badge.formattedTime)
        // On the ridge, not on the flat horizon: the dot is at the wall's 10 degrees.
        assertEquals(10.0, badge.apparentElevationDegrees, 0.05)
        assertEquals(ridgeSet.coordinates.azimuthDegrees, badge.azimuthDegrees, 0.0)
    }

    @Test
    fun `with terrain the sunrise badge uses the terrain sunrise`() {
        val eastRidge = profile { azimuth -> if (azimuth in 0.0..179.99) 8f else -0.0379f }
        val ridgeRise = TerrainRiseSetCalculator.terrainSunrise(trajectory, eastRidge, zone)!!

        val badge = RiseSetBadgePolicy.sun(trajectory.sunriseMarker, ridgeRise, terrainActive = true, hiddenAllDay = false)!!

        assertTrue(badge.onTerrain)
        assertEquals(8.0, badge.apparentElevationDegrees, 0.05)
        assertTrue(
            "terrain sunrise ${badge.formattedTime} must be later than the almanac's ${trajectory.sunriseMarker!!.formattedTime}",
            badge.formattedTime > trajectory.sunriseMarker!!.formattedTime,
        )
    }

    @Test
    fun `a body the terrain hides all day gets no badge`() {
        assertNull(RiseSetBadgePolicy.sun(trajectory.sunsetMarker, null, terrainActive = true, hiddenAllDay = true))
        assertNull(RiseSetBadgePolicy.moon(lunar.moonsetMarker ?: lunar.moonriseMarker, null, terrainActive = true, hiddenAllDay = true))
    }

    @Test
    fun `terrain with no crossing keeps the almanac badge`() {
        val marker = trajectory.sunsetMarker!!
        val badge = RiseSetBadgePolicy.sun(marker, null, terrainActive = true, hiddenAllDay = false)!!

        assertFalse(badge.onTerrain)
        assertEquals(marker.formattedTime, badge.formattedTime)
    }

    @Test
    fun `no almanac event and no terrain event is no badge`() {
        assertNull(RiseSetBadgePolicy.sun(null, null, terrainActive = false, hiddenAllDay = false))
        assertNull(RiseSetBadgePolicy.sun(null, null, terrainActive = true, hiddenAllDay = false))
    }

    @Test
    fun `the moon badge follows the same rule`() {
        val marker = lunar.moonsetMarker ?: lunar.moonriseMarker
        assertNotNull("fixture day must have a moonrise or a moonset", marker)
        val ridge = profile { 6f }
        val events = TerrainRiseSetCalculator.lunarEvents(lunar, ridge, zone)
        assertTrue("a 6 degree ring must be crossed by the moon", events.isNotEmpty())
        val event = events.first()

        val almanac = RiseSetBadgePolicy.moon(marker, event, terrainActive = false, hiddenAllDay = false)!!
        val terrain = RiseSetBadgePolicy.moon(marker, event, terrainActive = true, hiddenAllDay = false)!!

        assertFalse(almanac.onTerrain)
        assertEquals(marker!!.formattedTime, almanac.formattedTime)
        assertTrue(terrain.onTerrain)
        assertEquals(6.0, terrain.apparentElevationDegrees, 0.1)
    }
}

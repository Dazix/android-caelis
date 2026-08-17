package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SolarTrajectoryPlannerTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val zone = ZoneId.of("Europe/Prague")
    private val date = LocalDate.of(2026, 8, 17)

    @Test
    fun `generates correct number of bubbles for each interval`() {
        val t15 = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.FIFTEEN_MINUTES, zone)
        assertEquals(96, t15.timeBubbles.size) // 1440 / 15 = 96

        val t30 = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.THIRTY_MINUTES, zone)
        assertEquals(48, t30.timeBubbles.size) // 1440 / 30 = 48

        val t60 = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.ONE_HOUR, zone)
        assertEquals(24, t60.timeBubbles.size) // 1440 / 60 = 24

        val t120 = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.TWO_HOURS, zone)
        assertEquals(12, t120.timeBubbles.size) // 1440 / 120 = 12
    }

    @Test
    fun `verifies continuous trajectory path sampling`() {
        val traj = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.ONE_HOUR, zone, samplingStepMinutes = 2)
        assertEquals(720, traj.pathPoints.size) // 1440 / 2 = 720 points

        // Check that points transition across elevation smoothly
        val aboveHorizonPoints = traj.pathPoints.filter { it.isAboveHorizon }
        assertTrue("Expected between 400 and 500 daytime points in August in Prague, got ${aboveHorizonPoints.size}", aboveHorizonPoints.size in 400..500)
    }

    @Test
    fun `verifies sunrise and sunset markers exist for Prague in August`() {
        val traj = SolarTrajectoryPlanner.planTrajectory(date, prague, BubbleInterval.ONE_HOUR, zone)

        assertNotNull(traj.sunriseMarker)
        assertEquals(SolarMarkerType.SUNRISE, traj.sunriseMarker?.type)
        assertTrue(traj.sunriseMarker?.formattedTime?.matches(Regex("\\d{2}:\\d{2}")) == true)

        assertNotNull(traj.sunsetMarker)
        assertEquals(SolarMarkerType.SUNSET, traj.sunsetMarker?.type)
        assertTrue(traj.sunsetMarker?.formattedTime?.matches(Regex("\\d{2}:\\d{2}")) == true)

        assertEquals(SolarMarkerType.SOLAR_NOON, traj.solarNoonMarker.type)
    }
}

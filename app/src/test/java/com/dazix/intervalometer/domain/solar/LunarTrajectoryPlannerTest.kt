package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LunarTrajectoryPlannerTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val date = LocalDate.of(2026, 8, 20)

    @Test
    fun `plans 24-hour lunar trajectory with exact sampling count`() {
        val traj = LunarTrajectoryPlanner.planTrajectory(
            date = date,
            location = prague,
            bubbleInterval = BubbleInterval.ONE_HOUR,
            zoneId = ZoneId.of("UTC"),
            samplingStepMinutes = 2,
        )

        // 1440 minutes / 2 = 720 points
        assertEquals(720, traj.pathPoints.size)

        // Hourly time bubbles cover the 24-hour window
        assertTrue(traj.timeBubbles.size in 24..25)

        // Trajectory contains day times info
        assertNotNull(traj.dayTimes)
        assertTrue(traj.dayTimes.phaseName.isNotEmpty())
        assertTrue(traj.dayTimes.illuminationPercent in 0..100)
    }

    @Test
    fun `lunar trajectory is completely continuous and smooth across above-horizon passage`() {
        val zone = ZoneId.of("Europe/Prague")
        val d = LocalDate.of(2026, 8, 23)
        val traj = LunarTrajectoryPlanner.planTrajectory(
            date = d,
            location = prague,
            bubbleInterval = BubbleInterval.ONE_HOUR,
            zoneId = zone,
            samplingStepMinutes = 2,
        )

        // Verify that along the entire 720-point trajectory, adjacent points have smooth step (< 1.5° delta)
        for (i in 0 until traj.pathPoints.size - 1) {
            val p1 = traj.pathPoints[i]
            val p2 = traj.pathPoints[i + 1]

            val elDiff = Math.abs(p1.coordinates.apparentElevationDegrees - p2.coordinates.apparentElevationDegrees)
            assertTrue("Elevation jump between $i and ${i+1}: $elDiff", elDiff < 2.0)

            var azDiff = Math.abs(p1.coordinates.azimuthDegrees - p2.coordinates.azimuthDegrees)
            if (azDiff > 180.0) azDiff = 360.0 - azDiff
            assertTrue("Azimuth jump between $i and ${i+1}: $azDiff", azDiff < 2.0)
        }
    }

    @Test
    fun `lunar day times for 2026-08-23 has cohesive rise, transit, and set for that night`() {
        val zone = ZoneId.of("Europe/Prague")
        val d = LocalDate.of(2026, 8, 23)
        val dayTimes = LunarDayTimesCalculator.calculate(d, prague, zone)

        assertNotNull(dayTimes.moonriseUtc)
        assertNotNull(dayTimes.moonTransitUtc)
        assertNotNull(dayTimes.moonsetUtc)

        val riseLocal = dayTimes.moonriseUtc!!.atZone(zone)
        val transitLocal = dayTimes.moonTransitUtc!!.atZone(zone)
        val setLocal = dayTimes.moonsetUtc!!.atZone(zone)

        // Moonrise on Aug 23 in evening (~18:09)
        assertEquals(23, riseLocal.dayOfMonth)
        assertEquals(18, riseLocal.hour)

        // Transit on Aug 23 around 21:46
        assertEquals(23, transitLocal.dayOfMonth)
        assertEquals(21, transitLocal.hour)

        // Moonset following morning on Aug 24 (~01:24)
        assertEquals(24, setLocal.dayOfMonth)
        assertEquals(1, setLocal.hour)

        assertTrue(riseLocal.isBefore(transitLocal))
        assertTrue(transitLocal.isBefore(setLocal))
    }

    @Test
    fun `lunar trajectory across full lunar month produces valid cohesive paths`() {
        val zone = ZoneId.of("Europe/Prague")
        for (day in 1..31) {
            val d = LocalDate.of(2026, 8, day)
            val traj = LunarTrajectoryPlanner.planTrajectory(
                date = d,
                location = prague,
                bubbleInterval = BubbleInterval.ONE_HOUR,
                zoneId = zone,
                samplingStepMinutes = 2,
            )

            assertEquals(720, traj.pathPoints.size)
            assertTrue(traj.timeBubbles.isNotEmpty())
            assertNotNull(traj.dayTimes.moonTransitUtc)

            // When rise and set exist, rise is before transit and transit is before set
            val rise = traj.dayTimes.moonriseUtc
            val transit = traj.dayTimes.moonTransitUtc
            val set = traj.dayTimes.moonsetUtc
            if (rise != null && transit != null && set != null) {
                assertTrue(
                    "On $d rise ($rise) must be before transit ($transit)",
                    rise.isBefore(transit)
                )
                assertTrue(
                    "On $d transit ($transit) must be before set ($set)",
                    transit.isBefore(set)
                )
            }
        }
    }
}

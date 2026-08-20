package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

        // 1440 / 60 = 24 hourly time bubbles
        assertEquals(24, traj.timeBubbles.size)

        // Trajectory contains day times info
        assertNotNull(traj.dayTimes)
        assertTrue(traj.dayTimes.phaseName.isNotEmpty())
        assertTrue(traj.dayTimes.illuminationPercent in 0..100)
    }
}

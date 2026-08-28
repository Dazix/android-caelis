package com.dazix.caelis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPlannerTest {

    private fun plannerOf(stop: StopConditions) = SessionPlanner(
        SessionConfig(
            mode = TriggerMode.PeriodicTap(null),
            intervalMs = 1_000L,
            stop = stop,
        )
    )

    @Test
    fun `no stop conditions never stops`() {
        val planner = plannerOf(StopConditions())
        assertNull(planner.stopReasonFor(10_000, 999_999_999L, 1, 0L))
    }

    @Test
    fun `max shots reached completes the session`() {
        val planner = plannerOf(StopConditions(maxShots = 5))
        assertNull(planner.stopReasonFor(4, 0L, null, null))
        assertEquals(StopReason.COMPLETED, planner.stopReasonFor(5, 0L, null, null))
        assertEquals(StopReason.COMPLETED, planner.stopReasonFor(6, 0L, null, null))
    }

    @Test
    fun `non positive max shots means unlimited`() {
        val planner = plannerOf(StopConditions(maxShots = 0))
        assertNull(planner.stopReasonFor(0, 0L, null, null))
        assertNull(planner.stopReasonFor(120, 0L, null, null))
    }

    @Test
    fun `max duration reached stops with max duration`() {
        val planner = plannerOf(StopConditions(maxDurationMs = 60_000L))
        assertNull(planner.stopReasonFor(0, 59_999L, null, null))
        assertEquals(StopReason.MAX_DURATION, planner.stopReasonFor(0, 60_000L, null, null))
        assertEquals(StopReason.MAX_DURATION, planner.stopReasonFor(0, 60_001L, null, null))
    }

    @Test
    fun `non positive max duration means unlimited`() {
        val planner = plannerOf(StopConditions(maxDurationMs = 0L))
        assertNull(planner.stopReasonFor(0, 5_000_000L, null, null))
    }

    @Test
    fun `battery strictly below the threshold stops with low battery`() {
        val planner = plannerOf(StopConditions(minBatteryPercent = 20))
        assertNull(planner.stopReasonFor(0, 0L, 21, null))
        assertNull(planner.stopReasonFor(0, 0L, 20, null))
        assertEquals(StopReason.LOW_BATTERY, planner.stopReasonFor(0, 0L, 19, null))
    }

    @Test
    fun `free bytes strictly below the threshold stops with low storage`() {
        val planner = plannerOf(StopConditions(minFreeBytes = 1_000L))
        assertNull(planner.stopReasonFor(0, 0L, null, 1_001L))
        assertNull(planner.stopReasonFor(0, 0L, null, 1_000L))
        assertEquals(StopReason.LOW_STORAGE, planner.stopReasonFor(0, 0L, null, 999L))
    }

    @Test
    fun `unknown battery never stops even with a threshold set`() {
        val planner = plannerOf(StopConditions(minBatteryPercent = 90))
        assertNull(planner.stopReasonFor(0, 0L, null, null))
    }

    @Test
    fun `unknown free space never stops even with a threshold set`() {
        val planner = plannerOf(StopConditions(minFreeBytes = Long.MAX_VALUE))
        assertNull(planner.stopReasonFor(0, 0L, 100, null))
    }

    @Test
    fun `completed wins over every other reason`() {
        val planner = plannerOf(
            StopConditions(
                maxShots = 3,
                maxDurationMs = 1_000L,
                minBatteryPercent = 50,
                minFreeBytes = 1_000L,
            )
        )
        assertEquals(StopReason.COMPLETED, planner.stopReasonFor(3, 90_000L, 5, 0L))
    }

    @Test
    fun `max duration wins over the sensor thresholds`() {
        val planner = plannerOf(
            StopConditions(
                maxShots = 100,
                maxDurationMs = 1_000L,
                minBatteryPercent = 50,
                minFreeBytes = 1_000L,
            )
        )
        assertEquals(StopReason.MAX_DURATION, planner.stopReasonFor(3, 90_000L, 5, 0L))
    }

    @Test
    fun `low battery wins over low storage`() {
        val planner = plannerOf(StopConditions(minBatteryPercent = 50, minFreeBytes = 1_000L))
        assertEquals(StopReason.LOW_BATTERY, planner.stopReasonFor(3, 90_000L, 5, 0L))
    }

    @Test
    fun `low storage applies when battery is healthy`() {
        val planner = plannerOf(StopConditions(minBatteryPercent = 50, minFreeBytes = 1_000L))
        assertEquals(StopReason.LOW_STORAGE, planner.stopReasonFor(3, 90_000L, 80, 0L))
    }

    @Test
    fun `planner exposes the config it was built from`() {
        val stop = StopConditions(maxShots = 7)
        assertEquals(stop, plannerOf(stop).config.stop)
    }
}

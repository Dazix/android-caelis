package com.dazix.caelis.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalScheduleTest {

    private fun scheduleOf(
        intervalMs: Long = 1_000L,
        startDelayMs: Long = 0L,
        ramp: RampSpec? = null,
        minIntervalFloorMs: Long = 0L,
    ) = IntervalSchedule(
        SessionConfig(
            mode = TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            intervalMs = intervalMs,
            startDelayMs = startDelayMs,
            ramp = ramp,
            minIntervalFloorMs = minIntervalFloorMs,
        )
    )

    private fun assertNear(expected: Long, actual: Long, tolerance: Long = 1L) {
        assertTrue("expected $expected +/- $tolerance but was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun `no ramp yields a constant interval regardless of index or elapsed`() {
        val schedule = scheduleOf(intervalMs = 2_000L)
        assertEquals(2_000L, schedule.intervalForShot(0, 0L))
        assertEquals(2_000L, schedule.intervalForShot(7, 123_456L))
    }

    @Test
    fun `no ramp series is start plus delay plus index times interval`() {
        val schedule = scheduleOf(intervalMs = 2_000L, startDelayMs = 500L)
        assertEquals(10_500L, schedule.targetElapsedFor(0, 10_000L))
        assertEquals(12_500L, schedule.targetElapsedFor(1, 10_000L))
        assertEquals(16_500L, schedule.targetElapsedFor(3, 10_000L))
    }

    @Test
    fun `negative index collapses to the first shot`() {
        val schedule = scheduleOf(intervalMs = 2_000L, startDelayMs = 500L)
        assertEquals(schedule.targetElapsedFor(0, 10_000L), schedule.targetElapsedFor(-4, 10_000L))
    }

    @Test
    fun `linear shot index ramp hits both endpoints and the midpoint`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 3_000L, RampDomain.SHOT_INDEX, overShots = 10),
        )
        assertEquals(1_000L, schedule.intervalForShot(0, 0L))
        assertEquals(2_000L, schedule.intervalForShot(5, 0L))
        assertEquals(3_000L, schedule.intervalForShot(10, 0L))
    }

    @Test
    fun `linear ramp clamps past the ramp length`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 3_000L, RampDomain.SHOT_INDEX, overShots = 10),
        )
        assertEquals(3_000L, schedule.intervalForShot(11, 0L))
        assertEquals(3_000L, schedule.intervalForShot(5_000, 0L))
    }

    @Test
    fun `exponential shot index ramp is geometric between the endpoints`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 4_000L, RampDomain.SHOT_INDEX, overShots = 10),
        )
        assertNear(1_000L, schedule.intervalForShot(0, 0L))
        assertNear(2_000L, schedule.intervalForShot(5, 0L))
        assertNear(4_000L, schedule.intervalForShot(10, 0L))
    }

    @Test
    fun `exponential ramp clamps past the ramp length`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 8_000L, RampDomain.SHOT_INDEX, overShots = 3),
        )
        assertNear(2_000L, schedule.intervalForShot(1, 0L))
        assertNear(4_000L, schedule.intervalForShot(2, 0L))
        assertNear(8_000L, schedule.intervalForShot(3, 0L))
        assertNear(8_000L, schedule.intervalForShot(99, 0L))
    }

    @Test
    fun `exponential falls back to linear when an endpoint is not positive`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 0L, RampDomain.SHOT_INDEX, overShots = 10),
        )
        assertEquals(1_000L, schedule.intervalForShot(0, 0L))
        assertEquals(500L, schedule.intervalForShot(5, 0L))
        assertEquals(1L, schedule.intervalForShot(10, 0L))
    }

    @Test
    fun `missing ramp length degrades to an immediate jump to the end interval`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 3_000L, RampDomain.SHOT_INDEX, overShots = null),
        )
        assertEquals(1_000L, schedule.intervalForShot(0, 0L))
        assertEquals(3_000L, schedule.intervalForShot(1, 0L))
    }

    @Test
    fun `min interval floor raises a too small interval`() {
        val schedule = scheduleOf(intervalMs = 100L, minIntervalFloorMs = 500L)
        assertEquals(500L, schedule.intervalForShot(0, 0L))
        assertEquals(500L, schedule.targetElapsedFor(1, 0L))
    }

    @Test
    fun `min interval floor clamps a descending ramp`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 100L, RampDomain.SHOT_INDEX, overShots = 10),
            minIntervalFloorMs = 400L,
        )
        assertEquals(1_000L, schedule.intervalForShot(0, 0L))
        assertEquals(550L, schedule.intervalForShot(5, 0L))
        assertEquals(400L, schedule.intervalForShot(10, 0L))
    }

    @Test
    fun `zero interval with no floor still yields at least one millisecond`() {
        assertEquals(1L, scheduleOf(intervalMs = 0L).intervalForShot(0, 0L))
    }

    @Test
    fun `ramped series is the exact sum of its intervals`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            startDelayMs = 2_000L,
            ramp = RampSpec(RampCurve.LINEAR, 2_000L, RampDomain.SHOT_INDEX, overShots = 4),
        )
        val start = 50_000L
        // intervals for shots 0..4 are 1000, 1250, 1500, 1750, 2000
        assertEquals(52_000L, schedule.targetElapsedFor(0, start))
        assertEquals(53_000L, schedule.targetElapsedFor(1, start))
        assertEquals(54_250L, schedule.targetElapsedFor(2, start))
        assertEquals(55_750L, schedule.targetElapsedFor(3, start))
        assertEquals(57_500L, schedule.targetElapsedFor(4, start))
        assertEquals(59_500L, schedule.targetElapsedFor(5, start))
    }

    @Test
    fun `consecutive targets differ by exactly the scheduled interval`() {
        val schedule = scheduleOf(
            intervalMs = 900L,
            startDelayMs = 1_500L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 5_400L, RampDomain.SHOT_INDEX, overShots = 8),
        )
        val start = 7_000L
        val first = schedule.targetElapsedFor(0, start)
        for (index in 0 until 12) {
            val here = schedule.targetElapsedFor(index, start)
            val next = schedule.targetElapsedFor(index + 1, start)
            assertEquals(schedule.intervalForShot(index, here - first), next - here)
        }
    }

    @Test
    fun `targets are independent of when they are queried`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 4_000L, RampDomain.ELAPSED_TIME, overDurationMs = 10_000L),
        )
        val ahead = schedule.targetElapsedFor(9, 0L)
        val recomputed = (0..9).map { schedule.targetElapsedFor(it, 0L) }
        assertEquals(ahead, recomputed.last())
        assertEquals(ahead, IntervalSchedule(
            SessionConfig(
                mode = TriggerMode.Timelapse("com.google.android.GoogleCamera"),
                intervalMs = 1_000L,
                ramp = RampSpec(RampCurve.LINEAR, 4_000L, RampDomain.ELAPSED_TIME, overDurationMs = 10_000L),
            )
        ).targetElapsedFor(9, 0L))
    }

    @Test
    fun `elapsed time domain drives progress from measured elapsed`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 3_000L, RampDomain.ELAPSED_TIME, overDurationMs = 10_000L),
        )
        assertEquals(1_000L, schedule.intervalForShot(0, 0L))
        assertEquals(2_000L, schedule.intervalForShot(0, 5_000L))
        assertEquals(3_000L, schedule.intervalForShot(0, 10_000L))
        assertEquals(3_000L, schedule.intervalForShot(0, 900_000L))
    }

    @Test
    fun `elapsed time domain series uses the ideal accumulated elapsed`() {
        val schedule = scheduleOf(
            intervalMs = 1_000L,
            ramp = RampSpec(RampCurve.LINEAR, 3_000L, RampDomain.ELAPSED_TIME, overDurationMs = 10_000L),
        )
        assertEquals(0L, schedule.targetElapsedFor(0, 0L))
        assertEquals(1_000L, schedule.targetElapsedFor(1, 0L))
        assertEquals(2_200L, schedule.targetElapsedFor(2, 0L))
        assertEquals(3_640L, schedule.targetElapsedFor(3, 0L))
        assertEquals(5_368L, schedule.targetElapsedFor(4, 0L))
    }
}

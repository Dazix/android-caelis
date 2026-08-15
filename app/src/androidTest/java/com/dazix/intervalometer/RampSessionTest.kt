package com.dazix.intervalometer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.intervalometer.debug.FakeCameraTapLog
import com.dazix.intervalometer.domain.IntervalSchedule
import com.dazix.intervalometer.domain.RampCurve
import com.dazix.intervalometer.domain.RampDomain
import com.dazix.intervalometer.domain.RampSpec
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TriggerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A ramp is only real if the intervals the camera actually sees change the way the plan says.
 *
 * Both tests compare the measured gaps against [IntervalSchedule]'s own numbers for the same
 * config - the schedule is the single source of truth, so this catches an engine that ramps on a
 * different curve, ramps in the wrong direction, or ignores the ramp entirely.
 */
@RunWith(AndroidJUnit4::class)
class RampSessionTest {

    @Before
    fun setUp() {
        SessionHarness.requireServiceBound()
        SessionHarness.reset()
    }

    @After
    fun tearDown() {
        SessionHarness.stopSessionQuietly()
        SessionHarness.closeFakeCamera()
    }

    @Test
    fun linearRampStretchesEveryGapByTheSameStep() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = 1_000L,
            startDelayMs = 0L,
            ramp = RampSpec(
                curve = RampCurve.LINEAR,
                endIntervalMs = 3_000L,
                domain = RampDomain.SHOT_INDEX,
                overShots = SHOTS - 1,
            ),
            stop = StopConditions(maxShots = SHOTS),
        )

        runRampAndAssert(config, "linear")
    }

    @Test
    fun exponentialRampMultipliesEveryGapByTheSameFactor() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = 800L,
            startDelayMs = 0L,
            ramp = RampSpec(
                curve = RampCurve.EXPONENTIAL,
                endIntervalMs = 6_400L,
                domain = RampDomain.SHOT_INDEX,
                overShots = SHOTS - 1,
            ),
            stop = StopConditions(maxShots = SHOTS),
        )

        runRampAndAssert(config, "exponential")
    }

    private fun runRampAndAssert(config: SessionConfig, label: String) {
        val expected = expectedGaps(config, SHOTS)
        val plannedSpan = expected.sum()

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(SHOTS, plannedSpan + SLACK_MS)
        assertEquals("$label ramp: wrong number of taps, log was $taps", SHOTS, taps.size)

        val measured = FakeCameraTapLog.gapsMs(taps)

        // 1. every gap matches the schedule's own number for that shot index
        measured.forEachIndexed { index, gap ->
            assertTrue(
                "$label ramp: gap ${index + 1} was $gap ms, schedule says ${expected[index]} ms " +
                    "(+/- $TOLERANCE_MS); measured $measured vs planned $expected",
                abs(gap - expected[index]) <= TOLERANCE_MS,
            )
        }

        // 2. the gaps really move in one direction; a flat or oscillating series is not a ramp
        measured.reduce { previous, gap ->
            assertTrue(
                "$label ramp: gaps are not monotonically increasing: $measured",
                gap >= previous - MONOTONIC_SLACK_MS,
            )
            gap
        }
        assertTrue(
            "$label ramp: the series barely changed - first ${measured.first()} ms, " +
                "last ${measured.last()} ms",
            measured.last() - measured.first() >= MIN_TOTAL_CHANGE_MS,
        )
    }

    /** `IntervalSchedule`'s ideal gap between shot i and shot i+1, taken from absolute offsets. */
    private fun expectedGaps(config: SessionConfig, shots: Int): List<Long> {
        val schedule = IntervalSchedule(config)
        return (0 until shots - 1).map { index ->
            schedule.targetElapsedFor(index + 1, 0L) - schedule.targetElapsedFor(index, 0L)
        }
    }

    private companion object {
        const val SHOTS = 6
        const val TOLERANCE_MS = 250L

        /** A gap may sit slightly below its predecessor from jitter alone, but no further. */
        const val MONOTONIC_SLACK_MS = 120L
        const val MIN_TOTAL_CHANGE_MS = 800L
        const val SLACK_MS = 12_000L
    }
}

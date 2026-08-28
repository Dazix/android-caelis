package com.dazix.caelis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.debug.FakeCameraTapLog
import com.dazix.caelis.domain.IntervalSchedule
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Measures what actually reached the (fake) camera: how many taps, how far each gap sat from the
 * configured interval, whether error accumulates over a series, and whether the coordinate
 * fallback fires when there is no findable node.
 *
 * Timing is read from the tap log the fake camera writes, never from the service's own bookkeeping,
 * so the assertions cannot pass just because the scheduler believes it was on time.
 *
 * The shared plumbing lives in [SessionHarness]; the accessibility binding it depends on is set up by
 * [AccessibilityBinding].
 */
@RunWith(AndroidJUnit4::class)
class TapAccuracyTest {

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
    fun handlerPathGapsStayWithinToleranceOfConfiguredInterval() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = SHOTS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(SHOTS, INTERVAL_MS * SHOTS + SLACK_MS)

        assertEquals("wrong number of taps reached the camera: $taps", SHOTS, taps.size)
        val gaps = FakeCameraTapLog.gapsMs(taps)
        gaps.forEachIndexed { index, gap ->
            assertTrue(
                "gap ${index + 1} was $gap ms, expected $INTERVAL_MS ms +/- $GAP_TOLERANCE_MS " +
                    "(all gaps: $gaps)",
                abs(gap - INTERVAL_MS) <= GAP_TOLERANCE_MS,
            )
        }
    }

    /**
     * The scheduler derives every shot time from the session start instant, so a single late shot
     * must not shift the ones after it. Measured over the whole series: the first-to-last span is
     * compared against [IntervalSchedule]'s own first-to-last span.
     *
     * The budget is a FIXED [DRIFT_BUDGET_MS], deliberately not `shots * tolerance` - that is the
     * whole property under test. It is set to the engine's own re-anchor threshold
     * (`SessionEngine.LATE_TOLERANCE_MS`): a shot later than that shifts the anchor and the span
     * legitimately grows once, anything smaller must be absorbed without moving the series.
     */
    @Test
    fun noDriftAccumulatesAcrossTheSeries() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = DRIFT_SHOTS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(DRIFT_SHOTS, INTERVAL_MS * DRIFT_SHOTS + SLACK_MS)
        assertEquals("wrong number of taps reached the camera: $taps", DRIFT_SHOTS, taps.size)

        val schedule = IntervalSchedule(config)
        val expectedSpan = schedule.targetElapsedFor(DRIFT_SHOTS - 1, 0L) -
            schedule.targetElapsedFor(0, 0L)
        val measuredSpan = taps.last().elapsedRealtimeMs - taps.first().elapsedRealtimeMs

        assertTrue(
            "series span drifted: measured $measuredSpan ms vs scheduled $expectedSpan ms " +
                "over ${DRIFT_SHOTS - 1} gaps (fixed budget $DRIFT_BUDGET_MS ms, gaps: " +
                "${FakeCameraTapLog.gapsMs(taps)})",
            abs(measuredSpan - expectedSpan) <= DRIFT_BUDGET_MS,
        )
    }

    /**
     * No id, no content description, nothing important for accessibility in the window: the node
     * search must come up empty and the session must still land its taps on the configured
     * [TapTarget] coordinates.
     */
    @Test
    fun coordinateFallbackTapsWhenNoNodeIsFindable() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = FALLBACK_INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = FALLBACK_SHOTS),
            target = TapTarget(xFraction = 0.5f, yFraction = 0.85f),
        )

        SessionHarness.launchFakeCamera(unlabeled = true)
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(
            FALLBACK_SHOTS,
            FALLBACK_INTERVAL_MS * FALLBACK_SHOTS + SLACK_MS,
        )

        assertEquals("coordinate fallback did not land every tap: $taps", FALLBACK_SHOTS, taps.size)
        val sources = taps.map { it.source }.distinct()
        assertEquals(
            "taps should have arrived on the unlabeled surface only, saw $sources",
            listOf(FakeCameraTapLog.SOURCE_COORDINATE),
            sources,
        )
    }

    private companion object {
        /** Short enough that the engine schedules with a Handler rather than an alarm. */
        const val INTERVAL_MS = 1_200L
        const val SHOTS = 6
        const val DRIFT_SHOTS = 8
        const val FALLBACK_INTERVAL_MS = 1_500L
        const val FALLBACK_SHOTS = 4
        const val GAP_TOLERANCE_MS = 150L

        /** Fixed, independent of shot count: matches `SessionEngine.LATE_TOLERANCE_MS`. */
        const val DRIFT_BUDGET_MS = 250L

        /** Head room for activity launch, service wake-up and the trailing stop bookkeeping. */
        const val SLACK_MS = 10_000L
    }
}

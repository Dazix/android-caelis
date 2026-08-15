package com.dazix.intervalometer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.intervalometer.debug.FakeCameraTapLog
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import com.dazix.intervalometer.service.CameraProfiles
import com.dazix.intervalometer.service.ShutterDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A periodic tap is a metronome for whatever the user aims at, not a camera driver, and it used to be
 * clamped by camera arithmetic: [TriggerMode.PeriodicTap] ran through a camera profile, so a 300 ms
 * cadence silently became the profile's interval floor — 970 ms on the generic profile, 2270 ms on the
 * Pixel one (`captureLatencyMs` 2200 + one 70 ms tap). `CameraProfiles.passthrough` and
 * `CameraProfiles.effectiveFloorMs` are the fix; the unit tests pin the arithmetic, and this pins what
 * the device actually does with it.
 *
 * Measured from the tap log the fake camera writes, so the assertion is about taps that really landed
 * in a window rather than about what the scheduler intended.
 */
@RunWith(AndroidJUnit4::class)
class PeriodicTapCadenceTest {

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
    fun periodicTapKeepsAnIntervalWellUnderEveryCameraProfileFloor() {
        val config = SessionConfig(
            mode = TriggerMode.PeriodicTap(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = SHOTS),
            // PeriodicTap never touches the node tree: the calibrated point is the whole truth, so the
            // unlabeled full-screen surface is what has to receive every tap.
            target = TapTarget(xFraction = 0.5f, yFraction = 0.5f),
        )

        SessionHarness.launchFakeCamera(unlabeled = true)
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(SHOTS, INTERVAL_MS * SHOTS + SLACK_MS)

        assertEquals("wrong number of taps reached the surface: $taps", SHOTS, taps.size)
        assertEquals(
            "a periodic tap must be delivered as a dispatched touch at the calibrated point",
            listOf(FakeCameraTapLog.SOURCE_COORDINATE),
            taps.map { it.source }.distinct(),
        )
        assertEquals(
            "a periodic tap must run on the passthrough profile; a camera profile is what re-imposes " +
                "a camera's floor",
            CameraProfiles.passthrough.id,
            SessionHarness.activeProfile().id,
        )

        val gaps = FakeCameraTapLog.gapsMs(taps)
        gaps.forEachIndexed { index, gap ->
            assertTrue(
                "gap ${index + 1} was $gap ms, expected $INTERVAL_MS ms +/- $GAP_TOLERANCE_MS " +
                    "(all gaps: $gaps)",
                abs(gap - INTERVAL_MS) <= GAP_TOLERANCE_MS,
            )
        }

        // The defect, stated as the thing a user would notice: the whole series has to be over long
        // before a camera-floored one would even be halfway.
        val span = taps.last().elapsedRealtimeMs - taps.first().elapsedRealtimeMs
        val pixelFlooredSpan = (SHOTS - 1) * PIXEL_PROFILE_FLOOR_MS
        val genericFlooredSpan = (SHOTS - 1) * GENERIC_PROFILE_FLOOR_MS
        assertTrue(
            "$SHOTS taps at $INTERVAL_MS ms took $span ms; a Pixel-profile floor would have needed " +
                "$pixelFlooredSpan ms and the generic one $genericFlooredSpan ms, so the interval was " +
                "clamped by a camera profile (gaps: $gaps)",
            span < genericFlooredSpan,
        )
        assertTrue(
            "series span drifted: $span ms against an ideal ${(SHOTS - 1) * INTERVAL_MS} ms " +
                "(budget $SPAN_BUDGET_MS ms, gaps: $gaps)",
            span <= (SHOTS - 1) * INTERVAL_MS + SPAN_BUDGET_MS,
        )

        // And the floor really is one synthetic tap, not a camera's idea of one.
        assertEquals(
            ShutterDispatcher.TAP_DURATION_MS,
            CameraProfiles.effectiveFloorMs(config.mode, userFloorMs = 0L),
        )
    }

    private companion object {
        /** Well under both camera floors below, and under the 900 ms one the defect imposed. */
        const val INTERVAL_MS = 300L
        const val SHOTS = 10

        /** `CameraProfiles.generic`: `declaredMinIntervalMs` 900 + one 70 ms tap. */
        const val GENERIC_PROFILE_FLOOR_MS = 970L

        /** The Pixel profile: `captureLatencyMs` 2200 + one 70 ms tap. */
        const val PIXEL_PROFILE_FLOOR_MS = 2_270L

        /** Same per-gap slack `TapAccuracyTest` allows; gesture jitter does not scale with interval. */
        const val GAP_TOLERANCE_MS = 150L

        /** Fixed, not per-shot: an accumulating budget is what would hide a systematic clamp. */
        const val SPAN_BUDGET_MS = 500L

        const val SLACK_MS = 10_000L
    }
}

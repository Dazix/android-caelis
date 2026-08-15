package com.dazix.intervalometer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.intervalometer.debug.FakeCameraActivity
import com.dazix.intervalometer.debug.FakeCameraTapLog
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import com.dazix.intervalometer.service.DisplayGeometry
import com.dazix.intervalometer.service.ShutterDispatcher
import com.dazix.intervalometer.service.TapPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a tap actually lands when the user has calibrated a point.
 *
 * The defect this pins down produced perfectly green sessions with zero photos: the region
 * heuristic — the largest clickable node inside the bottom-centre third — was allowed to answer even
 * when the user had calibrated a point somewhere else, so a mode pill or a gallery thumbnail took the
 * click, reported success, and the shot counter rose over frames that were never captured.
 * `ShutterDispatcher.findShutterNode` now demotes that guess to "only if it agrees with the
 * calibration".
 *
 * Both directions of the documented resolution order are held down here, because either one alone can
 * be satisfied by breaking the other:
 *
 *  * [calibratedPointIsTappedAndNotTheBottomCentreRegion] — with nothing findable in the window, the
 *    tap must land **on the calibrated pixel**, and provably not inside
 *    [DisplayGeometry.shutterSearchRegion], which is the only place a region guess can ever produce.
 *    The coordinates are read from the engine's own [com.dazix.intervalometer.service.TapOutcome]
 *    (`matchedBy`, `x`, `y`) and compared against [DisplayGeometry]'s answer for the same target, so
 *    "it tapped something" cannot pass for "it tapped the right thing".
 *  * [aFindableShutterNodeStillWinsOverACalibratedPointElsewhere] — a calibration must not turn into a
 *    blanket override either. When the shutter *is* findable, `ACTION_CLICK` on the real node is the
 *    precise way to press it, and the calibration stays the fallback it is documented to be.
 *
 * What is deliberately **not** claimed here: the arbitration between a *present* region candidate and
 * the calibration. [FakeCameraActivity]'s unlabeled variant hides its whole node tree, so the region
 * step finds nothing to be demoted; provoking a live region candidate needs a camera stand-in that
 * exposes a clickable non-shutter node in the bottom third, which would mean changing the debug source
 * set. The two tests below cover the observable consequence (the calibrated pixel is what gets tapped,
 * and the bottom-centre region is not) rather than the branch.
 */
@RunWith(AndroidJUnit4::class)
class CalibratedTargetTest {

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
    fun calibratedPointIsTappedAndNotTheBottomCentreRegion() {
        val target = TapTarget(xFraction = CALIBRATED_X, yFraction = CALIBRATED_Y)
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = SHOTS),
            target = target,
        )

        SessionHarness.launchFakeCamera(unlabeled = true)

        val geometry = DisplayGeometry.current(SessionHarness.context)
        val expected = geometry.pointFor(target)
        val region = geometry.shutterSearchRegion()
        assertFalse(
            "the calibrated point ${expected.x},${expected.y} lies inside the bottom-centre search " +
                "region ${region.toShortString()}, so this test could not tell the calibration and " +
                "the region guess apart",
            region.contains(expected.x.toInt(), expected.y.toInt()),
        )

        SessionHarness.startSession(config)
        val taps = SessionHarness.awaitTaps(SHOTS, INTERVAL_MS * SHOTS + SLACK_MS)

        assertEquals("the calibrated point did not land every tap: $taps", SHOTS, taps.size)
        assertEquals(
            "every tap must have arrived as a dispatched touch on the unlabeled surface",
            listOf(FakeCameraTapLog.SOURCE_COORDINATE),
            taps.map { it.source }.distinct(),
        )

        assertEquals(
            "the display rotated mid-test, so the expected pixel is no longer comparable",
            geometry.rotation,
            DisplayGeometry.currentRotation(SessionHarness.context),
        )

        val outcome = SessionHarness.lastTapOutcome()
        assertNotNull("the engine recorded no tap outcome at all", outcome)
        checkNotNull(outcome)
        assertTrue("the last shutter press was not reported as fired: $outcome", outcome.fired)
        assertEquals(
            "the tap was not resolved through the calibration: $outcome",
            ShutterDispatcher.MATCHED_CALIBRATED,
            outcome.matchedBy,
        )
        assertEquals(
            "a calibrated point is pressed with a synthetic tap, not a node click: $outcome",
            TapPath.GESTURE_ON_TARGET,
            outcome.path,
        )
        assertEquals("tap x, outcome was $outcome", expected.x, outcome.x, PIXEL_TOLERANCE)
        assertEquals("tap y, outcome was $outcome", expected.y, outcome.y, PIXEL_TOLERANCE)
        assertFalse(
            "the tap landed at ${outcome.x},${outcome.y}, inside the bottom-centre region " +
                "${region.toShortString()} the heuristic guesses at, instead of on the calibrated " +
                "point ${expected.x},${expected.y}",
            region.contains(outcome.x.toInt(), outcome.y.toInt()),
        )
    }

    @Test
    fun aFindableShutterNodeStillWinsOverACalibratedPointElsewhere() {
        // The same off-target calibration as above, this time with a findable shutter in the window.
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = SHOTS),
            target = TapTarget(xFraction = CALIBRATED_X, yFraction = CALIBRATED_Y),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)
        val taps = SessionHarness.awaitTaps(SHOTS, INTERVAL_MS * SHOTS + SLACK_MS)

        assertEquals("wrong number of taps reached the camera: $taps", SHOTS, taps.size)
        assertEquals(
            "with a findable shutter node the click has to go through accessibility, not through the " +
                "calibrated coordinates",
            listOf(FakeCameraTapLog.SOURCE_NODE),
            taps.map { it.source }.distinct(),
        )

        val outcome = SessionHarness.lastTapOutcome()
        assertNotNull("the engine recorded no tap outcome at all", outcome)
        checkNotNull(outcome)
        assertTrue("the last shutter press was not reported as fired: $outcome", outcome.fired)
        assertEquals(
            "the shutter should have been pressed on its own node: $outcome",
            TapPath.NODE_CLICK,
            outcome.path,
        )
        val matchedBy = outcome.matchedBy
        assertTrue(
            "the node must have been matched by its view id, not by the calibration or the region " +
                "guess: $outcome",
            matchedBy != null &&
                matchedBy.startsWith("viewId:") &&
                matchedBy.contains(FakeCameraActivity.SHUTTER_VIEW_ID_NAME),
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_500L
        const val SHOTS = 3
        const val SLACK_MS = 10_000L

        /**
         * Deliberately in the upper-left quadrant: [DisplayGeometry.shutterSearchRegion] is the
         * bottom-centre third, so a point at these fractions cannot be produced by any region guess
         * on any display size.
         */
        const val CALIBRATED_X = 0.22f
        const val CALIBRATED_Y = 0.34f

        /** The engine clamps and rounds nothing but the screen edge; a pixel of slack is plenty. */
        const val PIXEL_TOLERANCE = 1.0f
    }
}

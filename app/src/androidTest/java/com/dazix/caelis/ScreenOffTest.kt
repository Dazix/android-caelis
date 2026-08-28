package com.dazix.caelis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.debug.FakeCameraTapLog
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TriggerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phantom-shot defect: a session whose screen goes off mid-series used to keep dispatching
 * gestures. A blank or locked screen answers "unknown package" to a window query, the engine read
 * that as close enough, every tap then landed on the keyguard, completed happily, and the shot
 * counter kept rising over photos that do not exist. `SessionEngine.isScreenLive()` and
 * `pauseForScreenOff()` are the fix; this is the on-device proof, from both sides:
 *
 *  * while the screen is off **no tap may be logged and `shotsTaken` may not advance** — the tap log
 *    is written by the fake camera, so it counts what really reached a window, not what the engine
 *    believed it did;
 *  * once the screen is live again the session must carry on **by itself**
 *    (`SessionEngine.tryAutoResume`), because a guard that needs the user to press Resume is a guard
 *    that ends every unattended night session.
 *
 * The display is global state shared with the device's human owner, so it is snapshotted in `@Before`
 * and put back in `@After` whatever happens, and [ScreenState] refuses to let the test proceed
 * against a screen that did not actually go off.
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffTest {

    private var display: ScreenState.Snapshot =
        ScreenState.Snapshot(screenOn = true, keyguardLocked = false)

    @Before
    fun setUp() {
        SessionHarness.requireServiceBound()
        SessionHarness.reset()
        display = ScreenState.snapshot()
        // Asserted rather than worked around: `scripts/wake_device.sh` runs before the suite precisely
        // because nothing here can be tapped otherwise, and a test that quietly restored a dark screen
        // would take every test after it down with it.
        assertTrue(
            "the display is not usable before the test even starts; run this through " +
                "scripts/run_instrumented.sh, which wakes the device first.\n" + ScreenState.diagnostics(),
            ScreenState.isLive,
        )
    }

    @After
    fun tearDown() {
        // The session first: stopping it while the screen is still off keeps the engine from taking a
        // shot during the restore below.
        SessionHarness.stopSessionQuietly()
        ScreenState.restore(display)
        SessionHarness.closeFakeCamera()
    }

    @Test
    fun screenOffStopsTheSeriesAndItAutoResumesWhenTheScreenComesBack() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            // Deliberately more shots than the test will let it take: the session must be stopped by
            // the screen going off, never by running out of frames.
            stop = StopConditions(maxShots = MAX_SHOTS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val before = SessionHarness.awaitTaps(TAPS_BEFORE_SLEEP, INTERVAL_MS * TAPS_BEFORE_SLEEP + SLACK_MS)
        assertTrue(
            "the series never got going, so switching the screen off would prove nothing; log was $before",
            before.size >= TAPS_BEFORE_SLEEP,
        )

        val screenOffAt = ScreenState.turnOff()

        // The engine notices at the next shot boundary, so it gets more than one interval.
        val paused = SessionHarness.awaitState(PAUSE_TIMEOUT_MS) { it == SessionState.Paused }
        assertTrue(
            "the session kept running with the screen off; snapshot was ${SessionHarness.snapshot}\n" +
                ScreenState.diagnostics(),
            paused,
        )
        assertEquals(
            "a screen-off pause has to say so, otherwise the UI cannot explain itself",
            SessionHarness.context.getString(R.string.session_warning_screen_off),
            SessionHarness.snapshot.warning,
        )
        // The pause is durable too: a process reclaimed while parked must come back parked.
        assertEquals(
            "the durable run record did not record the pause: ${SessionHarness.runState.liveRun()}",
            true,
            SessionHarness.runState.liveRun()?.progress?.paused,
        )

        // Everything in flight when the screen went off has landed by now, so these two are the
        // baseline the quiet window is measured against.
        val tapsAtPause = SessionHarness.readTaps().size
        val shotsAtPause = SessionHarness.snapshot.shotsTaken

        // The defect itself: nothing may reach a window while the screen is off.
        SessionHarness.idle(INTERVAL_MS * ASLEEP_INTERVALS)
        assertFalse(
            "the screen came back on by itself, so the quiet window below was not measured with it " +
                "off.\n" + ScreenState.diagnostics(),
            ScreenState.isLive,
        )
        // Timestamps rather than a count, so a gesture dispatched while the screen was still live and
        // merely delivered late cannot be mistaken for a phantom shot, and vice versa.
        val phantom = SessionHarness.readTaps().filter { it.elapsedRealtimeMs > screenOffAt }
        assertEquals(
            "taps reached the camera after the screen went off at $screenOffAt: $phantom",
            emptyList<FakeCameraTapLog.Tap>(),
            phantom,
        )
        assertEquals(
            "a tap was logged after the session parked itself: ${SessionHarness.readTaps()}",
            tapsAtPause,
            SessionHarness.readTaps().size,
        )
        assertEquals(
            "shotsTaken advanced while the screen was off, which is exactly how a green session " +
                "ends up with no photos",
            shotsAtPause,
            SessionHarness.snapshot.shotsTaken,
        )

        ScreenState.turnOnAndUnlock()

        val resumed = SessionHarness.awaitTaps(tapsAtPause + TAPS_AFTER_WAKE, RESUME_TIMEOUT_MS)
        assertTrue(
            "the session did not resume on its own once the screen was live again: ${resumed.size} " +
                "tap(s) logged, expected at least ${tapsAtPause + TAPS_AFTER_WAKE}; snapshot was " +
                "${SessionHarness.snapshot}",
            resumed.size >= tapsAtPause + TAPS_AFTER_WAKE,
        )
        assertEquals(
            "the resumed session should be Running; snapshot was ${SessionHarness.snapshot}",
            SessionState.Running,
            SessionHarness.snapshot.state,
        )
        val countedAfterWake = SessionHarness.awaitShots(
            shotsAtPause + TAPS_AFTER_WAKE,
            SHOT_COUNT_TIMEOUT_MS,
        )
        assertTrue(
            "shotsTaken did not carry on from $shotsAtPause after the resume: counted " +
                "$countedAfterWake, expected at least ${shotsAtPause + TAPS_AFTER_WAKE}; snapshot " +
                "was ${SessionHarness.snapshot}",
            countedAfterWake >= shotsAtPause + TAPS_AFTER_WAKE,
        )
        assertNotEquals(
            "the screen-off warning is still up on a session that is shooting again",
            SessionHarness.context.getString(R.string.session_warning_screen_off),
            SessionHarness.snapshot.warning,
        )

        // The screen only ever paused the session; nothing here may have ended it.
        assertFalse(
            "the session ended instead of resuming: ${SessionHarness.snapshot.state}",
            SessionHarness.snapshot.state is SessionState.Stopped,
        )
    }

    private companion object {
        /** Short enough to stay on the `Handler` path (`SessionEngine.HANDLER_GAP_LIMIT_MS`). */
        const val INTERVAL_MS = 1_200L

        const val MAX_SHOTS = 40
        const val TAPS_BEFORE_SLEEP = 2
        const val TAPS_AFTER_WAKE = 2

        /** How long the screen stays off while the tap log is watched for phantom shots. */
        const val ASLEEP_INTERVALS = 4

        const val PAUSE_TIMEOUT_MS = 10_000L
        const val RESUME_TIMEOUT_MS = 25_000L

        /**
         * Slack between a tap being delivered and the shot being counted. The engine counts on the
         * gesture's completion callback, so the two instants are milliseconds apart on a good day and
         * one dropped frame apart on a bad one; two seconds is far beyond either and still far below
         * anything a real regression would need.
         */
        const val SHOT_COUNT_TIMEOUT_MS = 2_000L
        const val SLACK_MS = 10_000L
    }
}

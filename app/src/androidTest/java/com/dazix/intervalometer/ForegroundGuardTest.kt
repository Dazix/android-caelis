package com.dazix.intervalometer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.SessionState
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of the "never tap into the dark" rule: never tap into somebody else's app either.
 *
 * `SessionEngine.isForeground` is deliberately strict — "cannot tell which app is in front" is not
 * permission to tap — and the cost of that strictness is a pause that has to lift itself again, from
 * either the window-state event or the guard tick. A guard that needs the user to press Resume would
 * end every unattended session the moment a notification pulled focus.
 *
 * The session carries a calibrated point, so a broken guard would dispatch a real gesture at a real
 * coordinate and count a shot for it: that is what makes `shotsTaken` standing still an assertion
 * rather than a formality. It also means a regression can land one synthetic tap on the home screen,
 * which is why `@After` puts the device back on it.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundGuardTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        SessionHarness.requireServiceBound()
        SessionHarness.reset()
    }

    @After
    fun tearDown() {
        SessionHarness.stopSessionQuietly()
        SessionHarness.closeFakeCamera()
        // The test navigated away from whatever was in front; the home screen is where a run starts.
        runCatching { device.pressHome() }
    }

    @Test
    fun sessionPausesInsteadOfTappingAnotherAppAndResumesWhenTheTargetComesBack() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            // Deliberately more frames than the test takes: only the foreground may pause this.
            stop = StopConditions(maxShots = MAX_SHOTS),
            target = TapTarget(xFraction = 0.5f, yFraction = 0.5f),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val before = SessionHarness.awaitTaps(TAPS_BEFORE_HOME, INTERVAL_MS * TAPS_BEFORE_HOME + SLACK_MS)
        assertTrue(
            "the series never got going, so leaving the app would prove nothing: $before",
            before.size >= TAPS_BEFORE_HOME,
        )

        assertTrue("could not leave the target app", device.pressHome())

        val paused = SessionHarness.awaitState(PAUSE_TIMEOUT_MS) { it == SessionState.Paused }
        assertTrue(
            "the session kept shooting with another app in front; snapshot was ${SessionHarness.snapshot}",
            paused,
        )
        assertEquals(
            "a wrong-app pause has to name the app it is waiting for",
            SessionHarness.context.getString(
                R.string.session_warning_wrong_app,
                SessionHarness.appPackage,
            ),
            SessionHarness.snapshot.warning,
        )

        val tapsAtPause = SessionHarness.readTaps().size
        val shotsAtPause = SessionHarness.snapshot.shotsTaken

        SessionHarness.idle(INTERVAL_MS * BACKGROUNDED_INTERVALS)
        assertEquals(
            "a tap reached the camera while it was not even on screen: ${SessionHarness.readTaps()}",
            tapsAtPause,
            SessionHarness.readTaps().size,
        )
        assertEquals(
            "shotsTaken advanced while another app was in front, so a gesture was dispatched into it",
            shotsAtPause,
            SessionHarness.snapshot.shotsTaken,
        )

        // Bringing the target app back has to be enough on its own.
        SessionHarness.launchFakeCamera(clearLog = false)

        val resumed = SessionHarness.awaitTaps(tapsAtPause + TAPS_AFTER_RETURN, RESUME_TIMEOUT_MS)
        assertTrue(
            "the session did not resume when the target app came back: ${resumed.size} tap(s), " +
                "expected at least ${tapsAtPause + TAPS_AFTER_RETURN}; snapshot was " +
                "${SessionHarness.snapshot}",
            resumed.size >= tapsAtPause + TAPS_AFTER_RETURN,
        )
        assertEquals(SessionState.Running, SessionHarness.snapshot.state)
        assertFalse(
            "the session ended instead of resuming: ${SessionHarness.snapshot.state}",
            SessionHarness.snapshot.state is SessionState.Stopped,
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_200L
        const val MAX_SHOTS = 40
        const val TAPS_BEFORE_HOME = 2
        const val TAPS_AFTER_RETURN = 2

        /** How long the target app stays away while the tap log is watched. */
        const val BACKGROUNDED_INTERVALS = 4

        const val PAUSE_TIMEOUT_MS = 10_000L
        const val RESUME_TIMEOUT_MS = 25_000L
        const val SLACK_MS = 10_000L
    }
}

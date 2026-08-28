package com.dazix.caelis

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.service.SessionEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stop conditions in the states where a session is alive but not shooting.
 *
 * They used to be evaluated only after a shot, so a session that never got to its first frame never
 * evaluated them at all: one parked in [SessionState.WaitingToStart] behind a long start delay, or one
 * the engine had paused itself, sat there past `maxDurationMs` — and past the battery and free-space
 * limits — indefinitely. `SessionEngine.scheduleGuardTick` is the fix, and its resolution is
 * [SessionEngine.GUARD_TICK_MS], which is where the tolerance below comes from rather than from feel.
 *
 * `AutoStopTest` covers the same two stop conditions on a session that is shooting; these are the two
 * states it cannot reach.
 */
@RunWith(AndroidJUnit4::class)
class GuardTickTest {

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
        // [aPausedSessionStillStopsWhenMaxDurationRunsOut] leaves the launcher in front; the home
        // screen is where a run starts, so that is where the device is handed back.
        runCatching { device.pressHome() }
    }

    /**
     * A start delay longer than the whole session's duration limit: the session has to end without
     * ever taking a frame, and it has to end within one guard tick of the limit.
     */
    @Test
    fun aSessionWaitingForItsStartDelayStopsWhenMaxDurationRunsOut() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = LONG_START_DELAY_MS,
            stop = StopConditions(maxDurationMs = SHORT_DURATION_MS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)
        val startedAt = SystemClock.elapsedRealtime()

        assertTrue(
            "a session with a ${LONG_START_DELAY_MS} ms start delay should be waiting, not " +
                "${SessionHarness.snapshot.state}",
            SessionHarness.snapshot.state is SessionState.WaitingToStart,
        )

        val stopped = SessionHarness.awaitStopped(SHORT_DURATION_MS + WAIT_SLACK_MS)
        val stoppedAfterMs = SystemClock.elapsedRealtime() - startedAt

        assertNotNull(
            "a session parked in its start delay never noticed maxDurationMs; snapshot was " +
                "${SessionHarness.snapshot}",
            stopped,
        )
        assertEquals(StopReason.MAX_DURATION, stopped?.reason)

        val upperBound = SHORT_DURATION_MS + SessionEngine.GUARD_TICK_MS + GRACE_MS
        assertTrue(
            "the duration stop landed after $stoppedAfterMs ms, expected between " +
                "${SHORT_DURATION_MS - GRACE_MS} and $upperBound ms for a $SHORT_DURATION_MS ms limit",
            stoppedAfterMs in (SHORT_DURATION_MS - GRACE_MS)..upperBound,
        )
        assertEquals(
            "the start delay had not even run out, so nothing may have been tapped: " +
                "${SessionHarness.readTaps()}",
            0,
            SessionHarness.readTaps().size,
        )
    }

    /**
     * The same limit against a session the engine paused itself. This is the state an unattended
     * session used to sit in for hours, warning about the wrong app while the battery limit it was
     * given went past unnoticed.
     */
    @Test
    fun aPausedSessionStillStopsWhenMaxDurationRunsOut() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxDurationMs = PAUSED_DURATION_MS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)
        val startedAt = SystemClock.elapsedRealtime()

        val before = SessionHarness.awaitTaps(1, INTERVAL_MS + WAIT_SLACK_MS)
        assertTrue("the series never got going: $before", before.isNotEmpty())

        assertTrue("could not leave the target app", device.pressHome())
        val paused = SessionHarness.awaitState(PAUSE_TIMEOUT_MS) { it == SessionState.Paused }
        assertTrue(
            "the session did not pause with another app in front; snapshot was " +
                "${SessionHarness.snapshot}",
            paused,
        )
        val tapsAtPause = SessionHarness.readTaps().size

        val stopped = SessionHarness.awaitStopped(PAUSED_DURATION_MS + WAIT_SLACK_MS)
        val stoppedAfterMs = SystemClock.elapsedRealtime() - startedAt

        assertNotNull(
            "a paused session never noticed maxDurationMs; snapshot was ${SessionHarness.snapshot}",
            stopped,
        )
        assertEquals(StopReason.MAX_DURATION, stopped?.reason)

        val upperBound = PAUSED_DURATION_MS + SessionEngine.GUARD_TICK_MS + GRACE_MS
        assertTrue(
            "the duration stop landed after $stoppedAfterMs ms, expected between " +
                "${PAUSED_DURATION_MS - GRACE_MS} and $upperBound ms for a $PAUSED_DURATION_MS ms limit",
            stoppedAfterMs in (PAUSED_DURATION_MS - GRACE_MS)..upperBound,
        )
        assertEquals(
            "a paused session may not tap: ${SessionHarness.readTaps()}",
            tapsAtPause,
            SessionHarness.readTaps().size,
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_000L

        /** Longer than [SHORT_DURATION_MS], so the session dies waiting rather than shooting. */
        const val LONG_START_DELAY_MS = 30_000L
        const val SHORT_DURATION_MS = 5_000L

        /** Long enough to get one frame in and press Home before the limit runs out. */
        const val PAUSED_DURATION_MS = 9_000L

        const val PAUSE_TIMEOUT_MS = 10_000L

        /** Room for the stop to be written into the snapshot, on top of one whole guard tick. */
        const val GRACE_MS = 2_500L

        const val WAIT_SLACK_MS = 15_000L
    }
}

package com.dazix.intervalometer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.intervalometer.data.SessionRepository
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.StopReason
import com.dazix.intervalometer.domain.TriggerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two ways a session ends on its own. Both are checked from the outside - the tap log says how
 * many frames the camera really got - and from the inside, via the snapshot the UI reads.
 */
@RunWith(AndroidJUnit4::class)
class AutoStopTest {

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

    /**
     * `maxShots` must stop on the count, not near it: after the last shot the harness keeps
     * watching for two more intervals and no extra tap may appear.
     */
    @Test
    fun maxShotsStopsExactlyOnCount() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = MAX_SHOTS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(MAX_SHOTS, INTERVAL_MS * MAX_SHOTS + SLACK_MS)
        assertEquals("expected exactly $MAX_SHOTS taps, log was $taps", MAX_SHOTS, taps.size)

        val stopped = SessionHarness.awaitStopped(STOP_GRACE_MS)
        assertNotNull(
            "session never reached Stopped; snapshot was ${SessionRepository.snapshot.value}",
            stopped,
        )
        assertEquals(
            "wrong stop reason for a shot-count stop",
            StopReason.COMPLETED,
            stopped?.reason,
        )
        assertEquals(
            "snapshot disagrees with the tap log",
            MAX_SHOTS,
            SessionRepository.snapshot.value.shotsTaken,
        )

        // Nothing may fire after the stop.
        SessionHarness.idle(INTERVAL_MS * 2)
        assertEquals(
            "a tap fired after the session stopped: ${SessionHarness.readTaps()}",
            MAX_SHOTS,
            SessionHarness.readTaps().size,
        )
    }

    /**
     * `maxDurationMs` is evaluated at shot boundaries, so the stop lands inside a window rather
     * than on the exact millisecond: no earlier than the deadline minus one interval, no later than
     * the deadline plus one interval and the bookkeeping grace.
     */
    @Test
    fun maxDurationStopsWithinTolerance() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxDurationMs = MAX_DURATION_MS),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)
        val startedAt = SystemClock.elapsedRealtime()

        val stopped = SessionHarness.awaitStopped(MAX_DURATION_MS + SLACK_MS)
        val stoppedAfterMs = SystemClock.elapsedRealtime() - startedAt

        assertNotNull(
            "session never reached Stopped; snapshot was ${SessionRepository.snapshot.value}",
            stopped,
        )
        assertEquals(
            "wrong stop reason for a duration stop",
            StopReason.MAX_DURATION,
            stopped?.reason,
        )

        val lowerBound = MAX_DURATION_MS - INTERVAL_MS
        val upperBound = MAX_DURATION_MS + INTERVAL_MS + STOP_GRACE_MS
        assertTrue(
            "duration stop landed after $stoppedAfterMs ms, expected between $lowerBound and " +
                "$upperBound ms for a ${MAX_DURATION_MS} ms limit",
            stoppedAfterMs in lowerBound..upperBound,
        )

        // A duration stop still has to have produced the frames it had time for.
        val taps = SessionHarness.readTaps()
        val minimumTaps = (MAX_DURATION_MS / INTERVAL_MS).toInt()
        assertTrue(
            "expected at least $minimumTaps taps inside $MAX_DURATION_MS ms, log was $taps",
            taps.size >= minimumTaps,
        )
        SessionHarness.idle(INTERVAL_MS * 2)
        assertEquals(
            "a tap fired after the duration stop: ${SessionHarness.readTaps()}",
            taps.size,
            SessionHarness.readTaps().size,
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val MAX_SHOTS = 4
        const val MAX_DURATION_MS = 4_500L
        const val SLACK_MS = 10_000L

        /** Room for the stop to be written into the snapshot after the last shot. */
        const val STOP_GRACE_MS = 2_500L
    }
}

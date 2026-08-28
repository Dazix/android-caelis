package com.dazix.caelis

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.debug.FakeCameraTapLog
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.service.RunPlan
import com.dazix.caelis.service.RunProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What survives the process going away mid-session, and what a rebound service does with it.
 *
 * ### Why this is not an `am force-stop` test
 * A real force-stop cannot be expressed from inside an instrumented test: the instrumentation runs in
 * the app's own process, so `adb shell am force-stop --user <id> com.dazix.caelis` — however it
 * is issued, `UiDevice.executeShellCommand` included — kills the test along with the app and the run
 * reports as a crashed instrumentation rather than as a pass or a fail. Faking it with a `Process.kill`
 * would be no better.
 *
 * So the contract is split into the two halves that *are* observable, and both are asserted on the
 * device rather than mocked:
 *
 *  1. [aRunLeftOnDiskIsPickedBackUpAtItsStoredProgress] — a run record is put on disk exactly as a
 *     killed process leaves one behind (`RunStateStore.beginRun` plus its progress, with no engine
 *     running), the accessibility service is taken off and put back — which is what the framework does
 *     once the hosting process returns — and `SessionEngine.restore` has to rebuild the series and
 *     shoot only the frames it still owed, counting on from the stored `shotsTaken`.
 *  2. [aServiceTornDownMidRunReportsTheLossAndDropsThePlan] — a run that is *not* resumable has to be
 *     written off exactly once: `ShutterAccessibilityService.reportEarlierServiceLoss` must report
 *     `StopReason.SERVICE_LOST` from the durable record after rebinding (the shared snapshot is reset in
 *     between, so the report can only have come from disk), the plan must be gone, and nothing may tap
 *     afterwards.
 *
 * The encoding underneath is round-tripped on the JVM in
 * `app/src/test/java/com/dazix/caelis/service/RunPlanCodecTest.kt`; what these two add is that
 * a real service, a real `SharedPreferences` file and a real rebind agree with it.
 */
@RunWith(AndroidJUnit4::class)
class InterruptedRunTest {

    @Before
    fun setUp() {
        SessionHarness.requireServiceBound()
        SessionHarness.reset()
    }

    @After
    fun tearDown() {
        SessionHarness.stopSessionQuietly()
        // No stored plan may outlive this class: the next binding of the service would pick it up and
        // start a phantom session inside somebody else's test.
        SessionHarness.runState.markStopped()
        SessionHarness.closeFakeCamera()
        // Whatever happened above, the service has to be left bound for the rest of the run.
        AccessibilityBinding.bound()
    }

    @Test
    fun aRunLeftOnDiskIsPickedBackUpAtItsStoredProgress() {
        // A periodic tap on purpose. `SessionEngine.restore` re-launches the target app for a
        // time-lapse, and the stand-in camera shares this app's package, so a re-launch would put
        // MainActivity in front of it — still "the right package" as far as the foreground guard is
        // concerned, and every following tap would land on the app's own UI. A periodic tap resumes
        // without touching the foreground, which keeps this test about the stored run and nothing else.
        val config = SessionConfig(
            mode = TriggerMode.PeriodicTap(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = MAX_SHOTS),
        )

        // The camera goes up first: the tap log is cleared here, before the record is written, so every
        // tap counted below belongs to the resumed run.
        SessionHarness.launchFakeCamera(unlabeled = true)

        val anchor = SystemClock.elapsedRealtime()
        SessionHarness.runState.beginRun(
            RunPlan(
                config = config,
                sessionOriginElapsed = anchor,
                // The calibration is part of the plan for a reason: a resumed run that lost it would
                // fall back on the region guess the calibration exists to override.
                calibratedTarget = CALIBRATED_TARGET,
            ),
            RunProgress(
                shotsTaken = STORED_SHOTS,
                attemptedShots = STORED_SHOTS,
                nextShotIndex = STORED_SHOTS,
                scheduleAnchorElapsed = anchor,
                firstShotElapsed = anchor,
            ),
        )
        val stored = SessionHarness.runState.liveRun()
        assertNotNull("the store would not hand the plan back, so nothing could be resumed", stored)
        assertEquals(INTERVAL_MS, stored?.plan?.config?.intervalMs)
        assertEquals(STORED_SHOTS, stored?.progress?.shotsTaken)
        assertEquals(CALIBRATED_TARGET, stored?.plan?.calibratedTarget)

        // Off and on again: the closest thing to the process coming back that a test can do.
        AccessibilityBinding.rebind()

        assertFalse(
            "a resumable run was written off instead of being picked up: ${SessionHarness.snapshot}",
            SessionHarness.snapshot.state is SessionState.Stopped,
        )

        val stopped = SessionHarness.awaitStopped(
            INTERVAL_MS * (MAX_SHOTS - STORED_SHOTS) + RESUME_SLACK_MS,
        )
        assertNotNull(
            "the resumed session never finished; snapshot was ${SessionHarness.snapshot}, " +
                "tap log ${SessionHarness.readTaps()}",
            stopped,
        )
        assertEquals(
            "the resumed run must end on the stored maxShots",
            StopReason.COMPLETED,
            stopped?.reason,
        )
        assertEquals(
            "the resumed run restarted its counter instead of carrying on from $STORED_SHOTS",
            MAX_SHOTS,
            SessionHarness.snapshot.shotsTaken,
        )

        SessionHarness.idle(INTERVAL_MS)
        val taps = SessionHarness.readTaps()
        assertEquals(
            "the resumed run must take only the ${MAX_SHOTS - STORED_SHOTS} frames it still owed, " +
                "log was $taps",
            MAX_SHOTS - STORED_SHOTS,
            taps.size,
        )
        assertEquals(
            "the resumed run did not tap the calibration that was stored with the plan",
            listOf(FakeCameraTapLog.SOURCE_COORDINATE),
            taps.map { it.source }.distinct(),
        )
    }

    @Test
    fun aServiceTornDownMidRunReportsTheLossAndDropsThePlan() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            // More frames than the test will allow: the run has to be ended by the teardown.
            stop = StopConditions(maxShots = MAX_SHOTS_LONG),
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val running = SessionHarness.awaitTaps(
            TAPS_BEFORE_TEARDOWN,
            INTERVAL_MS * TAPS_BEFORE_TEARDOWN + RESUME_SLACK_MS,
        )
        assertTrue(
            "the series never got going, so tearing the service down would prove nothing: $running",
            running.size >= TAPS_BEFORE_TEARDOWN,
        )
        assertNotNull(
            "a live run has to be on disk while the session is shooting",
            SessionHarness.runState.liveRun(),
        )
        assertEquals(INTERVAL_MS, SessionHarness.runState.liveRun()?.plan?.config?.intervalMs)
        // The tap log is written from inside the accessibility click, a moment before the engine counts
        // the shot and records it, so the record is given that moment rather than read out of the race.
        val recorded = SessionHarness.awaitTrue(PROGRESS_RECORD_TIMEOUT_MS) {
            (SessionHarness.runState.liveRun()?.progress?.shotsTaken ?: 0) >= TAPS_BEFORE_TEARDOWN
        }
        assertTrue(
            "the durable record never caught up with the ${running.size} tap(s) that landed: " +
                "${SessionHarness.runState.liveRun()?.progress}",
            recorded,
        )

        AccessibilityBinding.unbind()

        // The engine's own teardown path, recorded durably.
        val tapsAtTeardown = SessionHarness.readTaps().size
        val lostShots = SessionHarness.runState.shotsTaken
        assertTrue("no shot count survived the teardown", lostShots >= TAPS_BEFORE_TEARDOWN)
        assertEquals(
            "a mid-run teardown has to stop the session as a service loss; snapshot was " +
                "${SessionHarness.snapshot}",
            SessionState.Stopped(
                StopReason.SERVICE_LOST,
                SessionHarness.context.getString(R.string.session_stopped_service_lost),
            ),
            SessionHarness.snapshot.state,
        )
        assertNull(
            "a lost run must not stay resumable: ${SessionHarness.runState.liveRun()}",
            SessionHarness.runState.liveRun(),
        )
        assertTrue(
            "the loss has to stay on disk until it has been reported to the user",
            SessionHarness.runState.serviceLost,
        )

        // Cleared on purpose: from here on, anything the snapshot says can only have come from disk,
        // which is what makes this a test of reportEarlierServiceLoss and not of the engine's own write.
        SessionRepository.reset()
        AccessibilityBinding.enable()

        val state = SessionHarness.snapshot.state
        assertTrue(
            "the rebound service did not report the earlier loss; state was $state",
            state is SessionState.Stopped && state.reason == StopReason.SERVICE_LOST,
        )
        assertEquals(
            "the shot count of the lost run has to reach the user with it",
            lostShots,
            SessionHarness.snapshot.shotsTaken,
        )
        assertFalse(
            "the loss must be reported once and then forgotten, otherwise every later reconnect " +
                "reports it again",
            SessionHarness.runState.serviceLost,
        )

        SessionHarness.idle(INTERVAL_MS * 2)
        assertEquals(
            "a tap fired after the service was torn down: ${SessionHarness.readTaps()}",
            tapsAtTeardown,
            SessionHarness.readTaps().size,
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_500L

        /** Centre of the unlabeled full-screen surface, so a resumed tap is unmistakable in the log. */
        val CALIBRATED_TARGET = TapTarget(xFraction = 0.5f, yFraction = 0.5f)

        /** The resumed run owes `MAX_SHOTS - STORED_SHOTS` frames, and may take no others. */
        const val MAX_SHOTS = 6
        const val STORED_SHOTS = 2

        const val MAX_SHOTS_LONG = 40
        const val TAPS_BEFORE_TEARDOWN = 2

        /** Room for the engine to count a shot it has just taken and write it down. */
        const val PROGRESS_RECORD_TIMEOUT_MS = 3_000L

        /** Head room for the unbind/bind round trip on top of the frames themselves. */
        const val RESUME_SLACK_MS = 20_000L
    }
}

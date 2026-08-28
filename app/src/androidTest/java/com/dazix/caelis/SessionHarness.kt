package com.dazix.caelis

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.debug.FakeCameraActivity
import com.dazix.caelis.debug.FakeCameraTapLog
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.service.CameraProfile
import com.dazix.caelis.service.RunStateStore
import com.dazix.caelis.service.ShutterAccessibilityService
import com.dazix.caelis.service.TapOutcome
import org.junit.Assert.assertTrue

/**
 * Shared plumbing for every instrumented test in this package: they all drive their sessions through
 * here rather than naming the service API themselves.
 *
 * The service API is named here and nowhere else in the test source set:
 *  - `ShutterAccessibilityService.instance: ShutterAccessibilityService?`
 *  - `startSession(config: SessionConfig, advanced: AdvancedSettings = AdvancedSettings())`
 *  - `stopSession()` (records `StopReason.USER`)
 *  - `activeProfile(): CameraProfile` / `lastTapOutcome(): TapOutcome?`
 *  - `SessionRepository.snapshot: StateFlow<SessionSnapshot>`
 *  - `RunStateStore(context)`, the durable half of a run (see [runState])
 */
internal object SessionHarness {

    private const val POLL_MS = 50L
    private const val LAUNCH_TIMEOUT_MS = 10_000L
    private const val SETTLE_MS = 750L

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val appPackage: String get() = context.packageName

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Makes sure the accessibility service is really bound before a test starts, re-enabling it from
     * inside the instrumentation when the process restart dropped the binding (see
     * [AccessibilityBinding]). Fails the test on timeout — it never skips.
     */
    fun requireServiceBound() {
        AccessibilityBinding.bound()
    }

    private fun requireService(): ShutterAccessibilityService =
        checkNotNull(ShutterAccessibilityService.instance) {
            "accessibility service disappeared mid-test"
        }

    /**
     * Clears the tap log, the shared session snapshot and the durable run record so each test starts
     * from zero.
     *
     * The run record matters as much as the other two: a plan left on disk by a previous test is
     * exactly what [com.dazix.caelis.service.SessionEngine.restore] is built to pick back up,
     * so the next accessibility binding would start a phantom session inside somebody else's test.
     */
    fun reset() {
        stopSessionQuietly()
        runState.markStopped()
        FakeCameraTapLog.clear(context)
        SessionRepository.reset()
    }

    fun startSession(config: SessionConfig) {
        requireService().startSession(config)
    }

    /** The camera profile the live session is running with; `passthrough` for a periodic tap. */
    fun activeProfile(): CameraProfile = requireService().activeProfile()

    /** How the last shutter press was delivered and where it landed, straight from the engine. */
    fun lastTapOutcome(): TapOutcome? = requireService().lastTapOutcome()

    /**
     * The durable run record. A fresh instance every time on purpose: it is the same
     * `SharedPreferences` file the engine writes, and reading it through a new store is how a process
     * that has just started would see it.
     */
    val runState: RunStateStore get() = RunStateStore(context)

    val snapshot: SessionSnapshot get() = SessionRepository.snapshot.value

    fun stopSessionQuietly() {
        runCatching { ShutterAccessibilityService.instance?.stopSession() }
    }

    /**
     * Brings the fake camera to the front. In labeled mode it also waits for the shutter node to be
     * visible to accessibility, which proves the node-search path has something to find before the
     * session starts.
     */
    fun launchFakeCamera(unlabeled: Boolean = false, clearLog: Boolean = true) {
        val intent = Intent(context, FakeCameraActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(FakeCameraActivity.EXTRA_CLEAR, clearLog)
            .putExtra(FakeCameraActivity.EXTRA_UNLABELED, unlabeled)
        context.startActivity(intent)

        val resumed = awaitTrue(LAUNCH_TIMEOUT_MS) { FakeCameraActivity.isResumed }
        assertTrue("fake camera did not reach the foreground", resumed)

        if (!unlabeled) {
            val found = device.wait(
                Until.hasObject(
                    By.res(appPackage, FakeCameraActivity.SHUTTER_VIEW_ID_NAME)
                        .desc(FakeCameraActivity.SHUTTER_CONTENT_DESCRIPTION),
                ),
                LAUNCH_TIMEOUT_MS,
            )
            assertTrue(
                "the shutter node is not visible to accessibility, so this run would not be " +
                    "testing the node-search path",
                found == true,
            )
        }
        // Let the service settle on the new window before the first shot is scheduled.
        idle(SETTLE_MS)
    }

    fun closeFakeCamera() {
        FakeCameraActivity.finishCurrent()
    }

    fun readTaps(): List<FakeCameraTapLog.Tap> = FakeCameraTapLog.read(context)

    /**
     * Waits until at least [expected] taps are logged, then returns everything logged. Returning the
     * full list (not a truncated one) is what lets a caller notice an extra tap.
     */
    fun awaitTaps(expected: Int, timeoutMs: Long): List<FakeCameraTapLog.Tap> {
        awaitTrue(timeoutMs) { readTaps().size >= expected }
        return readTaps()
    }

    /** Waits for the session to land in [SessionState.Stopped]; null if it never does. */
    fun awaitStopped(timeoutMs: Long): SessionState.Stopped? {
        awaitTrue(timeoutMs) { SessionRepository.snapshot.value.state is SessionState.Stopped }
        return SessionRepository.snapshot.value.state as? SessionState.Stopped
    }

    /**
     * Waits until at least [expected] shots have been counted, then returns the count.
     *
     * A tap reaching the camera and the shot being *counted* are two different instants: the tap log
     * is written by the target app the moment the gesture is delivered, while the engine counts the
     * shot when the platform reports the gesture completed (or, with verification on, when that
     * resolves). Sampling `shotsTaken` straight after `awaitTaps` therefore races the engine by a few
     * milliseconds, which is a flake, not a defect — this waits for the same fact instead.
     */
    fun awaitShots(expected: Int, timeoutMs: Long): Int {
        awaitTrue(timeoutMs) { SessionRepository.snapshot.value.shotsTaken >= expected }
        return SessionRepository.snapshot.value.shotsTaken
    }

    /** Waits for the published session state to satisfy [predicate]. */
    fun awaitState(timeoutMs: Long, predicate: (SessionState) -> Boolean): Boolean =
        awaitTrue(timeoutMs) { predicate(SessionRepository.snapshot.value.state) }

    fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            idle(POLL_MS)
        }
        return condition()
    }

    fun idle(ms: Long) {
        SystemClock.sleep(ms)
    }
}

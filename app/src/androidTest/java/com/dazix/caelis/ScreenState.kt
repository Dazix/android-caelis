package com.dazix.caelis

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * Turning the display off and on again from inside a test, and proving it happened.
 *
 * [ScreenOffTest] is the only user. It lives next to the tests rather than inside [SessionHarness]
 * because it is the one helper here that changes global device state: everything it touches is
 * *global*, not per-user (see the note about `input keyevent` / `dumpsys power` in
 * `scripts/README.md`), so a test that uses it has to hand the state back in `@After`. [snapshot] and
 * [restore] are that contract.
 *
 * Liveness is read through exactly the two system services
 * [com.dazix.caelis.service.SessionEngine] itself reads — [PowerManager.isInteractive] and
 * [KeyguardManager.isKeyguardLocked] — so "the screen is off as far as the engine is concerned" is
 * asserted rather than assumed. [UiDevice.isScreenOn] alone would not do: a device showing the
 * keyguard has the screen on and is still unusable for tapping.
 *
 * Nothing here is allowed to fail quietly. Both [turnOff] and [turnOnAndUnlock] throw an
 * [AssertionError] carrying the power state if the device did not do as it was told, because a test
 * that measures "no phantom taps while the screen is off" against a screen that stayed on measures
 * nothing at all.
 */
internal object ScreenState {

    private const val POLL_MS = 200L
    private const val SETTLE_TIMEOUT_MS = 10_000L

    /** Recorded state of the display, so a test can put back exactly what it found. */
    internal data class Snapshot(val screenOn: Boolean, val keyguardLocked: Boolean)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private val powerManager: PowerManager?
        get() = instrumentation.targetContext.getSystemService(PowerManager::class.java)

    private val keyguardManager: KeyguardManager?
        get() = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)

    val isScreenOn: Boolean get() = runCatching { device.isScreenOn }.getOrDefault(false)

    val isInteractive: Boolean get() = runCatching { powerManager?.isInteractive }.getOrNull() == true

    val isKeyguardLocked: Boolean
        get() = runCatching { keyguardManager?.isKeyguardLocked }.getOrNull() ?: false

    /** What `SessionEngine.isScreenLive()` decides: interactive *and* not behind the keyguard. */
    val isLive: Boolean get() = isInteractive && !isKeyguardLocked

    fun snapshot(): Snapshot = Snapshot(screenOn = isScreenOn, keyguardLocked = isKeyguardLocked)

    /** Puts the display back the way [snapshot] found it. */
    fun restore(snapshot: Snapshot) {
        if (snapshot.screenOn) turnOnAndUnlock() else turnOff()
    }

    /**
     * Sends the device to sleep and does not return until the engine would refuse to tap.
     *
     * Returns the [SystemClock.elapsedRealtime] instant at which the screen was first seen to be
     * unusable. A tap logged *after* that instant is a phantom shot; one logged before it belongs to a
     * gesture that was dispatched while the screen was still live and merely landed late, which is
     * what makes this return value worth having instead of a tap count.
     */
    fun turnOff(): Long {
        if (isScreenOn) runCatching { device.sleep() }
        val off = await { !isLive }
        if (!off) {
            throw AssertionError(
                "the display did not go off within $SETTLE_TIMEOUT_MS ms, so a screen-off " +
                    "assertion would be measured against a live screen.\n" + diagnostics(),
            )
        }
        return SystemClock.elapsedRealtime()
    }

    /** Wakes the device, dismisses the keyguard, and does not return until tapping is possible. */
    fun turnOnAndUnlock() {
        runCatching { device.wakeUp() }
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        val live = await { isLive }
        if (!live) {
            throw AssertionError(
                "the display could not be brought back within $SETTLE_TIMEOUT_MS ms, so the device " +
                    "was left unusable for the rest of the run.\n" + diagnostics(),
            )
        }
    }

    /** The lines a human would look at first when the device refused to change state. */
    fun diagnostics(): String {
        val wakefulness = runCatching {
            device.executeShellCommand("dumpsys power").lineSequence()
                .filter { it.contains("mWakefulness") || it.contains("mHoldingDisplaySuspendBlocker") }
                .map { it.trim() }
                .joinToString("\n")
        }.getOrElse { "dumpsys power unavailable: $it" }
        return "isScreenOn=$isScreenOn isInteractive=$isInteractive " +
            "isKeyguardLocked=$isKeyguardLocked\n$wakefulness"
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }
}

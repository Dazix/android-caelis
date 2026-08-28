package com.dazix.caelis

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.dazix.caelis.service.ShutterAccessibilityService

/**
 * Gets [ShutterAccessibilityService] bound *from inside the instrumentation*, and fails the test if
 * it cannot.
 *
 * Two separate things unbind the service the moment a test run starts, and both have to be undone
 * here, in this order:
 *
 * 1. **UiAutomation suppresses other accessibility services.** Connecting a [UiAutomation] — which
 *    every `UiDevice` call does — makes the framework unbind every other accessibility service for
 *    the user unless the connection carries
 *    [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]. The symptom is precise and easy to
 *    misread: `Enabled services:{{…ShutterAccessibilityService}}` with `Bound services:{}` and an
 *    *empty* `Crashed services:{}`. No amount of re-enabling fixes it, because nothing is broken —
 *    the framework is deliberately holding our service down. So the flag is set on the
 *    [Configurator] (which is what `UiDevice` passes when it fetches its own connection) and a
 *    flagged connection is requested explicitly, which re-creates an unflagged one and lifts the
 *    suppression.
 * 2. **`am instrument` restarts the app process.** A process that dies while its accessibility
 *    service is bound can land in the framework's `Crashed services` set, and that is only forgotten
 *    when `enabled_accessibility_services` actually *changes* — writing the value it already holds is
 *    a no-op. So if the service is still not bound after the suppression is lifted, the list is
 *    cycled: our component removed, then put back.
 *
 * Failing is deliberate: a skipped run says nothing about the engine, so "the service never bound"
 * must show up as a red test, not as a green report with seven skips in it.
 *
 * Only our own component is ever added or removed; any accessibility service the device's human owner
 * had enabled is written back verbatim. Snapshot and restore stay the scripts' job.
 */
internal object AccessibilityBinding {

    /** Lifting the suppression is enough on its own; this is how long that alone is given. */
    private const val SUPPRESSION_LIFT_TIMEOUT_MS = 10_000L

    /** How long the framework gets to bind after the enabled list has been cycled. */
    private const val BIND_TIMEOUT_MS = 30_000L

    /** How long switching the service off gets to actually tear it down. */
    private const val UNBIND_TIMEOUT_MS = 15_000L

    private const val CYCLE_SETTLE_MS = 1_500L
    private const val POLL_MS = 250L

    /** uid = userId * this + appId; the only public way to learn the user we are running as. */
    private const val PER_USER_RANGE = 100_000

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private val appPackage: String get() = instrumentation.targetContext.packageName

    private val userId: Int get() = Process.myUid() / PER_USER_RANGE

    private val component: String
        get() = ComponentName(
            appPackage,
            ShutterAccessibilityService::class.java.name,
        ).flattenToString()

    /**
     * The bound service. Returns immediately when it is already connected (the usual case for every
     * test after the first).
     */
    fun bound(): ShutterAccessibilityService {
        // First, and on every call: any unflagged UiAutomation connection created meanwhile has
        // suppressed our service again.
        allowOtherAccessibilityServices()
        ShutterAccessibilityService.instance?.let { return it }

        wakeScreen()
        awaitInstance(SUPPRESSION_LIFT_TIMEOUT_MS)?.let { return it }

        cycleService()
        awaitInstance(BIND_TIMEOUT_MS)?.let { return it }

        throw AssertionError(
            "ShutterAccessibilityService never bound for user $userId within " +
                "${SUPPRESSION_LIFT_TIMEOUT_MS + BIND_TIMEOUT_MS} ms, after both lifting the " +
                "UiAutomation suppression and cycling $component. No session can be started, so this " +
                "run cannot say anything about the engine.\n" + diagnostics(),
        )
    }

    /**
     * Switches the service off the way the user would in Settings, and does not return until it is
     * really gone.
     *
     * [ShutterAccessibilityService.instance] is cleared by the *last* statement of the service's own
     * teardown, so "the instance went away" is proof that `onUnbind`/`onDestroy` ran to completion —
     * including the mid-run [com.dazix.caelis.domain.StopReason.SERVICE_LOST] bookkeeping the
     * interrupted-run tests are about. Waiting for that is why this is not just a settings write.
     *
     * The caller is responsible for putting the service back with [enable]; [rebind] does both.
     */
    fun unbind() {
        val others = otherServices()
        shell("settings --user $userId put secure accessibility_enabled 0")
        writeEnabledServices(others)
        if (!awaitNoInstance(UNBIND_TIMEOUT_MS)) {
            throw AssertionError(
                "$component did not unbind within $UNBIND_TIMEOUT_MS ms, so nothing below would be " +
                    "testing a service teardown.\n" + diagnostics(),
            )
        }
    }

    /** Puts our component back into the enabled list and waits for the framework to bind it. */
    fun enable(): ShutterAccessibilityService {
        allowOtherAccessibilityServices()
        writeEnabledServices(otherServices() + component)
        shell("settings --user $userId put secure accessibility_enabled 1")
        return awaitInstance(BIND_TIMEOUT_MS) ?: throw AssertionError(
            "$component did not bind back within $BIND_TIMEOUT_MS ms after being switched off.\n" +
                diagnostics(),
        )
    }

    /**
     * Off and on again: the closest an in-process test can get to "the service went away and came
     * back", which is what the framework does after the hosting process has been reclaimed. A real
     * `am force-stop` would take the instrumentation down with the app, so it cannot be expressed
     * here — see `InterruptedRunTest` for what is asserted instead.
     */
    fun rebind(): ShutterAccessibilityService {
        unbind()
        return enable()
    }

    /**
     * Makes the framework stop holding down accessibility services other than UiAutomation's own.
     * Idempotent: requesting the flags the current connection already has returns it untouched.
     */
    private fun allowOtherAccessibilityServices() {
        Configurator.getInstance()
            .setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    /**
     * Removes our component, then puts it back. The removal is the point: writing the value the
     * setting already holds is a no-op for the framework and leaves a crashed service dead.
     */
    private fun cycleService() {
        val others = otherServices()
        shell("settings --user $userId put secure accessibility_enabled 0")
        writeEnabledServices(others)
        SystemClock.sleep(CYCLE_SETTLE_MS)
        writeEnabledServices(others + component)
        shell("settings --user $userId put secure accessibility_enabled 1")
    }

    private fun awaitInstance(timeoutMs: Long): ShutterAccessibilityService? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            ShutterAccessibilityService.instance?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        return ShutterAccessibilityService.instance
    }

    private fun awaitNoInstance(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ShutterAccessibilityService.instance == null) return true
            SystemClock.sleep(POLL_MS)
        }
        return ShutterAccessibilityService.instance == null
    }

    /** Every enabled accessibility service that is not ours; written back verbatim, always. */
    private fun otherServices(): List<String> =
        enabledServices().filterNot { it.substringBefore('/') == appPackage }

    private fun enabledServices(): List<String> {
        val raw = shell("settings --user $userId get secure enabled_accessibility_services").trim()
        if (raw.isEmpty() || raw == "null") return emptyList()
        return raw.split(':').filter { it.isNotBlank() }
    }

    private fun writeEnabledServices(services: List<String>) {
        // Values are passed unquoted on purpose: the shell command is tokenized on whitespace, and a
        // component list contains none.
        if (services.isEmpty()) {
            shell("settings --user $userId delete secure enabled_accessibility_services")
        } else {
            shell(
                "settings --user $userId put secure enabled_accessibility_services " +
                    services.joinToString(":"),
            )
        }
    }

    private fun wakeScreen() {
        runCatching { if (!device.isScreenOn) device.wakeUp() }
        runCatching { shell("wm dismiss-keyguard") }
    }

    /** The lines a human would look at first: the bound/enabled/crashed sets for our user. */
    private fun diagnostics(): String {
        val sets = runCatching {
            shell("dumpsys accessibility").lineSequence()
                .filter { it.contains("attributes:{id=") || it.contains("services:{") }
                .map { it.trim() }
                .joinToString("\n")
        }.getOrElse { "dumpsys accessibility unavailable: $it" }
        return "enabled_accessibility_services=${enabledServices()}\n" +
            "uiAutomationFlags=${Configurator.getInstance().uiAutomationFlags}\n$sets"
    }

    private fun shell(command: String): String = device.executeShellCommand(command)
}

package com.dazix.caelis.overlay

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.dazix.caelis.domain.TapTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user designated, in the same shape the session engine reads calibrations back in. */
data class CalibrationResult(
    /** The package the calibration was stored under, or `null` for a package-less PeriodicTap run. */
    val packageName: String?,
    /** The [android.view.Surface] rotation slot it was written to. */
    val rotation: Int,
    /** Natural-space fractions; x/y are the centre of the target. */
    val target: TapTarget,
)

/**
 * Entry point for calibration.
 *
 * Calibration used to be a translucent Activity started from the home screen, which meant the user
 * designated "the shutter button of Google Camera" while looking at a dimmed copy of *our* home
 * screen. The stored point was a blind guess — and it is the only tap path PeriodicTap has, and the
 * only fallback for camera apps whose shutter node refuses `ACTION_CLICK`.
 *
 * Now the target app is brought to the front first and the capture surface goes up as a
 * `TYPE_APPLICATION_OVERLAY` window (the same permission the session panel already needs), so the
 * user taps the shutter they can actually see.
 *
 * Usage from Compose:
 * ```
 * val started = CalibrationOverlay.start(context, setup.targetPackage)
 * if (!started) { /* send the user to the draw-over-other-apps settings page */ }
 * ```
 * The result is persisted through `SettingsStore` under package + rotation before the surface goes
 * away, and the app is brought back to the front, so a screen that re-reads its calibration on resume
 * picks it up with no result plumbing. [lastResult] is there for the package-less PeriodicTap case,
 * which has no per-package slot to be read back from.
 */
object CalibrationOverlay {

    private val activeState = MutableStateFlow(false)
    private val resultState = MutableStateFlow<CalibrationResult?>(null)

    /** True while the calibration surface is up. */
    val isActive: StateFlow<Boolean> = activeState.asStateFlow()

    /**
     * The most recent calibration of this process, newest last. Kept for `TriggerMode.PeriodicTap`
     * without a target package: there is no per-package slot to store such a target in, so the UI has
     * to fold it into the session config itself.
     */
    val lastResult: StateFlow<CalibrationResult?> = resultState.asStateFlow()

    /**
     * Brings [packageName] to the front and puts the capture surface over it.
     *
     * Returns `false` when the draw-over-other-apps permission is missing (nothing is shown; the
     * caller should route the user to the settings page) or when the surface could not be started at
     * all. A target app that refuses to launch is *not* a failure: the surface still comes up, telling
     * the user to switch to the camera themselves, because a calibration taken over a manually opened
     * camera is just as valid.
     *
     * Safe to call from any context; must be called while the app is in the foreground, which is the
     * only way the user can reach it.
     */
    fun start(context: Context, packageName: String?): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        // Launched from the caller's (foreground) context so the activity start is never a background
        // start, whether the caller is the trampoline Activity or a Compose screen.
        val launched = packageName?.let { launchTarget(context, it) } ?: false
        return runCatching {
            context.startService(CalibrationOverlayService.startIntent(context, packageName, launched))
        }.isSuccess
    }

    /** Takes the surface down without storing anything. No-op when nothing is showing. */
    fun cancel(context: Context) {
        runCatching { context.startService(CalibrationOverlayService.cancelIntent(context)) }
    }

    internal fun reportActive(active: Boolean) {
        activeState.value = active
    }

    internal fun reportResult(result: CalibrationResult) {
        resultState.value = result
    }

    /** The app's own launcher entry, or its still-image camera activity when it has no launcher one. */
    private fun launchTarget(context: Context, packageName: String): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

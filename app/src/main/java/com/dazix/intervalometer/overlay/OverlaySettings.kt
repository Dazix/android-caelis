package com.dazix.intervalometer.overlay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The two user-visible choices about how the floating panel behaves during a session. */
data class OverlayOptions(
    /**
     * Hold the display awake while the session is *running*. On by default because Pixel Camera (and
     * most camera apps) stop previewing — and therefore stop taking pictures — when the screen goes
     * off, and because `SessionEngine` refuses to dispatch a tap into a dark or locked screen at all,
     * so a run left with this off simply pauses at the display timeout.
     *
     * This is the authoritative copy of the setting;
     * [com.dazix.intervalometer.data.AdvancedSettings.keepScreenOn] mirrors it, default included.
     */
    val keepScreenOn: Boolean = true,

    /**
     * Fade the panel down to [OverlayController.DIM_ALPHA] so it stops branding what the user is
     * looking at. A touch brings it back to full opacity for a few seconds. Off by default so the
     * panel is discoverable the first time.
     */
    val dimPanel: Boolean = false,
)

/**
 * Process-wide, synchronously readable store for [OverlayOptions].
 *
 * Deliberately `SharedPreferences` and not the app's DataStore-backed `SettingsStore`: the window
 * flags have to be recomputed inside a `WindowManager` layout pass on the main thread, where a
 * suspending read is not available, and these two values are pure view state rather than part of the
 * session configuration that gets serialised into a run.
 */
object OverlaySettings {

    const val PREFS_NAME = "overlay_options"

    /** Boolean, default `true`. */
    const val KEY_KEEP_SCREEN_ON = "overlay_keep_screen_on"

    /** Boolean, default `false`. */
    const val KEY_DIM_PANEL = "overlay_dim_panel"

    /** Hot state; readable and collectable from anywhere in the process, including the UI slice. */
    fun options(context: Context): StateFlow<OverlayOptions> {
        ensureLoaded(context)
        return loaded.asStateFlow()
    }

    fun current(context: Context): OverlayOptions {
        ensureLoaded(context)
        return loaded.value
    }

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        loaded.update { it.copy(keepScreenOn = enabled) }
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun setDimPanel(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        loaded.update { it.copy(dimPanel = enabled) }
        prefs(context).edit().putBoolean(KEY_DIM_PANEL, enabled).apply()
    }

    private val loaded = MutableStateFlow(OverlayOptions())

    @Volatile
    private var readFromDisk = false

    /** Defaults are also the fallback for an unreadable preference file, so this never throws. */
    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (readFromDisk) return
        loaded.value = runCatching {
            val prefs = prefs(context)
            val defaults = OverlayOptions()
            OverlayOptions(
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
                dimPanel = prefs.getBoolean(KEY_DIM_PANEL, defaults.dimPanel),
            )
        }.getOrDefault(OverlayOptions())
        readFromDisk = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

package com.dazix.caelis.data

/**
 * Every device/camera-app-specific extra. All opt-in, all default off, so a default-constructed
 * instance behaves exactly like a build that never had any of them.
 */
data class AdvancedSettings(
    val launchCameraMode: String? = null,
    val adaptiveLongExposureWait: Boolean = false,
    val ensureSelfTimerOff: Boolean = false,
    val dismissPostCaptureUi: Boolean = false,
    /**
     * Hold the screen awake while the session is *running*.
     *
     * On by default, and the one flag here that is not "off = today's behaviour": the engine holds a
     * `PARTIAL_WAKE_LOCK`, which keeps the CPU up but lets the display time out, and a dark screen
     * makes [com.dazix.caelis.service.SessionEngine] refuse to tap (there is no way to press
     * a shutter that is not on screen) — so an unattended run with this off pauses at the display
     * timeout and shoots nothing until somebody wakes the phone. Turning it off is meaningful only
     * for a supervised run.
     *
     * ### Where this actually lives
     * The floating panel is what holds the flag on its window, and it needs the value synchronously
     * inside a `WindowManager` layout pass, so the authoritative copy is
     * [com.dazix.caelis.overlay.OverlaySettings] (SharedPreferences) and this field mirrors
     * it — including the default, which is why it is `true` here as well. `MainViewModel` writes both
     * stores and displays the panel's value; nothing reads this copy to decide behaviour.
     */
    val keepScreenOn: Boolean = true,
    /**
     * Draw the session overlay dimmed, for shooting in the dark without the overlay lighting the
     * scene or the viewfinder. Off by default: the overlay is drawn at full opacity. Mirrors
     * [com.dazix.caelis.overlay.OverlayOptions.dimPanel] exactly as [keepScreenOn] does.
     */
    val dimOverlay: Boolean = false,
)

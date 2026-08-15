package com.dazix.intervalometer.overlay

import android.content.Context
import android.os.Build

/**
 * The colours the floating panel is painted in, taken from the same source as the app's own theme.
 *
 * The app themes itself with Material 3 Expressive and dynamic colour, so its surfaces follow the
 * wallpaper. The panel cannot read that theme — it is plain `View`s in a `WindowManager` window, with
 * no Compose composition and no Material attributes on the platform theme it uses — so it resolves the
 * *same* palette from the platform's own dynamic colour roles instead of carrying a second, unrelated
 * set of hardcoded colours. Without this the app is lavender and the panel floating over it is amber.
 *
 * Always the **dark** roles, on purpose and independently of the system light/dark setting: the panel
 * floats over a camera preview. A light panel over a night sky is a lamp in the frame's reflection and
 * ruins the user's dark adaptation, which is exactly the situation a time-lapse app is used in.
 *
 * The dynamic roles used here (`system_surface_container_high_dark` and friends) exist from API 34.
 * On anything older — the app's `minSdk` is 33 — [FIXED] is used, which is the same hand-picked
 * expressive palette the Compose theme falls back to when the device has no dynamic palette.
 */
internal data class OverlayPalette(
    val surface: Int,
    val stroke: Int,
    val onSurface: Int,
    val accent: Int,
    val buttonContainer: Int,
    val onButtonContainer: Int,
    val stopContainer: Int,
    val onStopContainer: Int,
    val warning: Int,
) {

    companion object {

        /** The panel's own fallback, matching `expressiveDark` in the Compose theme. */
        private val FIXED = OverlayPalette(
            surface = 0xFF251E17.toInt(),
            stroke = 0xFF504539.toInt(),
            onSurface = 0xFFEDE0D4.toInt(),
            accent = 0xFFFFB870.toInt(),
            buttonContainer = 0xFF26456F.toInt(),
            onButtonContainer = 0xFFD7E2FF.toInt(),
            stopContainer = 0xFF73342C.toInt(),
            onStopContainer = 0xFFFFDAD5.toInt(),
            warning = 0xFFFFB4AB.toInt(),
        )

        /** Alpha the panel's own background is drawn at, so the camera preview stays readable. */
        const val SURFACE_ALPHA = 0xD9

        /** Alpha of the hairline that separates the panel from a busy preview behind it. */
        const val STROKE_ALPHA = 0x59

        fun of(context: Context): OverlayPalette {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return FIXED
            return runCatching {
                OverlayPalette(
                    surface = context.getColor(android.R.color.system_surface_container_high_dark),
                    stroke = context.getColor(android.R.color.system_outline_dark),
                    onSurface = context.getColor(android.R.color.system_on_surface_dark),
                    accent = context.getColor(android.R.color.system_primary_dark),
                    buttonContainer = context.getColor(
                        android.R.color.system_secondary_container_dark
                    ),
                    onButtonContainer = context.getColor(
                        android.R.color.system_on_secondary_container_dark
                    ),
                    stopContainer = context.getColor(
                        android.R.color.system_error_container_dark
                    ),
                    onStopContainer = context.getColor(
                        android.R.color.system_on_error_container_dark
                    ),
                    warning = context.getColor(android.R.color.system_error_dark),
                )
            }.getOrDefault(FIXED)
        }

        /** [color] with its alpha replaced, for the two translucent parts of the panel. */
        fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }
}

package com.dazix.caelis.ui

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/**
 * Material 3 Expressive: colour, shape, type and motion, switched on through the system's own entry
 * point rather than imitated.
 *
 * `MaterialExpressiveTheme` is the expressive theme: it installs the expressive shape scale (which
 * includes the extra `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` steps), the
 * expressive type scale with its `*Emphasized` styles, and — the part that cannot be reproduced from
 * outside — the [MotionScheme] that every built-in component animates on. Passing
 * `MotionScheme.expressive()` is what makes a switch, a button, a slider and an app bar in this app
 * move on the expressive springs instead of the standard ones, and [ExpressiveMotion] hands the same
 * scheme to the animations the app writes itself, so nothing on screen moves on a different clock.
 *
 * This needs androidx.compose.material3 1.5.x; the BOM's 1.4.0 ships the expressive tokens but keeps
 * the theme's motion scheme, the emphasized type styles and the expressive components out of its public
 * API. See the `material3` pin in gradle/libs.versions.toml.
 *
 * Colour comes from the wallpaper: Material You dynamic colour, light and dark, on Android 12 and
 * newer; Android 11 has none and gets a fixed amber palette.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CaelisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Wallpaper colour from Android 12, where dynamic colour exists. `minSdk` is 30, and Android 11
    // gets the hand-picked amber schemes the floating panel uses there too (FallbackColorSchemes.kt):
    // the panel is plain Views with no Compose theme to read, see [OverlayPalette].
    val scheme = remember(darkTheme, context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) expressiveDark else expressiveLight
        }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * The expressive motion scheme, as the app's own animations see it.
 *
 * Expressive motion is springs, not durations and easing curves: a spring can be interrupted and
 * redirected mid-flight, which is why a value that changes twice in a second (a countdown, a
 * selection, a shape morphing back before it arrived) stays smooth instead of restarting. The two
 * families are *spatial* — anything that moves, resizes or reshapes, which settles with a little
 * overshoot — and *effects* — colour and alpha, which must not overshoot, because colour that
 * overshoots reads as a flicker.
 *
 * These read the scheme off the theme rather than defining springs of their own, so an app animation
 * and the component beside it are the same motion.
 */
object ExpressiveMotion {

    /** Anything that moves or changes size. The default choice. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** A deliberate, weighty change: the primary action turning into its opposite. */
    @Composable
    @ReadOnlyComposable
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** Colour and alpha, which must not overshoot. */
    @Composable
    @ReadOnlyComposable
    fun <T> effects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()
}

/**
 * Styles for the numbers the app exists to show. Kept as named roles rather than as raw type steps so
 * the countdown and the shot counter cannot drift apart, and so a screen never picks `displayMedium`
 * for something that is not a live number.
 *
 * These are the *emphasized* steps of the expressive scale: emphasis there is weight and tracking, not
 * size, which is exactly what a number read at arm's length across a dark field needs.
 */
object ExpressiveType {

    /** The one number on screen that matters most right now: the countdown, or the shot count. */
    val hero: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displaySmallEmphasized

    /** The number beside the hero: planned interval, total shots. */
    val figure: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmallEmphasized

    /** The label above or below a number. */
    val figureLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLargeEmphasized
}

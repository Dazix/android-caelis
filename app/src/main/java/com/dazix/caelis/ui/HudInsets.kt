package com.dazix.caelis.ui

import android.graphics.RectF
import android.os.Build
import android.view.DisplayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dazix.caelis.domain.arframe.HudTopInset

/**
 * The shift every top-anchored HUD element of an AR screen adds to its designed top padding, so the
 * bar and the chips under it clear the camera cutout together ([HudTopInset]).
 *
 * The depth measured is that of the cutout itself, not the cutout inset: on a Pixel 10 Pro the inset
 * (and the top bounding rect) is 172 px, the height of the hidden status bar, while the punch-hole
 * ends at 127 px, and clearing the inset left a visibly needless gap above the bar. The cutout's path
 * (API 31+) gives the real shape; Android 11 falls back to the top bounding rect.
 */
@Composable
fun rememberHudTopShift(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    // Read so that a change of the cutout inset (rotation, a new window) recomposes and measures again.
    val cutoutInsetPx = WindowInsets.displayCutout.getTop(density)
    val cutoutDepthPx = remember(view, cutoutInsetPx) {
        view.rootWindowInsets?.displayCutout?.let(::topCutoutDepthPx) ?: cutoutInsetPx.toFloat()
    }
    val cutoutDepthDp = with(density) { cutoutDepthPx.toDp().value }
    return HudTopInset.shiftDp(cutoutDepthDp).dp
}

/** How far from the top edge the cutout on the top edge reaches, in px; 0 without one. */
private fun topCutoutDepthPx(cutout: DisplayCutout): Float {
    val topRect = cutout.boundingRectTop
    if (topRect.isEmpty) return 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val path = cutout.cutoutPath
        if (path != null && !path.isEmpty) {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            // Only the part of the path on the top edge: a second cutout elsewhere is not this bar's.
            if (bounds.top <= topRect.bottom && bounds.bottom > 0f) {
                return bounds.bottom.coerceAtMost(topRect.bottom.toFloat())
            }
        }
    }
    return topRect.bottom.toFloat()
}

package com.dazix.caelis.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Height in pixels of the strip along the bottom where the home and recents gestures start: the
 * window's own system-gesture inset, and never less than 28 dp (three-button navigation reports none,
 * yet a swipe up from the very edge is still no alignment).
 */
@Composable
fun rememberBottomGestureSlopPx(): Float {
    val density = LocalDensity.current
    val inset = WindowInsets.systemGestures.getBottom(density).toFloat()
    return maxOf(inset, with(density) { 28.dp.toPx() })
}

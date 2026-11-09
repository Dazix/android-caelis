package com.dazix.caelis.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dazix.caelis.domain.arframe.HudTopInset

/**
 * The shift every top-anchored HUD element of an AR screen adds to its designed top padding, so the
 * bar and the chips under it clear the camera cutout together ([HudTopInset]). The status bar is
 * hidden on those screens, so the display cutout is the inset that matters.
 */
@Composable
fun rememberHudTopShift(): Dp {
    val density = LocalDensity.current
    val cutoutTopPx = WindowInsets.displayCutout.getTop(density)
    val cutoutTopDp = with(density) { cutoutTopPx.toDp().value }
    return HudTopInset.shiftDp(cutoutTopDp).dp
}

package com.dazix.caelis.domain

/**
 * Screen position stored resolution-independently. A rect target has width/height > 0; a point
 * target leaves both at 0 and is tapped exactly at [xFraction]/[yFraction].
 */
data class TapTarget(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float = 0f,
    val heightFraction: Float = 0f,
    val jitter: Boolean = false,
) {
    val isRect: Boolean get() = widthFraction > 0f && heightFraction > 0f
}

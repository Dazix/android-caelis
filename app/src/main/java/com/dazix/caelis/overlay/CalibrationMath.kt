package com.dazix.caelis.overlay

/** Fractions of the *current* screen: centre plus size, the shape [com.dazix.caelis.domain.TapTarget] wants. */
data class ScreenFractions(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Turns a captured gesture into screen fractions. Split out of the calibration surface because it is
 * the one part of calibration that can be checked without a device.
 *
 * `xFraction`/`yFraction` are the *centre* of the target — that is how
 * [com.dazix.caelis.service.DisplayGeometry] turns them back into pixels — so a dragged area
 * is described by its centre plus its size, never by its top-left corner.
 */
object CalibrationMath {

    /**
     * [left]/[top]/[right]/[bottom] are raw display pixels; for a tap they are all the same point.
     * A drag shorter than the touch slop must be reported with [isDrag] false by the caller, which is
     * what keeps a shaky tap from being stored as a one-pixel rect that jitter would never move in.
     */
    fun screenFractions(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        isDrag: Boolean,
        widthPx: Int,
        heightPx: Int,
    ): ScreenFractions {
        val width = widthPx.toFloat().coerceAtLeast(1f)
        val height = heightPx.toFloat().coerceAtLeast(1f)
        val minX = minOf(left, right)
        val maxX = maxOf(left, right)
        val minY = minOf(top, bottom)
        val maxY = maxOf(top, bottom)
        return ScreenFractions(
            x = (((minX + maxX) / 2f) / width).coerceIn(0f, 1f),
            y = (((minY + maxY) / 2f) / height).coerceIn(0f, 1f),
            width = if (isDrag) ((maxX - minX) / width).coerceIn(0f, 1f) else 0f,
            height = if (isDrag) ((maxY - minY) / height).coerceIn(0f, 1f) else 0f,
        )
    }
}

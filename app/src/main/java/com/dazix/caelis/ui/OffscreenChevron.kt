package com.dazix.caelis.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * The chevron both AR screens put on the screen edge towards a body that is off screen: Night Sky's
 * towards the selected object, Sun AR's beside the sun or moon it points to. One shape, so the two
 * screens speak the same language; Sun AR once drew two thin strokes instead.
 *
 * In pixels, centred on the origin and pointing along +x (rotate the canvas to the direction).
 */
internal object OffscreenChevron {

    /** How far the shape reaches from its centre along the direction it points, in pixels. */
    const val HALF_LENGTH = 20f

    fun path(): Path = Path().apply {
        moveTo(HALF_LENGTH, 0f) // Tip
        lineTo(-HALF_LENGTH, -15f) // Top wing
        lineTo(-10f, 0f) // Inner indent
        lineTo(-HALF_LENGTH, 15f) // Bottom wing
        close()
    }

    /** Filled, with a dark glow that keeps it readable on a bright sky or a camera picture. */
    fun paint(argb: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
}

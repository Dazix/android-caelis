package com.dazix.caelis.domain.arframe

import kotlin.math.abs

/**
 * Whether two screen-aligned marks overlap, each given by its centre and half extents in pixels.
 *
 * Sun AR leaves out a time bubble that would sit on a rise or set badge: the 18:00 bubble drawn
 * over the "18:03" sunset badge made both unreadable, and the badge is the more precise of the two.
 * Judged on the screen, in the frame being drawn, because how far apart the two land depends on
 * the field of view and on how steeply the arc meets the horizon, not on their times.
 */
object MarkClearance {

    fun overlaps(
        ax: Float,
        ay: Float,
        aHalfWidth: Float,
        aHalfHeight: Float,
        bx: Float,
        by: Float,
        bHalfWidth: Float,
        bHalfHeight: Float,
    ): Boolean = abs(ax - bx) < aHalfWidth + bHalfWidth && abs(ay - by) < aHalfHeight + bHalfHeight
}

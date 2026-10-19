package com.dazix.caelis.domain.arframe

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where a rise or set badge's pill stands relative to its dot: on the arc itself, on the stretch that
 * runs below the horizon, just clear of the dot. At a rise that is the stretch before the dot, at a
 * set the stretch after it.
 *
 * No search and no state. That stretch is the faint one behind the ridge, which nothing else needs;
 * the pill lies along the line the arc already draws, so it can neither hide the part of the arc that
 * matters nor wander off it as the phone turns. How far from the dot it stands follows from the
 * direction the arc leaves in on the screen: a pill on a steep arc needs only half its height of
 * room, on a level one half its width.
 */
object BadgeOnArc {

    /** The share of the gap the rectangle placement left that is kept when it is measured to the outline. */
    const val GAP_SHARE = 0.75f

    /**
     * Writes into [out] (two floats) the pill centre minus the dot, in screen pixels (Y down), for a
     * pill with half extents ([halfWidth], [halfHeight]) on the arc that runs from the dot towards
     * ([towardX], [towardY]), any length.
     *
     * The pill is a capsule (its ends are half circles), and the distance that is kept is the one to its
     * outline along the arc. It was once worked out for the bounding rectangle, which on a slanted arc
     * reaches past the rounded corner by up to twice the pill's height and left the pill that much too far
     * from its dot. The gap that rectangle placement gave ([gap] beyond the rectangle) is now cut to [GAP_SHARE] of it
     * where it is measured to the outline, but never below [minGap], the dot's radius and some air.
     *
     * An arc with no direction (both ends on the dot) puts the pill straight down, on the ground side.
     */
    fun offset(
        towardX: Float,
        towardY: Float,
        halfWidth: Float,
        halfHeight: Float,
        gap: Float,
        minGap: Float,
        out: FloatArray,
    ) {
        var tx = towardX
        var ty = towardY
        val length = sqrt(tx * tx + ty * ty)
        if (length < 1e-3f) {
            tx = 0f
            ty = 1f
        } else {
            tx /= length
            ty /= length
        }
        val rectangleReach = abs(tx) * halfWidth + abs(ty) * halfHeight
        val outlineReach = capsuleReach(tx, ty, halfWidth, halfHeight)
        // What the old placement left between the dot and the pill's outline, and the share of it that is kept.
        val visibleGap = maxOf((rectangleReach - outlineReach + gap) * GAP_SHARE, minGap)
        val distance = outlineReach + visibleGap
        out[0] = tx * distance
        out[1] = ty * distance
    }

    /**
     * How far from its centre the outline of a capsule (half extents [halfWidth] x [halfHeight], ends
     * half circles of radius [halfHeight]) lies along the unit direction ([tx], [ty]).
     */
    fun capsuleReach(tx: Float, ty: Float, halfWidth: Float, halfHeight: Float): Float {
        // The straight part runs along x from -a to a; the ends are circles of radius halfHeight there.
        val a = maxOf(halfWidth - halfHeight, 0f)
        val absTy = abs(ty)
        if (absTy > 1e-6f) {
            val flat = halfHeight / absTy
            if (abs(flat * tx) <= a) return flat
        }
        // Out through an end cap: |s*t - c| = halfHeight with c the cap's centre on the side t points to.
        val cx = if (tx >= 0f) a else -a
        val along = tx * cx
        return along + sqrt(maxOf(along * along - cx * cx + halfHeight * halfHeight, 0f))
    }

    /**
     * Whether the segment from (x0, y0) to (x1, y1) crosses the rectangle with this centre and half
     * extents (Liang-Barsky); a segment that ends inside it counts. Sun AR uses it to leave out a time
     * bubble that stands on the stretch of arc between a badge's dot and its pill.
     */
    fun segmentHitsRect(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        cx: Float,
        cy: Float,
        halfWidth: Float,
        halfHeight: Float,
    ): Boolean {
        val left = cx - halfWidth
        val right = cx + halfWidth
        val top = cy - halfHeight
        val bottom = cy + halfHeight
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0f
        var t1 = 1f
        for (edge in 0 until 4) {
            val p: Float
            val q: Float
            when (edge) {
                0 -> { p = -dx; q = x0 - left }
                1 -> { p = dx; q = right - x0 }
                2 -> { p = -dy; q = y0 - top }
                else -> { p = dy; q = bottom - y0 }
            }
            if (p == 0f) {
                if (q < 0f) return false
            } else {
                val r = q / p
                if (p < 0f) t0 = maxOf(t0, r) else t1 = minOf(t1, r)
                if (t0 > t1) return false
            }
        }
        return true
    }
}

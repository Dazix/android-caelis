package com.dazix.caelis.domain.arframe

/**
 * The screen rectangles already taken in one frame, so a lower-priority mark can ask "is this spot
 * free?" and, when it is, take it for the marks after it.
 *
 * Fixed capacity and no allocation after construction: it is asked every frame of the AR render
 * loop. A full book stops recording rather than growing, which at worst lets a late, low-priority
 * mark overprint another; losing a mark that was never asked about would be the worse failure.
 */
class MarkReservations(private val capacity: Int) {

    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val halfWidth = FloatArray(capacity)
    private val halfHeight = FloatArray(capacity)

    var count = 0
        private set

    /** Starts a frame: nothing is reserved yet. */
    fun clear() {
        count = 0
    }

    /** Marks a rectangle (centre and half extents, pixels) as taken by a mark that has priority. */
    fun reserve(cx: Float, cy: Float, hw: Float, hh: Float) {
        if (count >= capacity) return
        x[count] = cx
        y[count] = cy
        halfWidth[count] = hw
        halfHeight[count] = hh
        count++
    }

    /** Whether a mark at this rectangle would overlap anything reserved so far. */
    fun isTaken(cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        for (r in 0 until count) {
            if (MarkClearance.overlaps(x[r], y[r], halfWidth[r], halfHeight[r], cx, cy, hw, hh)) return true
        }
        return false
    }

    /** Reserves the rectangle and returns true when it was free; returns false and reserves nothing when not. */
    fun tryReserve(cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        if (isTaken(cx, cy, hw, hh)) return false
        reserve(cx, cy, hw, hh)
        return true
    }
}

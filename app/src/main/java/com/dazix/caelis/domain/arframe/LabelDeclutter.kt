package com.dazix.caelis.domain.arframe

/**
 * Keeps sky names from printing over each other: a name that would land on one placed before it
 * moves one line down, then one line up, and is left out of the frame only when all three spots
 * are taken.
 *
 * Night Sky printed constellation and asterism names at their centroids with no clearance, and the
 * asterisms sit inside the constellations: "Orion's Belt" landed on a second asterism's name, the
 * "Winter Hexagon" on another, and both were unreadable. Callers place names in priority order
 * (the selection, then constellations, then asterisms), so the more important one keeps its spot.
 *
 * A name changes spot only with some margin ([place]'s `hysteresisPx`): two names whose boxes just
 * touch would otherwise swap between overlapping and not with every tremor of the hand, and the
 * name would jump a line up and down several times a second.
 *
 * Fixed capacity, no allocation after construction: it runs every frame of the AR render loop.
 */
class LabelDeclutter(capacity: Int) {

    private val book = MarkReservations(capacity)

    /** Starts a frame: nothing is placed yet. */
    fun clear() = book.clear()

    /** Takes a rectangle for a mark that never moves (the selected name) before the others are placed. */
    fun reserve(cx: Float, cy: Float, hw: Float, hh: Float) = book.reserve(cx, cy, hw, hh)

    /**
     * Places a name whose box at its own spot is centred on ([cx], [cy]) with half extents [hw] x [hh]
     * (pixels, Y down) and reserves the spot chosen. Returns [SLOT_BASE], [SLOT_BELOW] ([stepPx]
     * lower), [SLOT_ABOVE] ([stepPx] higher) or [HIDDEN], in which case nothing is reserved.
     *
     * [previousSlot] is what this name got in the last frame. A name that was moved or hidden goes
     * back to its own spot, or shows again, only when that spot is free by [hysteresisPx] all round;
     * a moved name otherwise keeps the spot it had while that one is free.
     */
    fun place(
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        stepPx: Float,
        previousSlot: Int,
        hysteresisPx: Float,
    ): Int {
        when (previousSlot) {
            SLOT_BASE -> return firstFree(cx, cy, hw, hh, stepPx, 0f)
            HIDDEN -> return firstFree(cx, cy, hw, hh, stepPx, hysteresisPx)
        }
        if (isFree(cx, cy, hw, hh, hysteresisPx)) return take(cx, cy, hw, hh, SLOT_BASE)
        val previousY = cy + offsetOf(previousSlot, stepPx)
        if (isFree(cx, previousY, hw, hh, 0f)) return take(cx, previousY, hw, hh, previousSlot)
        return firstFree(cx, cy, hw, hh, stepPx, 0f)
    }

    private fun firstFree(cx: Float, cy: Float, hw: Float, hh: Float, stepPx: Float, marginPx: Float): Int {
        for (slot in SLOT_BASE..SLOT_ABOVE) {
            val y = cy + offsetOf(slot, stepPx)
            if (isFree(cx, y, hw, hh, marginPx)) return take(cx, y, hw, hh, slot)
        }
        return HIDDEN
    }

    private fun isFree(cx: Float, cy: Float, hw: Float, hh: Float, marginPx: Float): Boolean =
        !book.isTaken(cx, cy, hw + marginPx, hh + marginPx)

    private fun take(cx: Float, cy: Float, hw: Float, hh: Float, slot: Int): Int {
        book.reserve(cx, cy, hw, hh)
        return slot
    }

    companion object {
        const val HIDDEN = -1
        const val SLOT_BASE = 0
        const val SLOT_BELOW = 1
        const val SLOT_ABOVE = 2

        /** Screen pixels (Y down) a name in [slot] is moved by from its own spot. */
        fun offsetOf(slot: Int, stepPx: Float): Float = when (slot) {
            SLOT_BELOW -> stepPx
            SLOT_ABOVE -> -stepPx
            else -> 0f
        }
    }
}

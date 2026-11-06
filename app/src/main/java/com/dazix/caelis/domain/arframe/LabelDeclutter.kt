package com.dazix.caelis.domain.arframe

import kotlin.math.abs

/**
 * Keeps sky names from printing over each other and over the stars of their own figure: a name
 * has three spots (its own, one line down, one line up), takes one no earlier name stands on and,
 * among those, the one covering the fewest of its figure's stars. It is left out of the frame only
 * when all three are taken by names.
 *
 * Night Sky printed constellation and asterism names at their centroids with no clearance, and the
 * asterisms sit inside the constellations: "Orion's Belt" landed on a second asterism's name, the
 * "Winter Hexagon" on another, and both were unreadable. Centred on its figure, a name also lay on
 * the figure's own stars (the belt's three stars run right through the middle of "Orion's Belt").
 * Callers place names in priority order (the selection, then constellations, then asterisms), so
 * the more important one keeps its spot. Stars only steer the choice, they never hide a name: a
 * figure too dense to clear is still better named over its stars than not at all.
 *
 * A name changes spot only with some margin ([place]'s `hysteresisPx`): two names whose boxes just
 * touch would otherwise swap between overlapping and not with every tremor of the hand, and the
 * name would jump a line up and down several times a second. For the same reason a spot keeps the
 * name while it covers no more stars than the best alternative.
 *
 * Fixed capacity, no allocation after construction: it runs every frame of the AR render loop.
 */
class LabelDeclutter(capacity: Int) {

    private val book = MarkReservations(capacity)

    /** Starts a frame: nothing is placed yet. */
    fun clear() = book.clear()

    /** Takes a rectangle for a mark placed elsewhere, before the names are placed. */
    fun reserve(cx: Float, cy: Float, hw: Float, hh: Float) = book.reserve(cx, cy, hw, hh)

    /**
     * Places a name whose box at its own spot is centred on ([cx], [cy]) with half extents [hw] x [hh]
     * (pixels, Y down) and reserves the spot chosen. Returns [SLOT_BASE], [SLOT_BELOW] ([stepPx]
     * lower), [SLOT_ABOVE] ([stepPx] higher) or [HIDDEN], in which case nothing is reserved.
     *
     * [previousSlot] is what this name got in the last frame: moving to any other spot asks for that
     * spot to be clear of other names by [hysteresisPx] all round, unless no spot is.
     *
     * The first [starCount] entries of [starX] / [starY] are the figure's own stars on screen; a star
     * counts as covered when its disc of [starRadiusPx] touches the box.
     */
    fun place(
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        stepPx: Float,
        previousSlot: Int,
        hysteresisPx: Float,
        starX: FloatArray = NO_STARS,
        starY: FloatArray = NO_STARS,
        starCount: Int = 0,
        starRadiusPx: Float = 0f,
    ): Int {
        var slot = best(cx, cy, hw, hh, stepPx, previousSlot, hysteresisPx, starX, starY, starCount, starRadiusPx)
        // A name pushed off its spot takes any free one rather than vanish; one already hidden stays
        // hidden until a spot is clear by the margin, or it would blink at the boundary.
        if (slot == HIDDEN && previousSlot != HIDDEN) {
            slot = best(cx, cy, hw, hh, stepPx, previousSlot, 0f, starX, starY, starCount, starRadiusPx)
        }
        if (slot != HIDDEN) book.reserve(cx, cy + offsetOf(slot, stepPx), hw, hh)
        return slot
    }

    private fun best(
        cx: Float, cy: Float, hw: Float, hh: Float, stepPx: Float,
        previousSlot: Int, marginPx: Float,
        starX: FloatArray, starY: FloatArray, starCount: Int, starRadiusPx: Float,
    ): Int {
        var bestSlot = HIDDEN
        var bestStars = Int.MAX_VALUE
        // Asked in the order own spot, previous spot, below, above; on a tie in covered stars the
        // first wins, so a name goes home when it can and otherwise stays where it was.
        for (k in 0..3) {
            val slot = when (k) {
                0 -> SLOT_BASE
                1 -> previousSlot
                else -> k - 1
            }
            if (slot !in SLOT_BASE..SLOT_ABOVE || (k != 0 && slot == SLOT_BASE) || (k >= 2 && slot == previousSlot)) continue
            val y = cy + offsetOf(slot, stepPx)
            val margin = if (slot == previousSlot) 0f else marginPx
            if (book.isTaken(cx, y, hw + margin, hh + margin)) continue
            val stars = coveredStars(cx, y, hw, hh, starX, starY, starCount, starRadiusPx)
            if (stars < bestStars) {
                bestSlot = slot
                bestStars = stars
                if (stars == 0) break
            }
        }
        return bestSlot
    }

    private fun coveredStars(
        cx: Float, cy: Float, hw: Float, hh: Float,
        starX: FloatArray, starY: FloatArray, starCount: Int, r: Float,
    ): Int {
        var n = 0
        for (i in 0 until starCount) {
            if (abs(starX[i] - cx) < hw + r && abs(starY[i] - cy) < hh + r) n++
        }
        return n
    }

    companion object {
        const val HIDDEN = -1
        const val SLOT_BASE = 0
        const val SLOT_BELOW = 1
        const val SLOT_ABOVE = 2

        private val NO_STARS = FloatArray(0)

        /** Screen pixels (Y down) a name in [slot] is moved by from its own spot. */
        fun offsetOf(slot: Int, stepPx: Float): Float = when (slot) {
            SLOT_BELOW -> stepPx
            SLOT_ABOVE -> -stepPx
            else -> 0f
        }
    }
}

package com.dazix.caelis.domain.arframe

/**
 * Shelf packing of sprites (pre-rendered labels) into one texture atlas.
 *
 * Sprites go left to right along a shelf as tall as the tallest sprite on it; one that does not fit
 * the rest of the row starts a new shelf below. [gutter] pixels are kept empty around every sprite,
 * so linear filtering at a sprite's edge never samples its neighbour. A sprite that does not fit is
 * refused rather than clipped — a truncated label would look like a different word. Zero
 * allocations.
 */
class AtlasPacker(val width: Int, val height: Int, val gutter: Int = 2) {

    private var cursorX = 0
    private var cursorY = 0
    private var shelfHeight = 0

    init {
        require(width > 0 && height > 0) { "atlas must have a size" }
        require(gutter >= 0) { "gutter must not be negative" }
    }

    /** Starts over with an empty atlas. */
    fun reset() {
        cursorX = 0
        cursorY = 0
        shelfHeight = 0
    }

    /**
     * Reserves a [w] x [h] pixel rectangle and writes its top-left corner into [out] (x, y).
     * Returns false, leaving [out] untouched, when it does not fit.
     */
    fun place(w: Int, h: Int, out: IntArray): Boolean {
        require(w >= 0 && h >= 0) { "sprite size must not be negative" }
        val cellW = w + 2 * gutter
        val cellH = h + 2 * gutter
        if (cellW > width || cellH > height) return false
        if (cursorX + cellW > width) {
            cursorY += shelfHeight
            cursorX = 0
            shelfHeight = 0
        }
        if (cursorY + cellH > height) return false
        out[0] = cursorX + gutter
        out[1] = cursorY + gutter
        cursorX += cellW
        if (cellH > shelfHeight) shelfHeight = cellH
        return true
    }
}

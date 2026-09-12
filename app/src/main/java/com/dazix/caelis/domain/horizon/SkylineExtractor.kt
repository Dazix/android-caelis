package com.dazix.caelis.domain.horizon

/**
 * Finds the sky/terrain boundary in a camera frame's luma plane.
 *
 * Only the Y plane is read — no colour conversion, no bitmap — because the boundary is a brightness
 * step and everything else is a waste of a frame's worth of memory. For every sampled column it takes
 * the strongest downward step: sky above, ground below. Columns where no step stands out are reported
 * as [NO_EDGE] rather than guessed at, because a guessed column is a wrong constraint and there is no
 * way for the matcher to tell it from a real one.
 *
 * Pure Kotlin so the thresholds can be tested against synthetic images instead of against the weather.
 */
object SkylineExtractor {

    /** No boundary stood out in this column. */
    const val NO_EDGE = -1

    /** How many rows either side of a candidate are averaged. Enough to ignore a single noisy row. */
    private const val WINDOW_ROWS = 12

    /** Rows skipped at the very top and bottom, where the window would run off the image. */
    private const val EDGE_MARGIN_ROWS = WINDOW_ROWS + 2

    /**
     * Minimum brightness step, out of 255, for a boundary to count.
     *
     * Low enough to survive an overcast sky over a dark ridge, which is the hard case and the one the
     * diagnostic screenshot actually contained; high enough that a gradient in a clear sky does not
     * read as terrain.
     */
    const val MIN_CONTRAST = 18.0

    /**
     * @param luma the Y plane, one byte per pixel, unsigned.
     * @param rowStride bytes per row, which is not always [width] on a camera buffer.
     * @param columnStride sample every n-th column; 1 reads them all.
     * @param outRows receives the first terrain row per sampled column, or [NO_EDGE].
     * @return how many columns were written into [outRows].
     */
    fun extract(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        columnStride: Int,
        outRows: IntArray,
    ): Int {
        require(columnStride >= 1) { "columnStride must be positive, got $columnStride" }
        if (width <= 0 || height <= 2 * EDGE_MARGIN_ROWS || rowStride < width) return 0

        var written = 0
        var x = 0
        while (x < width && written < outRows.size) {
            outRows[written++] = strongestEdge(luma, x, height, rowStride)
            x += columnStride
        }
        return written
    }

    private fun strongestEdge(luma: ByteArray, x: Int, height: Int, rowStride: Int): Int {
        var bestRow = NO_EDGE
        var bestStep = MIN_CONTRAST

        // Running sums: the two windows slide down together, so each row costs four array reads
        // instead of 2 * WINDOW_ROWS. At 480 columns that is the difference between a frame and a
        // noticeable pause on the button.
        var above = 0
        var below = 0
        val first = EDGE_MARGIN_ROWS
        for (k in 1..WINDOW_ROWS) {
            above += sample(luma, x, first - k, rowStride)
            below += sample(luma, x, first + k, rowStride)
        }

        var y = first
        val last = height - EDGE_MARGIN_ROWS - 1
        while (y <= last) {
            val step = (above - below).toDouble() / WINDOW_ROWS
            // A clean step gives the same score on the last sky row and the first ground row, since
            // the candidate row itself is in neither window. `>=` settles that tie on the ground
            // row, so the reported boundary is always "the first row that is terrain".
            if (step >= bestStep) {
                bestStep = step
                bestRow = y
            }
            if (y == last) break

            // Slide one row down.
            above += sample(luma, x, y, rowStride)
            above -= sample(luma, x, y - WINDOW_ROWS, rowStride)
            below += sample(luma, x, y + 1 + WINDOW_ROWS, rowStride)
            below -= sample(luma, x, y + 1, rowStride)
            y++
        }
        return bestRow
    }

    private fun sample(luma: ByteArray, x: Int, y: Int, rowStride: Int): Int {
        val index = y * rowStride + x
        if (index < 0 || index >= luma.size) return 0
        return luma[index].toInt() and 0xFF
    }
}

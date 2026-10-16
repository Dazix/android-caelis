package com.dazix.caelis.domain.horizon

/**
 * Finds the sky/terrain boundary in a camera frame's luma plane.
 *
 * Only the Y plane is read — no colour conversion, no bitmap — because the boundary is a brightness
 * step and everything else is a waste of a frame's worth of memory. For every sampled column it takes
 * the topmost clear downward step (see [RELATIVE_CONTRAST]): sky above, ground below. Columns where no step stands out are reported
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

    /**
     * A step counts as the skyline once it reaches this share of the column's strongest one.
     *
     * Found on the device: through a window at Ještěd the hazy ridge was a step of ~50 while a sunlit
     * wall below it was twice that, and taking the strongest step put a fifth of the columns 25° under
     * the ridge. The matcher's height comes from a low quantile on the assumption that every error
     * stands *above* the terrain (trees, roofs), so a boundary found below it is the one kind of error
     * it cannot absorb. The sky is above the skyline by definition: the first clear step from the top
     * is the answer, and the share only keeps a faint ripple in the sky from beating a real ridge.
     */
    private const val RELATIVE_CONTRAST = 0.4

    private fun strongestEdge(luma: ByteArray, x: Int, height: Int, rowStride: Int): Int {
        val strongest = scan(luma, x, height, rowStride, threshold = Double.NaN)
        if (strongest < MIN_CONTRAST) return NO_EDGE
        val threshold = maxOf(MIN_CONTRAST, strongest * RELATIVE_CONTRAST)
        return scan(luma, x, height, rowStride, threshold).toInt()
    }

    /**
     * One pass down a column. With a NaN [threshold] returns the strongest downward step; otherwise
     * the row of the first step reaching [threshold], taken at the peak of that step.
     */
    private fun scan(luma: ByteArray, x: Int, height: Int, rowStride: Int, threshold: Double): Double {
        val findStrongest = threshold.isNaN()
        var strongest = 0.0
        var bestRow = NO_EDGE
        var bestStep = 0.0

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
            if (findStrongest) {
                if (step > strongest) strongest = step
            } else if (bestRow == NO_EDGE) {
                if (step >= threshold) {
                    bestRow = y
                    bestStep = step
                }
            } else if (step >= bestStep) {
                // Still climbing the same step. A clean step gives the same score on the last sky
                // row and the first ground row, since the candidate row itself is in neither window;
                // `>=` settles that tie on the ground row, so the boundary is "the first terrain row".
                bestRow = y
                bestStep = step
            } else {
                break
            }
            if (y == last) break

            // Slide one row down.
            above += sample(luma, x, y, rowStride)
            above -= sample(luma, x, y - WINDOW_ROWS, rowStride)
            below += sample(luma, x, y + 1 + WINDOW_ROWS, rowStride)
            below -= sample(luma, x, y + 1, rowStride)
            y++
        }
        return if (findStrongest) strongest else bestRow.toDouble()
    }

    private fun sample(luma: ByteArray, x: Int, y: Int, rowStride: Int): Int {
        val index = y * rowStride + x
        if (index < 0 || index >= luma.size) return 0
        return luma[index].toInt() and 0xFF
    }
}

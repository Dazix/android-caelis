package com.dazix.caelis.domain.horizon

/**
 * Finds where the sky/terrain boundary could be in a camera frame's luma plane.
 *
 * Only the Y plane is read — no colour conversion, no bitmap — because the boundary is a brightness
 * step and everything else is a waste of a frame's worth of memory. For every sampled column it
 * reports the clear downward steps (sky above, something darker below) as *candidates*, top to
 * bottom, and does not decide which of them is the skyline: a cloud base, the ridge and a parapet are
 * all the same kind of step, and only the terrain profile can tell them apart ([SkylineMatcher]).
 * Columns where no step stands out report none rather than a guess, because a guessed column is a
 * wrong constraint and there is no way for the matcher to tell it from a real one.
 *
 * Pure Kotlin so the thresholds can be tested against synthetic images instead of against the weather.
 */
object SkylineExtractor {

    /** The most candidates kept per column; the strongest ones win when there are more. */
    const val MAX_CANDIDATES = 6

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
     * A step is a candidate once it reaches this share of the column's strongest one.
     *
     * The history is why there are candidates at all. Through a window at Ještěd the hazy ridge was a
     * step of ~50 while a sunlit wall below it was twice that, and taking the strongest step put a
     * fifth of the columns 25° under the ridge. Taking the first step from the top instead (at 0.4 of
     * the strongest) fixed that, and on a backlit rooftop the next month put most columns on the
     * underside of a cloud: the ridge was 37–50 there, the cloud edges above it as strong, the
     * parapet below it 100. Any single rule picks wrong in one of the two, so every clear step is
     * kept and the terrain chooses. The share only keeps a ripple in the sky (or in the ground) from
     * crowding a real ridge out of the [MAX_CANDIDATES].
     */
    private const val RELATIVE_CONTRAST = 0.25

    /**
     * @param luma the Y plane, one byte per pixel, unsigned.
     * @param rowStride bytes per row, which is not always [width] on a camera buffer.
     * @param columnStride sample every n-th column; 1 reads them all.
     * @param outRows receives, for sampled column i, its candidates' rows (the first terrain row of
     *   each step) at `i * MAX_CANDIDATES` onwards, top to bottom.
     * @param outCounts receives how many candidates column i has; 0 when nothing stood out.
     * @return how many columns were written.
     */
    fun extractCandidates(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        columnStride: Int,
        outRows: IntArray,
        outCounts: IntArray,
    ): Int {
        require(columnStride >= 1) { "columnStride must be positive, got $columnStride" }
        if (width <= 0 || height <= 2 * EDGE_MARGIN_ROWS || rowStride < width) return 0

        val steps = DoubleArray(height)
        val columns = minOf(outCounts.size, outRows.size / MAX_CANDIDATES)
        var written = 0
        var x = 0
        while (x < width && written < columns) {
            outCounts[written] = candidates(luma, x, height, rowStride, steps, outRows, written * MAX_CANDIDATES)
            written++
            x += columnStride
        }
        return written
    }

    /**
     * Fills [steps] with the downward step at every row of column [x], then writes the column's
     * candidates into [outRows] from [offset]. Returns how many.
     */
    private fun candidates(
        luma: ByteArray,
        x: Int,
        height: Int,
        rowStride: Int,
        steps: DoubleArray,
        outRows: IntArray,
        offset: Int,
    ): Int {
        val first = EDGE_MARGIN_ROWS
        val last = height - EDGE_MARGIN_ROWS - 1

        // Running sums: the two windows slide down together, so each row costs four array reads
        // instead of 2 * WINDOW_ROWS. At 480 columns that is the difference between a frame and a
        // noticeable pause on the button.
        var above = 0
        var below = 0
        for (k in 1..WINDOW_ROWS) {
            above += sample(luma, x, first - k, rowStride)
            below += sample(luma, x, first + k, rowStride)
        }
        var strongest = 0.0
        var y = first
        while (true) {
            val step = (above - below).toDouble() / WINDOW_ROWS
            steps[y] = step
            if (step > strongest) strongest = step
            if (y == last) break
            above += sample(luma, x, y, rowStride)
            above -= sample(luma, x, y - WINDOW_ROWS, rowStride)
            below += sample(luma, x, y + 1 + WINDOW_ROWS, rowStride)
            below -= sample(luma, x, y + 1, rowStride)
            y++
        }
        if (strongest < MIN_CONTRAST) return 0
        val threshold = maxOf(MIN_CONTRAST, strongest * RELATIVE_CONTRAST)

        var count = 0
        for (row in first..last) {
            val step = steps[row]
            if (step < threshold || !isPeak(steps, row, first, last)) continue
            if (count < MAX_CANDIDATES) {
                outRows[offset + count++] = row
                continue
            }
            // Full: this one replaces the weakest kept, if it is stronger.
            var weakest = 0
            for (k in 1 until count) {
                if (steps[outRows[offset + k]] < steps[outRows[offset + weakest]]) weakest = k
            }
            if (step <= steps[outRows[offset + weakest]]) continue
            // Keep the rows top to bottom: shift the later ones up over the weakest, append this one.
            for (k in weakest until count - 1) outRows[offset + k] = outRows[offset + k + 1]
            outRows[offset + count - 1] = row
        }
        return count
    }

    /**
     * The row is the top of its step within a window either way. A clean step scores the same on the
     * last sky row and the first ground row, since the candidate row itself is in neither window; the
     * strict comparison below settles that tie on the ground row, so a candidate is "the first
     * terrain row".
     */
    private fun isPeak(steps: DoubleArray, row: Int, first: Int, last: Int): Boolean {
        val step = steps[row]
        for (k in maxOf(first, row - WINDOW_ROWS) until row) if (steps[k] > step) return false
        for (k in row + 1..minOf(last, row + WINDOW_ROWS)) if (steps[k] >= step) return false
        return true
    }

    private fun sample(luma: ByteArray, x: Int, y: Int, rowStride: Int): Int {
        val index = y * rowStride + x
        if (index < 0 || index >= luma.size) return 0
        return luma[index].toInt() and 0xFF
    }
}

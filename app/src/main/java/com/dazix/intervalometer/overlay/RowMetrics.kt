package com.dazix.intervalometer.overlay

/**
 * The height arithmetic of a row of equal-width cells, kept away from the view code so it can be
 * tested without a device.
 *
 * A horizontal `LinearLayout` whose cells are `width = 0` + `weight = 1f` measures twice: first at
 * each cell's natural width, then again at the width the weights hand out. A label that fitted on
 * one line in the first pass can wrap in the second, which makes that cell taller than the height
 * the row already recorded — and the extra line is then drawn outside the row and clipped. That is
 * exactly what the Czech "Ztlumení: vypnuto" toggle did: its pill lost its bottom border.
 */
internal object RowMetrics {

    /**
     * The height the row has to report so that no cell is drawn outside it, or `null` when the row
     * as measured is already tall enough and nothing has to be redone.
     *
     * @param measuredHeight what the row measured itself as, content plus vertical padding.
     * @param cellHeights the measured height of every cell, each including its own vertical margins.
     * @param verticalPadding the row's own top plus bottom padding, added once.
     */
    fun correctedHeight(
        measuredHeight: Int,
        cellHeights: List<Int>,
        verticalPadding: Int,
    ): Int? {
        val tallest = cellHeights.maxOrNull() ?: return null
        val needed = tallest + verticalPadding
        return if (needed > measuredHeight) needed else null
    }

    /**
     * The height every cell is re-measured to once [correctedHeight] has raised the row: they all
     * match the tallest one, because two pills of different heights side by side read as a mistake
     * rather than as a wrapped label.
     */
    fun cellHeight(rowHeight: Int, verticalPadding: Int): Int =
        (rowHeight - verticalPadding).coerceAtLeast(0)
}

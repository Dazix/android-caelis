package com.dazix.intervalometer.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cell that grew taller than the row it lives in must raise the row, never be clipped by it: the
 * Czech "Ztlumení: vypnuto" toggle wrapped onto a second line when the weights narrowed it, and the
 * bottom of its pill was cut off because the row had already committed to a one-line height.
 */
class RowMetricsTest {

    private val padding = 12

    @Test
    fun `raises the row to the tallest cell`() {
        val corrected = RowMetrics.correctedHeight(
            measuredHeight = 132 + padding,
            cellHeights = listOf(132, 186),
            verticalPadding = padding,
        )
        assertEquals(186 + padding, corrected)
    }

    @Test
    fun `counts the row padding once, not once per cell`() {
        val corrected = RowMetrics.correctedHeight(
            measuredHeight = 100,
            cellHeights = listOf(200, 200),
            verticalPadding = padding,
        )
        assertEquals(200 + padding, corrected)
    }

    @Test
    fun `leaves a row alone when every cell already fits`() {
        assertNull(
            RowMetrics.correctedHeight(
                measuredHeight = 200 + padding,
                cellHeights = listOf(132, 200),
                verticalPadding = padding,
            )
        )
    }

    @Test
    fun `leaves a row alone when it is taller than its cells`() {
        assertNull(
            RowMetrics.correctedHeight(
                measuredHeight = 400,
                cellHeights = listOf(132, 132),
                verticalPadding = padding,
            )
        )
    }

    @Test
    fun `an empty row has nothing to correct`() {
        assertNull(
            RowMetrics.correctedHeight(
                measuredHeight = 0,
                cellHeights = emptyList(),
                verticalPadding = padding,
            )
        )
    }

    @Test
    fun `every cell is re-measured to the same height`() {
        assertEquals(186, RowMetrics.cellHeight(rowHeight = 186 + padding, verticalPadding = padding))
    }

    @Test
    fun `a row smaller than its own padding never asks for a negative cell`() {
        assertEquals(0, RowMetrics.cellHeight(rowHeight = 4, verticalPadding = padding))
    }
}

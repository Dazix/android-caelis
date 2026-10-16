package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkClearanceTest {

    @Test
    fun `marks with overlapping boxes overlap`() {
        assertTrue(MarkClearance.overlaps(100f, 100f, 50f, 15f, 140f, 110f, 25f, 10f))
    }

    @Test
    fun `marks side by side with a gap do not overlap`() {
        // 50 + 25 = 75 px of half widths, 76 px apart.
        assertFalse(MarkClearance.overlaps(100f, 100f, 50f, 15f, 176f, 100f, 25f, 10f))
    }

    @Test
    fun `marks that only touch do not overlap`() {
        assertFalse(MarkClearance.overlaps(100f, 100f, 50f, 15f, 175f, 100f, 25f, 10f))
        assertFalse(MarkClearance.overlaps(100f, 100f, 50f, 15f, 100f, 125f, 25f, 10f))
    }

    @Test
    fun `marks stacked vertically with a gap do not overlap even when aligned horizontally`() {
        assertFalse(MarkClearance.overlaps(100f, 100f, 50f, 15f, 100f, 130f, 25f, 10f))
        assertTrue(MarkClearance.overlaps(100f, 100f, 50f, 15f, 100f, 124f, 25f, 10f))
    }

    @Test
    fun `overlap is symmetric`() {
        assertTrue(MarkClearance.overlaps(140f, 110f, 25f, 10f, 100f, 100f, 50f, 15f))
        assertFalse(MarkClearance.overlaps(176f, 100f, 25f, 10f, 100f, 100f, 50f, 15f))
    }
}

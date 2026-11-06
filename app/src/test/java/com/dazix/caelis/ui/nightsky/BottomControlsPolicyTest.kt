package com.dazix.caelis.ui.nightsky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomControlsPolicyTest {

    // A 1080 px phone less 2 x 16 dp at 2.625: a 996 px row, a 168 px button column, 21 px gap.
    private val row = 996
    private val column = 168
    private val gap = 21

    @Test
    fun `a narrow HUD leaves the buttons on the bottom line`() {
        // LST and the star count only: 600 px, its right edge at 798, the column starts at 828.
        assertFalse(BottomControlsPolicy.liftButtons(row, 600, column, gap))
    }

    @Test
    fun `a HUD grown by the virtual clock lifts the buttons`() {
        // With the clock: 830 px, right edge at 913, into the column.
        assertTrue(BottomControlsPolicy.liftButtons(row, 830, column, gap))
    }

    @Test
    fun `coming within the gap already lifts them`() {
        // Right edge at 818: 10 px short of the column, inside the 21 px gap.
        assertTrue(BottomControlsPolicy.liftButtons(row, 640, column, gap))
    }

    @Test
    fun `nothing measured yet never lifts`() {
        assertFalse(BottomControlsPolicy.liftButtons(0, 830, column, gap))
        assertFalse(BottomControlsPolicy.liftButtons(row, 0, column, gap))
        assertFalse(BottomControlsPolicy.liftButtons(row, 830, 0, gap))
    }
}

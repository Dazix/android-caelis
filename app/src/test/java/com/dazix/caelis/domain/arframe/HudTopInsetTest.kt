package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test

class HudTopInsetTest {

    @Test
    fun `no cutout leaves the HUD where it was designed`() {
        assertEquals(0f, HudTopInset.shiftDp(0f), 0f)
    }

    @Test
    fun `a cutout ending above the bar with the gap to spare moves nothing`() {
        assertEquals(0f, HudTopInset.shiftDp(20f), 0f)
        assertEquals(0f, HudTopInset.shiftDp(HudTopInset.DESIGN_TOP_DP - HudTopInset.CUTOUT_GAP_DP), 0f)
    }

    @Test
    fun `a cutout reaching into the bar moves it below the cutout plus the gap`() {
        val cutout = 52f
        val shift = HudTopInset.shiftDp(cutout)
        assertEquals(cutout + HudTopInset.CUTOUT_GAP_DP, HudTopInset.DESIGN_TOP_DP + shift, 1e-4f)
    }

    @Test
    fun `a cutout just past the threshold moves the bar by exactly the overlap`() {
        val cutout = HudTopInset.DESIGN_TOP_DP - HudTopInset.CUTOUT_GAP_DP + 1f
        assertEquals(1f, HudTopInset.shiftDp(cutout), 1e-4f)
    }

    @Test
    fun `an unreadable inset never moves the HUD`() {
        assertEquals(0f, HudTopInset.shiftDp(-10f), 0f)
        assertEquals(0f, HudTopInset.shiftDp(Float.NaN), 0f)
    }
}

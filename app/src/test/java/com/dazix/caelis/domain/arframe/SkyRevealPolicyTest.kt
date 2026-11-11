package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.SkyRevealPolicy.NO_FRAME
import com.dazix.caelis.domain.arframe.SkyRevealPolicy.PICTURE_WAIT_NS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyRevealPolicyTest {

    private val first = 5_000_000_000L

    @Test
    fun `nothing is revealed before the first frame, whatever the picture says`() {
        assertFalse(SkyRevealPolicy.shouldReveal(NO_FRAME, first, pictureWanted = true, pictureShown = true))
        assertFalse(SkyRevealPolicy.shouldReveal(NO_FRAME, first, pictureWanted = false, pictureShown = false))
    }

    @Test
    fun `a wanted picture holds the sky back until it is in the frame`() {
        assertFalse(SkyRevealPolicy.shouldReveal(first, first, pictureWanted = true, pictureShown = false))
        assertFalse(SkyRevealPolicy.shouldReveal(first, first + PICTURE_WAIT_NS - 1, pictureWanted = true, pictureShown = false))
        assertTrue(SkyRevealPolicy.shouldReveal(first, first + 16_000_000L, pictureWanted = true, pictureShown = true))
    }

    @Test
    fun `the picture is shown with the very first frame when it is already there`() {
        assertTrue(SkyRevealPolicy.shouldReveal(first, first, pictureWanted = true, pictureShown = true))
    }

    @Test
    fun `a camera that never delivers does not keep the screen empty`() {
        assertTrue(SkyRevealPolicy.shouldReveal(first, first + PICTURE_WAIT_NS, pictureWanted = true, pictureShown = false))
    }

    @Test
    fun `without a wanted picture the first frame reveals at once`() {
        assertTrue(SkyRevealPolicy.shouldReveal(first, first, pictureWanted = false, pictureShown = false))
    }
}

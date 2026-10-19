package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemGestureZoneTest {

    private fun inZone(x: Float, y: Float) =
        SystemGestureZone.contains(x, y, width = 1080f, height = 2400f, sideSlopPx = 77f, bottomSlopPx = 96f)

    @Test
    fun `the bottom strip is the home gesture's`() {
        assertTrue(inZone(540f, 2399f))
        assertTrue(inZone(540f, 2305f))
        assertFalse(inZone(540f, 2303f))
    }

    @Test
    fun `both sides are the back gesture's`() {
        assertTrue(inZone(10f, 1200f))
        assertTrue(inZone(1070f, 1200f))
        assertFalse(inZone(78f, 1200f))
        assertFalse(inZone(1002f, 1200f))
    }

    @Test
    fun `the middle and the top are the overlay's`() {
        assertFalse(inZone(540f, 1200f))
        assertFalse(inZone(540f, 0f))
    }
}

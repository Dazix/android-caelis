package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonMorphTest {

    @Test
    fun `a new morph shows its target and does not run`() {
        val m = HorizonMorph()
        assertFalse(m.isRunning)
        assertEquals(1f, m.eased, 0f)
        assertEquals(7f, m.blend(0f, 7f), 0f)
        assertFalse(m.advance(0.016f))
    }

    @Test
    fun `restart begins at the old shape and reaches the new one after the duration`() {
        val m = HorizonMorph(durationSeconds = 0.8f)
        m.restart()
        assertTrue(m.isRunning)
        assertEquals(2f, m.blend(2f, 6f), 0f)

        assertTrue(m.advance(0.4f))
        assertEquals(0.5f, m.progress, 1e-6f)
        // Smoothstep is symmetric: halfway in time is halfway in shape.
        assertEquals(4f, m.blend(2f, 6f), 1e-5f)

        m.advance(0.4f)
        assertFalse(m.isRunning)
        assertEquals(6f, m.blend(2f, 6f), 0f)
    }

    @Test
    fun `easing is slow at both ends`() {
        val m = HorizonMorph(durationSeconds = 1f)
        m.restart()
        m.advance(0.1f)
        // 0.1 linear -> 0.028 eased: the ridge starts rising gently rather than jumping.
        assertEquals(0.028f, m.eased, 1e-5f)
        m.advance(0.8f)
        assertEquals(0.972f, m.eased, 1e-5f)
    }

    @Test
    fun `progress clamps and ignores negative or oversized steps`() {
        val m = HorizonMorph(durationSeconds = 0.8f)
        m.restart()
        m.advance(-1f)
        assertEquals(0f, m.progress, 0f)
        m.advance(10f)
        assertEquals(1f, m.progress, 0f)
    }

    @Test
    fun `finish jumps to the target`() {
        val m = HorizonMorph()
        m.restart()
        m.finish()
        assertFalse(m.isRunning)
        assertEquals(5f, m.blend(1f, 5f), 0f)
    }
}

package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Test

class ConstellationArtOpacityTest {

    @Test
    fun `the default is the alpha the artwork was always drawn with`() {
        assertEquals(90f / 255f, ConstellationArtOpacity.DEFAULT, 0f)
        assertEquals(90f / 255f, ConstellationArtOpacity.quadOpacity(ConstellationArtOpacity.DEFAULT, 1f), 1e-6f)
    }

    @Test
    fun `the slider can neither hide the artwork nor overshoot opaque`() {
        assertEquals(ConstellationArtOpacity.MIN, ConstellationArtOpacity.clamp(0f), 0f)
        assertEquals(ConstellationArtOpacity.MIN, ConstellationArtOpacity.clamp(-3f), 0f)
        assertEquals(ConstellationArtOpacity.MAX, ConstellationArtOpacity.clamp(1.7f), 0f)
        assertEquals(0.5f, ConstellationArtOpacity.clamp(0.5f), 0f)
        assertEquals(ConstellationArtOpacity.DEFAULT, ConstellationArtOpacity.clamp(Float.NaN), 0f)
    }

    @Test
    fun `the opacity grows with the figure and is zero before it appears`() {
        assertEquals(0f, ConstellationArtOpacity.quadOpacity(0.8f, 0f), 0f)
        assertEquals(0.4f, ConstellationArtOpacity.quadOpacity(0.8f, 0.5f), 1e-6f)
        assertEquals(0.8f, ConstellationArtOpacity.quadOpacity(0.8f, 1f), 1e-6f)
        assertEquals("an out-of-range value is clamped first", 1f, ConstellationArtOpacity.quadOpacity(5f, 1f), 0f)
    }
}

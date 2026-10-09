package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class PlanetAppearanceTest {

    private val focal = 1000f
    private val density = 2.75f

    @Test
    fun `a small planet at 1x is a point with a magnitude halo`() {
        val a = PlanetAppearance()
        a.compute("Jupiter", false, 0.013f, -2.5f, focal, density, 1f)
        assertFalse(a.textured)
        // 2 f tan(d/2), no boost at d = 0.013.
        val expected = 2f * focal * tan(Math.toRadians(0.0065)).toFloat()
        assertEquals(expected, a.diameterPx, 1e-3f)
        assertEquals(2.5f * density, a.coreRadiusPx, 1e-4f)
        val intensity = 1f - (-2.5f + 5f) / 13f
        assertEquals((10f + 25f * intensity) * (2f / 3f) * density, a.glowRadiusPx, 1e-3f)
        assertEquals((140 * intensity).toInt() / 255f, a.glowAlpha, 1e-6f)
        assertEquals(maxOf(10f * density, a.glowRadiusPx), a.hitRadiusPx, 1e-4f)
    }

    @Test
    fun `tiny planets are boosted at most ten times and the moon never`() {
        val a = PlanetAppearance()
        a.compute("Neptune", false, 0.0006f, 7.8f, focal, density, 1f)
        val raw = 2f * focal * tan(Math.toRadians(0.0003)).toFloat()
        assertEquals(raw * 10f, a.diameterPx, 1e-3f)

        a.compute("Moon", true, 0.52f, -12f, focal, density, 1f)
        assertEquals(2f * focal * tan(Math.toRadians(0.26)).toFloat(), a.diameterPx, 1e-3f)
    }

    @Test
    fun `an implausible lunar diameter falls back to the mean`() {
        val a = PlanetAppearance()
        a.compute("Moon", true, 0f, -12f, focal, density, 1f)
        assertEquals(2f * focal * tan(Math.toRadians(0.259)).toFloat(), a.diameterPx, 1e-3f)
    }

    @Test
    fun `above fifteen pixels a body becomes a texture with a fading rim glow`() {
        val a = PlanetAppearance()
        // 2 f tan(0.0025 deg) x 2.6 boost = 45 px at this focal length.
        a.compute("Saturn", false, 0.005f, 0.5f, 200_000f, density, 20f)
        assertTrue(a.textured)
        assertEquals(2f * 200_000f * tan(Math.toRadians(0.0025)).toFloat() * 2.6f, a.diameterPx, 1e-2f)
        assertEquals(a.diameterPx * 0.5f * 2.38f, a.glowRadiusPx, 1e-3f)
        assertEquals(PlanetAppearance.SATURN_IMAGE_RATIO, a.imageToBodyRatio, 0f)
        // Rings are part of the image, and part of what a finger can hit.
        assertEquals(maxOf(12f * density, a.diameterPx * 2.33f * 0.5f), a.hitRadiusPx, 1e-3f)
        val intensity = 1f - (0.5f + 5f) / 13f
        val fade = (30f / a.diameterPx).coerceIn(0.1f, 1f)
        assertEquals((140 * intensity * fade).toInt().coerceIn(15, 200) / 255f, a.glowAlpha, 1e-6f)
    }

    @Test
    fun `glow colours match the old paints and default to white`() {
        assertEquals(0xFF6B36, PlanetAppearance.glowColorRgb("mars"))
        assertEquals(0xFFFFFF, PlanetAppearance.glowColorRgb("Pluto"))
    }
}

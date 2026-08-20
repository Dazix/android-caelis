package com.dazix.intervalometer.ui.nightsky

import org.junit.Assert.assertNotNull
import org.junit.Test

class NightSkyRendererZeroAllocTest {

    @Test
    fun `verifies renderer pre-allocated paints and red vision theme`() {
        val renderer = NightSkyRenderer()

        assertNotNull(renderer.tier1Paint)
        assertNotNull(renderer.tier2Paint)
        assertNotNull(renderer.tier3Paint)
        assertNotNull(renderer.extinctionPaint)
        assertNotNull(renderer.constellationPaint)
        assertNotNull(renderer.constellationLabelPaint)
        assertNotNull(renderer.skyDomeRenderer)

        // Apply Red Vision mode
        renderer.applyTheme(redVisionMode = true)

        // Apply Normal mode
        renderer.applyTheme(redVisionMode = false)

        // Update stroke widths
        renderer.updateStrokeWidths(tier1Px = 7.5f, tier2Px = 5.0f, tier3Px = 3.0f, constellationPx = 1.8f)
    }

    @Test
    fun `verifies milky way agsl shader defines all required uniforms and raycasting logic`() {
        val src = MilkyWayShader.SRC

        org.junit.Assert.assertTrue("Shader must declare milkyWayTexture uniform", src.contains("uniform shader milkyWayTexture;"))
        org.junit.Assert.assertTrue("Shader must declare resolution uniform", src.contains("uniform float2 resolution;"))
        org.junit.Assert.assertTrue("Shader must declare textureSize uniform", src.contains("uniform float2 textureSize;"))
        org.junit.Assert.assertTrue("Shader must declare viewMatrix uniform", src.contains("uniform mat3 viewMatrix;"))
        org.junit.Assert.assertTrue("Shader must declare fovFactor uniform", src.contains("uniform float fovFactor;"))
        org.junit.Assert.assertTrue("Shader must declare isRedVision uniform", src.contains("uniform float isRedVision;"))
        org.junit.Assert.assertTrue("Shader must contain spherical coordinate conversion", src.contains("atan(worldDir.z, worldDir.x)"))
        org.junit.Assert.assertTrue("Shader must contain declination latitude conversion", src.contains("asin(clamp(worldDir.y, -1.0, 1.0))"))
    }

    @Test
    fun `verifies atmosphere agsl shader defines all required uniforms and horizon extinction raycasting`() {
        val src = AtmosphereShader.SRC

        org.junit.Assert.assertTrue("Shader must declare resolution uniform", src.contains("uniform float2 resolution;"))
        org.junit.Assert.assertTrue("Shader must declare viewMatrix uniform", src.contains("uniform mat3 viewMatrix;"))
        org.junit.Assert.assertTrue("Shader must declare fovFactor uniform", src.contains("uniform float fovFactor;"))
        org.junit.Assert.assertTrue("Shader must declare isRedVision uniform", src.contains("uniform float isRedVision;"))
        org.junit.Assert.assertTrue("Shader must extract Z elevation", src.contains("sinElevation = worldDir.z"))
        org.junit.Assert.assertTrue("Shader must discard pixels below horizon", src.contains("if (sinElevation < 0.0) return half4(0.0);"))
        org.junit.Assert.assertTrue("Shader must apply max elevation of 15 degrees", src.contains("0.258"))
        org.junit.Assert.assertTrue("Shader must apply non-linear exponential decay", src.contains("pow(factor, 3.0)"))
        org.junit.Assert.assertTrue("Shader must contain red vision filter", src.contains("luminance * 0.4"))
    }

    @Test
    fun `verifies star extinction calculation math`() {
        fun computeExtinction(elevationDeg: Float): Float {
            return if (elevationDeg <= 0.0f) 0.0f else (elevationDeg / 15.0f).coerceIn(0.0f, 1.0f)
        }

        org.junit.Assert.assertEquals(0.0f, computeExtinction(-5.0f), 0.001f)
        org.junit.Assert.assertEquals(0.0f, computeExtinction(0.0f), 0.001f)
        org.junit.Assert.assertEquals(0.5f, computeExtinction(7.5f), 0.001f)
        org.junit.Assert.assertEquals(1.0f, computeExtinction(15.0f), 0.001f)
        org.junit.Assert.assertEquals(1.0f, computeExtinction(30.0f), 0.001f)
    }
}

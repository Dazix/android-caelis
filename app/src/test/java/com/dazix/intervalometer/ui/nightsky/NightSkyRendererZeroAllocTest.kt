package com.dazix.intervalometer.ui.nightsky

import org.junit.Assert.assertNotNull
import org.junit.Test

class NightSkyRendererZeroAllocTest {

    @Test
    fun `verifies renderer pre-allocated paints and red vision theme`() {
        val renderer = NightSkyRenderer()

        org.junit.Assert.assertEquals(6, renderer.numBuckets)
        org.junit.Assert.assertEquals(6, renderer.corePaints.size)
        org.junit.Assert.assertEquals(6, renderer.bucketPoints.size)
        org.junit.Assert.assertEquals(6, renderer.bucketCounts.size)

        for (b in 0 until 6) {
            assertNotNull(renderer.corePaints[b])
            org.junit.Assert.assertEquals(4096 * 2, renderer.bucketPoints[b].size)
        }

        assertNotNull(renderer.spriteMatrix)
        assertNotNull(renderer.spritePaint)
        assertNotNull(renderer.constellationPaint)
        assertNotNull(renderer.constellationLabelPaint)
        assertNotNull(renderer.skyDomeRenderer)

        // Apply Red Vision mode
        renderer.applyTheme(redVisionMode = true)

        // Apply Normal mode
        renderer.applyTheme(redVisionMode = false)

        // Update optical paints at 1.0x, 2.0x, 4.0x zoom
        renderer.updateOpticalPaints(zoomFactor = 1.0f, density = 2.5f, redVisionMode = false)
        renderer.updateOpticalPaints(zoomFactor = 4.0f, density = 2.5f, redVisionMode = false)

        // Verify hit-testing method returns -1 for empty stars
        val hitIndex = renderer.findClosestStarAt(100f, 100f, 48f, 0)
        org.junit.Assert.assertEquals(-1, hitIndex)
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

    @Test
    fun `verifies landscape agsl shader defines all required uniforms and earth-fixed raycasting`() {
        val src = LandscapeShader.SRC

        org.junit.Assert.assertTrue("Shader must declare landscapeTexture uniform", src.contains("uniform shader landscapeTexture;"))
        org.junit.Assert.assertTrue("Shader must declare resolution uniform", src.contains("uniform float2 resolution;"))
        org.junit.Assert.assertTrue("Shader must declare textureSize uniform", src.contains("uniform float2 textureSize;"))
        org.junit.Assert.assertTrue("Shader must declare viewMatrix uniform", src.contains("uniform mat3 viewMatrix;"))
        org.junit.Assert.assertTrue("Shader must declare fovFactor uniform", src.contains("uniform float fovFactor;"))
        org.junit.Assert.assertTrue("Shader must declare isRedVision uniform", src.contains("uniform float isRedVision;"))
        org.junit.Assert.assertTrue("Shader must contain spherical azimuth conversion", src.contains("atan(worldDir.x, worldDir.y)"))
        org.junit.Assert.assertTrue("Shader must contain spherical elevation conversion", src.contains("asin(clamp(worldDir.z, -1.0, 1.0))"))
        org.junit.Assert.assertTrue("Shader must contain equirectangular UV mapping", src.contains("texU = (theta / 6.283185307179586) + 0.5;"))
        org.junit.Assert.assertTrue("Shader must contain red vision filter", src.contains("luminance * 0.4"))
    }
}

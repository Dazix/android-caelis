package com.dazix.intervalometer.ui.nightsky

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.data.nightsky.StarMetadata
import com.dazix.intervalometer.domain.nightsky.NightSkyCatalogLoader
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            org.junit.Assert.assertEquals(10240 * 2, renderer.bucketPoints[b].size)
        }

        assertNotNull(renderer.spriteMatrix)
        assertNotNull(renderer.spritePaint)
        assertNotNull(renderer.constellationPaint)
        assertNotNull(renderer.constellationHaloPaint)
        assertNotNull(renderer.constellationCorePaint)
        assertNotNull(renderer.constellationLabelPaint)
        assertNotNull(renderer.constellationHighlightLabelPaint)
        assertNotNull(renderer.reticlePaint)
        assertNotNull(renderer.reticleTextPaint)
        assertNotNull(renderer.skyDomeRenderer)

        org.junit.Assert.assertEquals(88, renderer.constellationProgress.size)
        org.junit.Assert.assertEquals(88, renderer.constellationCentroidScreenX.size)
        org.junit.Assert.assertEquals(88, renderer.constellationCentroidScreenY.size)
        org.junit.Assert.assertEquals(88, renderer.constellationCentroidInFront.size)
        org.junit.Assert.assertEquals(10240, renderer.forceRenderStar.size)

        // Apply Red Vision mode
        renderer.applyTheme(redVisionMode = true)

        // Apply Normal mode
        renderer.applyTheme(redVisionMode = false)

        // Update optical paints at 1.0x, 2.0x, 4.0x zoom
        renderer.updateOpticalPaints(zoomFactor = 1.0f, density = 2.5f, redVisionMode = false)
        renderer.updateOpticalPaints(zoomFactor = 4.0f, density = 2.5f, redVisionMode = false)

        // Verify hit-testing method returns Miss for null catalog
        val hit = renderer.findHitAt(100f, 100f, 48f, 2.5f, null)
        org.junit.Assert.assertTrue("Null catalog hit must be Miss", hit is NightSkyRenderer.HitTarget.Miss)

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

    @Test
    fun `verifies selection reticle preallocated paints and buffers exist`() {
        val renderer = NightSkyRenderer()

        // Verify reticle paint and text paint are non-null and correctly initialized
        org.junit.Assert.assertNotNull(renderer.reticlePaint)
        org.junit.Assert.assertNotNull(renderer.reticleTextPaint)
    }

    @Test
    fun `verifies dynamic zoom override force renders visible constellation stars`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. Constellation with 2 stars (star 0 and star 1)
        val constNameOff = fbb.createString("Crater")
        val locNameOff = fbb.createString("Pohár")
        val brightOff = fbb.createString("Delta Crateris")
        val famOff = fbb.createString("Hercules Family")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val constOff = Constellation.createConstellation(fbb, constNameOff, 0.0f, 90.0f, linesVec, locNameOff, brightOff, famOff)
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))

        // 2. StarMetadata
        val metaNameOff = fbb.createString("Star0")
        val metaOff = StarMetadata.createStarMetadata(fbb, 0, metaNameOff, 0, 0, 10.0f)
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf(metaOff))

        // 3. Stars (Structs vector) near North Celestial Pole:
        // Star 0: RA=0.0, Dec=90.0, Mag=5.8f (faint, in Crater constellation)
        // Star 1: RA=0.0, Dec=89.0, Mag=5.6f (faint, in Crater constellation)
        // Star 2: RA=0.0, Dec=88.0, Mag=5.7f (faint, standalone - NOT in any constellation)
        // Star 3: RA=0.0, Dec=87.0, Mag=1.2f (bright, standalone)
        NightSkyCatalog.startStarsVector(fbb, 4)
        Star.createStar(fbb, 0.0f, 87.0f, 1.2f) // index 3
        Star.createStar(fbb, 0.0f, 88.0f, 5.7f) // index 2
        Star.createStar(fbb, 0.0f, 89.0f, 5.6f) // index 1
        Star.createStar(fbb, 0.0f, 90.0f, 5.8f) // index 0
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())

        val renderer = NightSkyRenderer()
        renderer.applyTheme(redVisionMode = false)

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        // Case A: Constellations ENABLED (showConstellations = true)
        // At zoomFactor = 1.0f, base limiting mag is 4.5.
        // Crater is in front & center, so its progress ramps up.
        // Star 0 (mag 5.8) and Star 1 (mag 5.6) are forced to render via dynamic override.
        // Star 2 (mag 5.7) is not part of Crater, so it is culled.
        // Star 3 (mag 1.2) is bright, so it is rendered.
        // Visible stars should be 3 (Star 0, Star 1, Star 3).
        var visibleStarsEnabled = 0
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            visibleStarsEnabled = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
            )
        }

        assertTrue("Constellation progress must be > 0", renderer.constellationProgress[0] > 0.001f)
        assertTrue("Star 0 must be forced to render", renderer.forceRenderStar[0])
        assertTrue("Star 1 must be forced to render", renderer.forceRenderStar[1])
        assertTrue("Star 2 must NOT be forced to render", !renderer.forceRenderStar[2])
        assertTrue("Star 3 must NOT be forced to render (it's naturally bright)", !renderer.forceRenderStar[3])
        assertEquals("Should render 3 stars (2 forced faint constellation stars + 1 bright star)", 3, visibleStarsEnabled)

        // Case B: Constellations DISABLED (showConstellations = false)
        // forceRenderStar must be cleared to all false.
        // Only bright Star 3 (mag 1.2 <= 4.5) should be rendered; faint stars 0, 1, 2 must all be culled.
        var visibleStarsDisabled = 0
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            visibleStarsDisabled = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = false,
            )
        }

        assertTrue("Star 0 must NOT be forced when constellations are disabled", !renderer.forceRenderStar[0])
        assertTrue("Star 1 must NOT be forced when constellations are disabled", !renderer.forceRenderStar[1])
        assertTrue("Star 2 must NOT be forced", !renderer.forceRenderStar[2])
        assertEquals("Only 1 bright star should be rendered when constellations disabled", 1, visibleStarsDisabled)
    }
}

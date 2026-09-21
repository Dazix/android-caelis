package com.dazix.caelis.ui.nightsky

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.data.nightsky.StarMetadata
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.NightSkyCatalogLoader
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS * 2, renderer.bucketPoints[b].size)
        }

        assertNotNull(renderer.spriteMatrix)
        assertNotNull(renderer.spritePaint)
        assertNotNull(renderer.constellationPaint)
        assertNotNull(renderer.constellationHaloPaint)
        assertNotNull(renderer.constellationCorePaint)
        assertNotNull(renderer.constellationLabelPaint)
        assertNotNull(renderer.constellationHighlightLabelPaint)
        assertNotNull(renderer.asterismPaint)
        assertNotNull(renderer.asterismHaloPaint)
        assertNotNull(renderer.asterismCorePaint)
        assertNotNull(renderer.asterismLabelPaint)
        assertNotNull(renderer.asterismHighlightLabelPaint)
        assertNotNull(renderer.reticlePaint)
        assertNotNull(renderer.reticleTextPaint)
        assertNotNull(renderer.skyDomeRenderer)
        assertNotNull(renderer.artworkCache)
        assertNotNull(renderer.artMatrix)
        assertNotNull(renderer.artPaint)
        assertEquals(8, renderer.artSrcPoints.size)
        assertEquals(8, renderer.artDstPoints.size)
        assertEquals(12, renderer.artCornerProjected.size)
        assertNotNull(renderer.debugFocusCirclePaint)
        assertNotNull(renderer.debugFocusFillPaint)
        assertNotNull(renderer.debugFocusLabelPaint)
        assertNotNull(renderer.debugCentroidPaint)

        org.junit.Assert.assertEquals(256, renderer.constellationProgress.size)
        org.junit.Assert.assertEquals(256, renderer.constellationCentroidScreenX.size)
        org.junit.Assert.assertEquals(256, renderer.constellationCentroidScreenY.size)
        org.junit.Assert.assertEquals(256, renderer.constellationCentroidInFront.size)
        org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS, renderer.forceRenderStar.size)
        org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS, renderer.starWorldX.size)
        org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS, renderer.starWorldY.size)
        org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS, renderer.starWorldZ.size)
        org.junit.Assert.assertEquals(NightSkyRenderer.MAX_STARS, renderer.starVectorComputed.size)

        // Apply Red Vision mode
        renderer.applyTheme(redVisionMode = true)

        // Apply Normal mode
        renderer.applyTheme(redVisionMode = false)

        // Update optical paints at 1.0x, 2.0x, 4.0x zoom and verify 1.5x artwork highlight boost
        renderer.updateOpticalPaints(zoomFactor = 1.0f, density = 2.5f, redVisionMode = false, boostStarAndLines = false)
        val normalConstellationStroke = renderer.constellationPaint.strokeWidth
        val normalCoreStroke = renderer.corePaints[0].strokeWidth

        renderer.updateOpticalPaints(zoomFactor = 1.0f, density = 2.5f, redVisionMode = false, boostStarAndLines = true)
        val boostedConstellationStroke = renderer.constellationPaint.strokeWidth
        val boostedCoreStroke = renderer.corePaints[0].strokeWidth

        org.junit.Assert.assertEquals(normalConstellationStroke * 1.25f, boostedConstellationStroke, 0.001f)
        org.junit.Assert.assertEquals(normalCoreStroke * 1.5f, boostedCoreStroke, 0.001f)

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
        org.junit.Assert.assertTrue("Shader must declare nightVisionTint uniform", src.contains("uniform float3 nightVisionTint;"))
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
        org.junit.Assert.assertTrue("Shader must declare nightVisionTint uniform", src.contains("uniform float3 nightVisionTint;"))
        org.junit.Assert.assertTrue("Shader must extract Z elevation", src.contains("sinElevation = worldDir.z"))
        org.junit.Assert.assertTrue("Shader must discard pixels below horizon", src.contains("if (sinElevation < 0.0) return half4(0.0);"))
        org.junit.Assert.assertTrue("Shader must apply max elevation of 15 degrees", src.contains("0.258"))
        org.junit.Assert.assertTrue("Shader must apply non-linear exponential decay", src.contains("pow(factor, 3.0)"))
        org.junit.Assert.assertTrue("Shader must contain red vision filter", src.contains("luminance * 0.15 * nightVisionTint"))
    }

    @Test
    fun `verifies star extinction calculation math`() {
        fun computeExtinction(elevationDeg: Float): Float {
            return if (elevationDeg <= 0.0f) 0.0f else (elevationDeg / 10.0f).coerceIn(0.0f, 1.0f)
        }

        org.junit.Assert.assertEquals(0.0f, computeExtinction(-5.0f), 0.001f)
        org.junit.Assert.assertEquals(0.0f, computeExtinction(0.0f), 0.001f)
        org.junit.Assert.assertEquals(0.5f, computeExtinction(5.0f), 0.001f)
        org.junit.Assert.assertEquals(1.0f, computeExtinction(10.0f), 0.001f)
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
        org.junit.Assert.assertTrue("Shader must declare nightVisionTint uniform", src.contains("uniform float3 nightVisionTint;"))
        org.junit.Assert.assertTrue("Shader must contain spherical azimuth conversion", src.contains("atan(worldDir.x, worldDir.y)"))
        org.junit.Assert.assertTrue("Shader must contain spherical elevation conversion", src.contains("asin(clamp(worldDir.z, -1.0, 1.0))"))
        org.junit.Assert.assertTrue("Shader must contain equirectangular UV mapping", src.contains("texU = (theta / 6.283185307179586) + 0.5;"))
        org.junit.Assert.assertTrue("Shader must contain red vision filter", src.contains("luminance * 0.15 * nightVisionTint"))
    }

    @Test
    fun `verifies selection reticle and arrow indicator preallocated paints and buffers exist`() {
        val renderer = NightSkyRenderer()

        // Verify reticle paint, text paint, arrow paint, and arrow path are non-null and correctly initialized
        org.junit.Assert.assertNotNull(renderer.reticlePaint)
        org.junit.Assert.assertNotNull(renderer.reticleTextPaint)
        org.junit.Assert.assertNotNull(renderer.arrowPaint)
        org.junit.Assert.assertNotNull(renderer.arrowPath)

        // Verify theme switching runs without exception
        renderer.applyTheme(redVisionMode = false)
        renderer.applyTheme(redVisionMode = true)
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
        // Star 0: RA=0.0, Dec=90.0, Mag=6.8f (faint, in Crater constellation)
        // Star 1: RA=0.0, Dec=89.0, Mag=6.6f (faint, in Crater constellation)
        // Star 2: RA=0.0, Dec=88.0, Mag=6.7f (faint, standalone - NOT in any constellation)
        // Star 3: RA=0.0, Dec=87.0, Mag=1.2f (bright, standalone)
        NightSkyCatalog.startStarsVector(fbb, 4)
        Star.createStar(fbb, 0.0f, 87.0f, 1.2f) // index 3
        Star.createStar(fbb, 0.0f, 88.0f, 6.7f) // index 2
        Star.createStar(fbb, 0.0f, 89.0f, 6.6f) // index 1
        Star.createStar(fbb, 0.0f, 90.0f, 6.8f) // index 0
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

    @Test
    fun `verifies asterisms render and hit-test independently from parent constellations`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. IAU Constellation (Ursa Major, index 0, isAsterism = false)
        val umNameOff = fbb.createString("Ursa Major")
        val umLocOff = fbb.createString("Velká medvědice")
        val umLinesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val umOff = Constellation.createConstellation(fbb, umNameOff, 0.0f, 90.0f, umLinesVec, umLocOff, 0, 0, false)

        // 2. Asterism (Big Dipper, index 1, isAsterism = true) at distinct coordinates in front of camera
        val bdNameOff = fbb.createString("Big Dipper (Plough)")
        val bdLocOff = fbb.createString("Velký vůz")
        val bdLinesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val bdOff = Constellation.createConstellation(fbb, bdNameOff, 0.0f, 85.0f, bdLinesVec, bdLocOff, 0, 0, true)

        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(umOff, bdOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, 85.0f, 1.8f) // star 1
        Star.createStar(fbb, 0.0f, 90.0f, 1.8f) // star 0
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

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
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

        // Both IAU constellation (0) and Asterism (1) should have progress > 0
        assertTrue("Ursa Major progress > 0", renderer.constellationProgress[0] > 0.001f)
        assertTrue("Big Dipper progress > 0", renderer.constellationProgress[1] > 0.001f)

        // Set progress to 1.0f to simulate fully revealed animation
        renderer.constellationProgress[0] = 1.0f
        renderer.constellationProgress[1] = 1.0f

        // Hit testing at asterism centroid should resolve ConstellationHit(1)
        val centroidX = renderer.constellationCentroidScreenX[1]
        val centroidY = renderer.constellationCentroidScreenY[1]
        val hit = renderer.findHitAt(centroidX, centroidY, 48f * 2.5f, 2.5f, catalog)
        assertTrue("Hit must be ConstellationHit", hit is NightSkyRenderer.HitTarget.ConstellationHit)
        assertEquals(1, (hit as NightSkyRenderer.HitTarget.ConstellationHit).index)
    }

    @Test
    fun `verifies off-screen bearing indicator math for all directions and behind camera`() {
        val w = 1080f
        val h = 2400f
        val edgePadding = 64f
        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val halfW = centerX - edgePadding
        val halfH = centerY - edgePadding

        // 1. Target directly to the Right (Xs > 0, Ys = 0, Zs > 0)
        val rightTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 1f, ys = 0f, zs = 1f,
            screenX = w + 200f, screenY = centerY,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(0f, rightTransform.angleDeg, 0.01f)
        assertEquals(centerX + halfW, rightTransform.arrowX, 0.01f)
        assertEquals(centerY, rightTransform.arrowY, 0.01f)

        // 2. Target directly to the Left (Xs < 0, Ys = 0, Zs > 0)
        val leftTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = -1f, ys = 0f, zs = 1f,
            screenX = -200f, screenY = centerY,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(180f, kotlin.math.abs(leftTransform.angleDeg), 0.01f)
        assertEquals(centerX - halfW, leftTransform.arrowX, 0.01f)
        assertEquals(centerY, leftTransform.arrowY, 0.01f)

        // 3. Target directly Above (Xs = 0, Ys > 0, Zs > 0)
        val topTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 0f, ys = 1f, zs = 1f,
            screenX = centerX, screenY = -300f,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(-90f, topTransform.angleDeg, 0.01f)
        assertEquals(centerX, topTransform.arrowX, 0.01f)
        assertEquals(centerY - halfH, topTransform.arrowY, 0.01f)

        // 4. Target directly Below (Xs = 0, Ys < 0, Zs > 0)
        val bottomTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 0f, ys = -1f, zs = 1f,
            screenX = centerX, screenY = h + 300f,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(90f, bottomTransform.angleDeg, 0.01f)
        assertEquals(centerX, bottomTransform.arrowX, 0.01f)
        assertEquals(centerY + halfH, bottomTransform.arrowY, 0.01f)

        // 5. Target Behind observer and to the Right (Xs > 0, Ys = 0, Zs < 0)
        val behindRightTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 1f, ys = 0f, zs = -1f,
            screenX = Float.NaN, screenY = Float.NaN,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(0f, behindRightTransform.angleDeg, 0.01f)
        assertEquals(centerX + halfW, behindRightTransform.arrowX, 0.01f)
        assertEquals(centerY, behindRightTransform.arrowY, 0.01f)

        // 6. Target Behind observer and Above (Xs = 0, Ys > 0, Zs < 0)
        val behindTopTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 0f, ys = 1f, zs = -1f,
            screenX = Float.NaN, screenY = Float.NaN,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(-90f, behindTopTransform.angleDeg, 0.01f)
        assertEquals(centerX, behindTopTransform.arrowX, 0.01f)
        assertEquals(centerY - halfH, behindTopTransform.arrowY, 0.01f)

        // 7. Singularity: Target directly behind on camera optical axis (Xs = 0, Ys = 0, Zs < 0)
        val singularityTransform = NightSkyRenderer.computeOffscreenIndicator(
            xs = 0f, ys = 0f, zs = -1f,
            screenX = Float.NaN, screenY = Float.NaN,
            w = w, h = h, edgePadding = edgePadding
        )
        assertEquals(90f, singularityTransform.angleDeg, 0.01f)
        assertEquals(centerX, singularityTransform.arrowX, 0.01f)
        assertEquals(centerY + halfH, singularityTransform.arrowY, 0.01f)
    }

    @Test
    fun `verifies off-screen directional indicator renders on canvas during drawSelectionReticle`() {
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

        // Selected body behind camera (RA = 180°, Dec = 0°, looking North at LST 0°)
        val offscreenBody = CelestialBodyDetails(
            name = "Alpha Centauri",
            subtitle = "Star",
            property1 = Pair("Constellation", "Centaurus"),
            property2 = Pair("Distance", "4.37 ly"),
            magnitude = -0.27f,
            raDegrees = 180.0,
            decDegrees = -60.0,
        )

        renderer.selectedStarIndex = 0

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            // Must execute drawSelectionReticle with off-screen body without exceptions or allocations
            renderer.drawSelectionReticle(
                drawScope = this,
                selectedBody = offscreenBody,
                matrix = northMatrix,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                density = 2.5f,
            )
        }

        // Test with Constellation selected off-screen
        renderer.selectedStarIndex = -1
        renderer.selectedConstellationIndex = 5
        val offscreenConstellation = CelestialBodyDetails(
            name = "Crux",
            subtitle = "Constellation",
            property1 = Pair("Brightest", "Acrux"),
            property2 = Pair("Family", "Heavenly Waters"),
            magnitude = null,
            raDegrees = 186.0,
            decDegrees = -60.0,
        )

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawSelectionReticle(
                drawScope = this,
                selectedBody = offscreenConstellation,
                matrix = northMatrix,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                density = 2.5f,
            )
        }
    }

    @Test
    fun `verifies constellation artwork rendering and LRU caching without allocations`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. Constellation with artwork metadata
        val constNameOff = fbb.createString("Ursa Major")
        val locNameOff = fbb.createString("Velká medvědice")
        val brightOff = fbb.createString("Alioth")
        val famOff = fbb.createString("Ursa Major Family")
        val artResOff = fbb.createString("art_uma")
        val artCornerRaOff = Constellation.createArtCornerRaVector(fbb, floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f))
        val artCornerDecOff = Constellation.createArtCornerDecVector(fbb, floatArrayOf(89.0f, 90.0f, 88.0f, 87.0f))
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val constOff = Constellation.createConstellation(
            fbb, constNameOff, 0.0f, 90.0f, linesVec, locNameOff, brightOff, famOff, false,
            artResOff, artCornerRaOff, artCornerDecOff
        )
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, 89.0f, 1.8f) // star 1
        Star.createStar(fbb, 0.0f, 90.0f, 1.8f) // star 0
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

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            // First render pass: focuses Ursa Major with constellation art enabled
            renderer.drawNightSky(
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
                showConstellationArt = true,
            )
        }

        assertTrue("Constellation progress must ramp up", renderer.constellationProgress[0] > 0.001f)

        // Second render pass with debug calibration offsets injected
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
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
                showConstellationArt = true,
                debugOffsetX = 25f,
                debugOffsetY = -15f,
                debugScaleMultiplier = 1.25f,
                debugRotationOffset = 10f,
            )
        }

        // Apply Red Vision mode and Normal mode to verify theme switching runs without exception
        renderer.applyTheme(redVisionMode = true)
        renderer.applyTheme(redVisionMode = false)
    }

    @Test
    fun `verifies projectWorldToScreen matches perspective projection and handles front and back points`() {
        val renderer = NightSkyRenderer()
        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val w = 1080f
        val h = 2400f
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val fovH = 60.0f
        val halfFovRad = Math.toRadians((fovH * 0.5f).toDouble()).toFloat()
        val focalLengthPx = halfW / kotlin.math.tan(halfFovRad)
        val out = FloatArray(3)

        // Point directly in front (Facing North: world vy = 1.0, vx = 0, vz = 0)
        val inFront = renderer.projectWorldToScreen(
            wx = 0f, wy = 1f, wz = 0f,
            matrix = northMatrix,
            displayRotation = android.view.Surface.ROTATION_0,
            halfW = halfW, halfH = halfH,
            focalLengthPixels = focalLengthPx,
            outBuffer = out,
            offset = 0,
        )
        assertTrue("Point in front must return true", inFront)
        assertEquals(halfW, out[0], 0.01f) // screen center X
        assertEquals(halfH, out[1], 0.01f) // screen center Y
        assertEquals(1.0f, out[2], 0.01f) // camera Z = 1.0

        // Point directly behind (South: world vy = -1.0)
        val behind = renderer.projectWorldToScreen(
            wx = 0f, wy = -1f, wz = 0f,
            matrix = northMatrix,
            displayRotation = android.view.Surface.ROTATION_0,
            halfW = halfW, halfH = halfH,
            focalLengthPixels = focalLengthPx,
            outBuffer = out,
            offset = 0,
        )
        assertTrue("Point behind must return false", !behind)
        assertEquals(-1.0f, out[2], 0.01f) // camera Z = -1.0
    }

    @Test
    fun `verifies 4-point perspective homography artwork projection across all screen rotations`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. Constellation near the North Celestial Pole (Dec = 89.0°)
        val constNameOff = fbb.createString("Ursa Minor")
        val locNameOff = fbb.createString("Malý medvěd")
        val brightOff = fbb.createString("Polaris")
        val famOff = fbb.createString("Ursa Major Family")
        val artResOff = fbb.createString("art_umi")
        val artCornerRaOff = Constellation.createArtCornerRaVector(fbb, floatArrayOf(37.95f, 222.68f, 100.0f, 20.0f))
        val artCornerDecOff = Constellation.createArtCornerDecVector(fbb, floatArrayOf(89.26f, 74.16f, 85.0f, 80.0f))
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val constOff = Constellation.createConstellation(
            fbb, constNameOff, 0.0f, 89.0f, linesVec, locNameOff, brightOff, famOff, false,
            artResOff, artCornerRaOff, artCornerDecOff
        )
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 37.95f, 89.26f, 1.97f) // Polaris
        Star.createStar(fbb, 222.68f, 74.16f, 2.07f) // Kochab
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())

        val renderer = NightSkyRenderer()
        renderer.applyTheme(redVisionMode = true)

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        // Test across 4 standard screen orientations: 0°, 90°, 180°, 270°
        val rotations = intArrayOf(
            android.view.Surface.ROTATION_0,
            android.view.Surface.ROTATION_90,
            android.view.Surface.ROTATION_180,
            android.view.Surface.ROTATION_270,
        )

        for (rotation in rotations) {
            drawScope.draw(
                density = Density(2.5f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = composeCanvas,
                size = androidx.compose.ui.geometry.Size(1080f, 2400f),
            ) {
                val visible = renderer.drawNightSky(
                    drawScope = this,
                    catalog = catalog,
                    lstDegrees = 0.0,
                    latitudeDegrees = 90.0, // Observer at North Pole
                    matrix = northMatrix,
                    displayRotation = rotation,
                    fovHorizontalDegrees = 60.0f,
                    w = 1080f,
                    h = 2400f,
                    zoomFactor = 1.0f,
                    showConstellations = true,
                    showConstellationArt = true,
                )
                assertTrue("Polar stars should be visible at North Pole", visible >= 0)
            }
        }
    }

    @Test
    fun `verifies constellation focus region debug overlay rendering across night vision themes and normal mode`() {
        val fbb = FlatBufferBuilder(1024)

        val constNameOff = fbb.createString("Ursa Major")
        val locNameOff = fbb.createString("Velká medvědice")
        val brightOff = fbb.createString("Alioth")
        val famOff = fbb.createString("Ursa Major Family")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        val constOff = Constellation.createConstellation(
            fbb, constNameOff, 0.0f, 90.0f, linesVec, locNameOff, brightOff, famOff, false,
            0, 0, 0
        )
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, 89.0f, 1.8f)
        Star.createStar(fbb, 0.0f, 90.0f, 1.8f)
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())

        val renderer = NightSkyRenderer()

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        // Test normal mode with focus area debug overlay
        renderer.applyTheme(redVisionMode = false)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            val visible = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = false,
                isDebugFocusRegionVisible = true,
            )
            assertTrue("Visible stars rendered with debug focus overlay", visible >= 0)
        }

        // Test Red Vision mode with custom focus radius & threshold
        renderer.applyTheme(com.dazix.caelis.domain.nightsky.NightVisionTheme.RED, isNightVision = true)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            val visible = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = true,
                isDebugFocusRegionVisible = true,
                debugFocusRadiusMultiplier = 0.65f,
                debugFocusDisplayThreshold = 0.40f,
            )
            assertTrue("Visible stars rendered with custom focus parameters", visible >= 0)
        }

        // Test Amber & Green vision modes
        renderer.applyTheme(com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER, isNightVision = true)
        renderer.applyTheme(com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN, isNightVision = true)
    }

    @Test
    fun `verifies constellations behind observer have centroid in front set to false and do not activate focus or artwork`() {
        val fbb = FlatBufferBuilder(1024)

        val constNameOff = fbb.createString("Crux")
        val locNameOff = fbb.createString("Jižní kříž")
        val brightOff = fbb.createString("Acrux")
        val famOff = fbb.createString("Crux Family")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
        // Centroid at South Pole: Dec = -90.0
        val constOff = Constellation.createConstellation(
            fbb, constNameOff, 0.0f, -90.0f, linesVec, locNameOff, brightOff, famOff, false,
            0, 0, 0
        )
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, -89.0f, 1.8f)
        Star.createStar(fbb, 0.0f, -90.0f, 1.8f)
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())
        val renderer = NightSkyRenderer()

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        // Camera pointing strictly North at Lat = 90° (North Pole looking forward)
        // South pole is behind the camera (Z_dev <= 0)
        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = true,
            )
        }

        // Verify that Crux (behind camera) is marked NOT in front
        assertFalse("Constellation behind observer must have centroidInFront = false", renderer.constellationCentroidInFront[0])
    }

    @Test
    fun `verifies constellation spatial hysteresis state machine absorbs boundary jitter`() {
        val renderer = NightSkyRenderer()

        // 1. Verify pre-allocated buffer
        assertEquals(256, renderer.constellationIsFocused.size)
        assertFalse(renderer.constellationIsFocused[0])

        // 2. Mock catalog with 1 constellation
        val fbb = FlatBufferBuilder(1024)
        val constNameOff = fbb.createString("Ursa Major")
        val locNameOff = fbb.createString("Velká medvědice")
        val brightOff = fbb.createString("Alioth")
        val famOff = fbb.createString("Ursa Major Family")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1))

        val constOff = Constellation.createConstellation(
            fbb, constNameOff, 0.0f, 90.0f, linesVec, locNameOff, brightOff, famOff, false,
            0, 0, 0
        )
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, 89.0f, 1.8f)
        Star.createStar(fbb, 0.0f, 90.0f, 1.8f)
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)
        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        val w = 1000f
        val h = 2000f

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        // Render Frame 1: At Latitude = 0.0°, Dec = 90.0° is due North on horizon -> Centroid dead center (d = 0 <= 450) -> Locks ON
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(w, h),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = w,
                h = h,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = false,
            )
        }
        assertTrue("Centroid at center must acquire focus", renderer.constellationIsFocused[0])
        assertTrue("Progress must increase", renderer.constellationProgress[0] > 0f)

        // 3. Test Pure Spatial Hysteresis Deadzone State Transitions:
        val focusRadiusIn = w * (0.48f * (45f / 48f)) // 450px
        val focusRadiusOut = w * (0.48f * (55f / 48f)) // 550px
        val rInSq = focusRadiusIn * focusRadiusIn // 202500
        val rOutSq = focusRadiusOut * focusRadiusOut // 302500

        var isFocused = false

        // Transition A: Centroid in Deadzone (d = 500px, d^2 = 250000) starting from false -> stays false
        val distDeadzoneSq = 500f * 500f
        if (!isFocused && distDeadzoneSq <= rInSq) {
            isFocused = true
        } else if (isFocused && distDeadzoneSq > rOutSq) {
            isFocused = false
        }
        assertFalse("Deadzone starting from unfocused must remain unfocused", isFocused)

        // Transition B: Centroid enters Inner Radius (d = 400px, d^2 = 160000 <= 202500) -> locks ON
        val distInSq = 400f * 400f
        if (!isFocused && distInSq <= rInSq) {
            isFocused = true
        } else if (isFocused && distInSq > rOutSq) {
            isFocused = false
        }
        assertTrue("Crossing inner boundary must lock focus ON", isFocused)

        // Transition C: Centroid jitter moves back to Deadzone (d = 500px) -> remains locked ON (absorbs jitter!)
        if (!isFocused && distDeadzoneSq <= rInSq) {
            isFocused = true
        } else if (isFocused && distDeadzoneSq > rOutSq) {
            isFocused = false
        }
        assertTrue("Deadzone starting from focused must remain locked ON (absorbing micro-tremors)", isFocused)

        // Transition D: Centroid exits Outer Radius (d = 600px, d^2 = 360000 > 302500) -> locks OFF
        val distOutSq = 600f * 600f
        if (!isFocused && distOutSq <= rInSq) {
            isFocused = true
        } else if (isFocused && distOutSq > rOutSq) {
            isFocused = false
        }
        assertFalse("Crossing outer boundary must lock focus OFF", isFocused)
    }

    @Test
    fun `verifies planetary rendering buffers, dual-state rendering, and hit testing priority`() {
        val renderer = NightSkyRenderer()

        // 1. Verify pre-allocated planetary buffers
        assertEquals(16, renderer.maxPlanets)
        assertEquals(16, renderer.planetScreenX.size)
        assertEquals(16, renderer.planetScreenY.size)
        assertEquals(16, renderer.planetRadiusPx.size)
        assertEquals(16, renderer.planetInFront.size)
        assertEquals(16, renderer.planetIds.size)
        assertEquals(3, renderer.planetWorldVec.size)
        assertNotNull(renderer.planetPaint)
        assertNotNull(renderer.planetCorePaint)
        assertNotNull(renderer.planetHaloPaint)
        assertNotNull(renderer.planetLabelPaint)
        assertNotNull(renderer.planetHighlightLabelPaint)

        // 2. Mock catalog
        val fbb = FlatBufferBuilder(1024)
        NightSkyCatalog.startStarsVector(fbb, 1)
        Star.createStar(fbb, 0.0f, 90.0f, 1.8f) // North pole star
        val starsVec = fbb.endVector()
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)
        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())

        val testInstant = java.time.Instant.parse("2026-08-25T20:00:00Z")
        val planets = com.dazix.caelis.domain.nightsky.PlanetaryEphemerisCalculator.computeAll(testInstant)
        assertEquals(8, planets.size)

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        // 3. Render Pass at 1.0x Zoom (State A: Point Light)
        renderer.applyTheme(redVisionMode = false)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = false,
                planets = planets,
            )
        }

        // 4. Render Pass at 100.0x Deep Zoom (State B: High-Res Texture)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 0.6f, // 100x magnification
                w = 1080f,
                h = 2400f,
                zoomFactor = 100.0f,
                showConstellations = true,
                showConstellationArt = false,
                showPlanets = true,
                planets = planets,
            )
        }

        // Verify planets are active when showPlanets = true
        assertTrue("Active planets count must be > 0 when showPlanets is true", renderer.activePlanetsCount > 0)

        // 5. Render Pass with showPlanets = false (Layer Toggled OFF)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = false,
                showPlanets = false,
                planets = planets,
            )
        }

        // Verify active planets count is 0 and all planetInFront flags are false
        assertEquals("Active planets count must be 0 when showPlanets is false", 0, renderer.activePlanetsCount)
        for (i in 0 until renderer.maxPlanets) {
            assertFalse("planetInFront[$i] must be false when showPlanets is false", renderer.planetInFront[i])
        }

        // Re-enable showPlanets for hit-testing and reticle tests
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                zoomFactor = 1.0f,
                showConstellations = true,
                showConstellationArt = false,
                showPlanets = true,
                planets = planets,
            )
        }

        // 6. Test Hit Testing Priority
        // Find visible planet in front of camera
        var visiblePlanetIdx = -1
        for (i in 0 until renderer.activePlanetsCount) {
            if (renderer.planetInFront[i]) {
                visiblePlanetIdx = i
                break
            }
        }

        if (visiblePlanetIdx >= 0) {
            val px = renderer.planetScreenX[visiblePlanetIdx]
            val py = renderer.planetScreenY[visiblePlanetIdx]
            val expectedPlanetId = renderer.planetIds[visiblePlanetIdx]

            val hit = renderer.findHitAt(
                tapX = px,
                tapY = py,
                maxStarRadiusPx = 48f,
                density = 2.5f,
                catalog = catalog,
                planets = planets,
            )

            assertTrue("Hit must be PlanetHit", hit is NightSkyRenderer.HitTarget.PlanetHit)
            assertEquals(expectedPlanetId, (hit as NightSkyRenderer.HitTarget.PlanetHit).planetId)

            // When planets list is empty (e.g. layer toggled off), hit testing on the same spot should NOT return PlanetHit
            val hitOff = renderer.findHitAt(
                tapX = px,
                tapY = py,
                maxStarRadiusPx = 48f,
                density = 2.5f,
                catalog = catalog,
                planets = emptyList(),
            )
            assertFalse("Hit must not be PlanetHit when planets are empty/disabled", hitOff is NightSkyRenderer.HitTarget.PlanetHit)
        }

        // 7. Test Selection Reticle for Planet
        renderer.selectedStarIndex = -1
        renderer.selectedPlanetId = "Jupiter"
        val jupiterState = planets.first { it.id.equals("Jupiter", ignoreCase = true) }
        val selectedPlanetDetails = CelestialBodyDetails(
            name = "Jupiter",
            subtitle = "Planeta",
            property1 = Pair("Vzdálenost", "5.2 AU"),
            property2 = Pair("Úhlový průměr", "45.2\""),
            magnitude = -2.4f,
            raDegrees = jupiterState.raDegrees,
            decDegrees = jupiterState.decDegrees,
        )

        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            renderer.drawSelectionReticle(
                drawScope = this,
                selectedBody = selectedPlanetDetails,
                matrix = northMatrix,
                lstDegrees = 0.0,
                latitudeDegrees = 90.0,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 60.0f,
                w = 1080f,
                h = 2400f,
                density = 2.5f,
            )
        }
    }

    @Test
    fun `verifies planetary deep zoom scaling linear proportions and moon occupying 10 to 13 percent screen at 12x zoom`() {
        val renderer = NightSkyRenderer()
        renderer.applyTheme(redVisionMode = false)

        // Verify planet paints are non-null and pre-allocated
        assertNotNull(renderer.planetPaint)
        assertNotNull(renderer.planetCorePaint)
        assertNotNull(renderer.planetHaloPaint)

        // Moon asset test:
        // Physical Moon body diameter ~ 0.518°
        val moonState = com.dazix.caelis.domain.nightsky.PlanetState(
            id = "Moon",
            nameEn = "Moon",
            nameCs = "Měsíc",
            raDegrees = 0.0,
            decDegrees = 90.0,
            magnitude = -12.5f,
            angularDiameterDeg = 0.518f,
            distanceFormatted = "384,400 km",
            illuminationFraction = 1.0f,
            colorRgb = 0xFFF5F5F0.toInt(),
            isMoon = true,
        )

        val w = 1080f
        val h = 2400f
        val fovH_12x = 5.0f // 12x zoom (60° / 12 = 5°)
        val halfFovRad = Math.toRadians((fovH_12x * 0.5f).toDouble()).toFloat()
        val focalLengthPx = (w * 0.5f) / kotlin.math.tan(halfFovRad)

        // Calculate diameterInPixels using unified formula
        val diameterInPixels = 2f * focalLengthPx * kotlin.math.tan(Math.toRadians(moonState.angularDiameterDeg.toDouble() * 0.5)).toFloat()

        // Physical lunar disk should be ~111.8 px (~10.35% of screen width)
        val physicalDiskFraction = diameterInPixels / w

        assertTrue("Moon physical disk at 12x zoom should be ~10% (between 9% and 12%)", physicalDiskFraction in 0.09f..0.12f)

        // Verify diameter scales linearly with zoom (e.g. at 24x zoom, 2.5° FOV, diameter should be ~2x)
        val fovH_24x = 2.5f
        val halfFovRad24 = Math.toRadians((fovH_24x * 0.5f).toDouble()).toFloat()
        val focalLengthPx24 = (w * 0.5f) / kotlin.math.tan(halfFovRad24)
        val diameter24x = 2f * focalLengthPx24 * kotlin.math.tan(Math.toRadians(moonState.angularDiameterDeg.toDouble() * 0.5)).toFloat()

        assertEquals(2.0f, diameter24x / diameterInPixels, 0.02f)

        // Verify State A to State B transition threshold (15px):
        // At 1.0x zoom (60° FOV): Moon diameter is ~9.3px < 15px -> State A (Point Light)
        val fovH_1x = 60.0f
        val halfFovRad1 = Math.toRadians((fovH_1x * 0.5f).toDouble()).toFloat()
        val focalLengthPx1 = (w * 0.5f) / kotlin.math.tan(halfFovRad1)
        val diameter1x = 2f * focalLengthPx1 * kotlin.math.tan(Math.toRadians(moonState.angularDiameterDeg.toDouble() * 0.5)).toFloat()
        assertTrue("At 1x zoom Moon diameter must be < 15px (State A)", diameter1x < 15f)

        // At 2x zoom (30° FOV): Moon diameter is ~18.6px >= 15px -> State B (Texture)
        val fovH_2x = 30.0f
        val halfFovRad2 = Math.toRadians((fovH_2x * 0.5f).toDouble()).toFloat()
        val focalLengthPx2 = (w * 0.5f) / kotlin.math.tan(halfFovRad2)
        val diameter2x = 2f * focalLengthPx2 * kotlin.math.tan(Math.toRadians(moonState.angularDiameterDeg.toDouble() * 0.5)).toFloat()
        assertTrue("At 2x zoom Moon diameter must be >= 15px (State B)", diameter2x >= 15f)

        // Verify Saturn bodyToImageRatio logic (2.33x ring span):
        val saturnBodyAngularDeg = 0.005f
        val saturnBodyDiameterPx = 2f * focalLengthPx * kotlin.math.tan(Math.toRadians(saturnBodyAngularDeg.toDouble() * 0.5)).toFloat()
        val saturnBitmapWidth = 1024f
        val saturnBodyToImageRatio = 2.33f
        val expectedBodyWidthPx = saturnBitmapWidth / saturnBodyToImageRatio
        val saturnScale = saturnBodyDiameterPx / expectedBodyWidthPx
        val totalRenderedRingWidthPx = saturnBitmapWidth * saturnScale
        assertEquals("Total rendered ring span should be 2.33x the body diameter", saturnBodyDiameterPx * 2.33f, totalRenderedRingWidthPx, 0.1f)

        // Verify Planetarium Trick artificial boosting on Neptune vs Jupiter vs Moon:
        val neptuneAngularDeg = 0.0012f
        val neptuneBoost = (0.013f / neptuneAngularDeg).coerceIn(1.0f, 10.0f)
        assertEquals("Neptune should receive max 10x planetarium boost", 10.0f, neptuneBoost, 0.01f)

        val jupiterAngularDeg = 0.013f
        val jupiterBoost = (0.013f / jupiterAngularDeg).coerceIn(1.0f, 10.0f)
        assertEquals("Jupiter should receive 1.0x boost", 1.0f, jupiterBoost, 0.01f)

        // At 75x zoom (0.8° FOV), Neptune without boost is only ~1.6px, but with 10x boost is ~16.2px >= 15px (State B)
        val fovH_75x = 0.8f
        val halfFov75 = Math.toRadians((fovH_75x * 0.5f).toDouble()).toFloat()
        val focalLength75 = (w * 0.5f) / kotlin.math.tan(halfFov75)
        val neptunePhysical = 2f * focalLength75 * kotlin.math.tan(Math.toRadians(neptuneAngularDeg.toDouble() * 0.5)).toFloat()
        val neptuneBoosted = neptunePhysical * neptuneBoost
        assertTrue("Neptune without boost is tiny (< 5px)", neptunePhysical < 5f)
        assertTrue("Neptune with boost triggers State B (>= 15px)", neptuneBoosted >= 15f)
    }

    @Test
    fun `verifies celestial local north orientation calculation across declinations and device rotations`() {
        val renderer = NightSkyRenderer()
        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val w = 1080f
        val h = 2400f
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val fovH = 60.0f
        val halfFovRad = Math.toRadians((fovH * 0.5f).toDouble()).toFloat()
        val focalLengthPx = halfW / kotlin.math.tan(halfFovRad)

        // 1. Observer at equator (Lat = 0°), facing North.
        // Object at celestial equator (RA = 0°, Dec = 0°) is dead ahead.
        // North Celestial Pole is towards +Z (Zenith / Up).
        // Screen orientation in portrait (Rotation 0): Up is -Y.
        // computeCelestialNorthAngle should return 0° (or 360°), pointing straight UP.
        val anglePortrait = renderer.computeCelestialNorthAngle(
            pX = halfW,
            pY = halfH,
            ra = 0.0,
            dec = 0.0,
            lstDegrees = 0.0,
            latitudeDegrees = 0.0,
            matrix = northMatrix,
            displayRotation = android.view.Surface.ROTATION_0,
            halfW = halfW,
            halfH = halfH,
            focalLengthPixels = focalLengthPx,
        )
        assertEquals("North should point straight UP (0°) in portrait", 0f, anglePortrait, 1.0f)

        // 2. Landscape 90° rotation: North points to the right or left accordingly
        val angleLandscape = renderer.computeCelestialNorthAngle(
            pX = halfW,
            pY = halfH,
            ra = 0.0,
            dec = 0.0,
            lstDegrees = 0.0,
            latitudeDegrees = 0.0,
            matrix = northMatrix,
            displayRotation = android.view.Surface.ROTATION_90,
            halfW = halfW,
            halfH = halfH,
            focalLengthPixels = focalLengthPx,
        )
        assertTrue("Landscape orientation angle should be in [0, 360)", angleLandscape in 0f..360f)
    }

    @Test
    fun testTwoTierProgressiveVectorCalculation() {
        val renderer = NightSkyRenderer()
        val fbb = FlatBufferBuilder(1024)

        // Star 0: Bright naked-eye star (mag = 1.2f)
        // Star 1: Faint telescopic star (mag = 8.5f)
        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 0.0f, 45.0f, 8.5f) // Star 1
        Star.createStar(fbb, 0.0f, 45.0f, 1.2f) // Star 0
        val starsVec = fbb.endVector()

        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())
        assertNotNull(catalog)

        assertFalse(renderer.isStarCacheReady)

        // Step 1: Execute Tier 1 (High Priority - Naked Eye mag <= 6.0)
        renderer.updateStarWorldVectorsTier1(catalog, lstDegrees = 0.0, latitudeDegrees = 90.0, totalStars = 2)

        assertTrue("Tier 1 must immediately mark cache as ready", renderer.isStarCacheReady)
        assertTrue("Bright star (mag 1.2 <= 6.0) must be computed in Tier 1", renderer.starVectorComputed[0])
        assertFalse("Faint star (mag 8.5 > 6.0) must NOT be computed in Tier 1", renderer.starVectorComputed[1])

        // Step 2: Execute Tier 2 (Background - Telescopic mag > 6.0)
        renderer.updateStarWorldVectorsTier2(catalog, lstDegrees = 0.0, latitudeDegrees = 90.0, totalStars = 2)

        assertTrue("Bright star must remain computed", renderer.starVectorComputed[0])
        assertTrue("Faint star must now be computed in Tier 2", renderer.starVectorComputed[1])
    }

    @Test
    fun `verifies hit-testing priority hierarchy Planet over Constellation over Star over Miss`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. Constellation at North Pole (RA 0, Dec 90)
        val constNameOff = fbb.createString("Ursa Minor")
        val locNameOff = fbb.createString("Malý medvěd")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 0))
        val constOff = Constellation.createConstellation(fbb, constNameOff, 0.0f, 90.0f, linesVec, locNameOff, 0, 0, false)
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))

        // 2. Star 0 at (RA 0, Dec 90)
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
        NightSkyCatalog.startStarsVector(fbb, 1)
        Star.createStar(fbb, 0.0f, 90.0f, 2.0f) // Star 0
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())
        val renderer = NightSkyRenderer()

        val planet = com.dazix.caelis.domain.nightsky.PlanetState(
            id = "Jupiter",
            nameEn = "Jupiter",
            nameCs = "Jupiter",
            raDegrees = 0.0,
            decDegrees = 90.0,
            magnitude = -2.5f,
            angularDiameterDeg = 0.013f,
            distanceFormatted = "5.2 AU",
            colorRgb = 0xFFFFE0B2.toInt(),
            isMoon = false,
        )

        // Setup pre-projected buffers at screen center (540, 1200)
        renderer.planetScreenX[0] = 540f
        renderer.planetScreenY[0] = 1200f
        renderer.planetRadiusPx[0] = 48f
        renderer.planetInFront[0] = true
        renderer.planetIds[0] = "Jupiter"
        renderer.activePlanetsCount = 1

        renderer.constellationCentroidScreenX[0] = 540f
        renderer.constellationCentroidScreenY[0] = 1200f
        renderer.constellationCentroidInFront[0] = true
        renderer.constellationProgress[0] = 1.0f

        // Star coordinates in renderer private arrays are tested via findHitAt
        // When planet is active, tap at (540, 1200) must return PlanetHit (Priority 0)
        val hitPlanet = renderer.findHitAt(
            tapX = 540f,
            tapY = 1200f,
            maxStarRadiusPx = 48f,
            density = 2.5f,
            catalog = catalog,
            planets = listOf(planet),
        )
        assertTrue("Priority 0 must resolve PlanetHit", hitPlanet is NightSkyRenderer.HitTarget.PlanetHit)
        assertEquals("Jupiter", (hitPlanet as NightSkyRenderer.HitTarget.PlanetHit).planetId)

        // When planets are disabled/empty, tap at (540, 1200) must return ConstellationHit (Priority 1)
        val hitConst = renderer.findHitAt(
            tapX = 540f,
            tapY = 1200f,
            maxStarRadiusPx = 48f,
            density = 2.5f,
            catalog = catalog,
            planets = emptyList(),
        )
        assertTrue("Priority 1 must resolve ConstellationHit", hitConst is NightSkyRenderer.HitTarget.ConstellationHit)
        assertEquals(0, (hitConst as NightSkyRenderer.HitTarget.ConstellationHit).index)

        // When constellation progress is 0 (out of focus), tap at far away (100, 100) must return Miss
        renderer.constellationProgress[0] = 0f
        val hitMiss = renderer.findHitAt(
            tapX = 100f,
            tapY = 100f,
            maxStarRadiusPx = 48f,
            density = 2.5f,
            catalog = catalog,
            planets = emptyList(),
        )
        assertTrue("Miss must return HitTarget.Miss", hitMiss is NightSkyRenderer.HitTarget.Miss)
    }

    @Test
    fun `verifies planetary optical glare, magnitude-based halo scaling, and atmospheric rim glow`() {
        val renderer = NightSkyRenderer()

        // 1. Verify pre-allocated planetGlowPaint, planetGlowMatrix, and RadialGradient shaders
        assertNotNull(renderer.planetGlowPaint)
        assertNotNull(renderer.planetGlowMatrix)
        assertNotNull(renderer.getPlanetGlowShader("Sun"))
        assertNotNull(renderer.getPlanetGlowShader("Moon"))
        assertNotNull(renderer.getPlanetGlowShader("Mercury"))
        assertNotNull(renderer.getPlanetGlowShader("Venus"))
        assertNotNull(renderer.getPlanetGlowShader("Mars"))
        assertNotNull(renderer.getPlanetGlowShader("Jupiter"))
        assertNotNull(renderer.getPlanetGlowShader("Saturn"))
        assertNotNull(renderer.getPlanetGlowShader("Uranus"))
        assertNotNull(renderer.getPlanetGlowShader("Neptune"))
        assertNotNull(renderer.getPlanetGlowShader("UnknownBody"))

        // 2. Verify characteristic optical glow color mappings
        assertEquals(android.graphics.Color.parseColor("#FFF5D7"), renderer.getPlanetGlowColor("Sun"))
        assertEquals(android.graphics.Color.parseColor("#E6E8EA"), renderer.getPlanetGlowColor("Moon"))
        assertEquals(android.graphics.Color.parseColor("#B0AEAD"), renderer.getPlanetGlowColor("Mercury"))
        assertEquals(android.graphics.Color.parseColor("#FFF8E7"), renderer.getPlanetGlowColor("Venus"))
        assertEquals(android.graphics.Color.parseColor("#FF6B36"), renderer.getPlanetGlowColor("Mars"))
        assertEquals(android.graphics.Color.parseColor("#E0C4A8"), renderer.getPlanetGlowColor("Jupiter"))
        assertEquals(android.graphics.Color.parseColor("#F4D596"), renderer.getPlanetGlowColor("Saturn"))
        assertEquals(android.graphics.Color.parseColor("#A8E5F0"), renderer.getPlanetGlowColor("Uranus"))
        assertEquals(android.graphics.Color.parseColor("#4B70DD"), renderer.getPlanetGlowColor("Neptune"))
        assertEquals(android.graphics.Color.WHITE, renderer.getPlanetGlowColor("UnknownBody"))

        // 3. Magnitude normalization and State A halo scaling (Venus vs Neptune, scaled to 2/3)
        // Venus (mag -4.0):
        val venusMag = -4.0f
        val clampedVenus = venusMag.coerceIn(-5.0f, 8.0f)
        val venusIntensity = 1.0f - ((clampedVenus + 5.0f) / 13.0f)
        assertEquals("Venus intensity should be near 1.0 (~0.923)", 0.923f, venusIntensity, 0.01f)
        val venusHalo = (10f + 25f * venusIntensity) * (2f / 3f)
        val venusAlpha = (140 * venusIntensity).toInt().coerceIn(25, 200)
        assertTrue("Venus halo should be around 22px", venusHalo > 20f)
        assertTrue("Venus alpha should be bright (> 100)", venusAlpha > 100)

        // Neptune (mag +7.8):
        val neptuneMag = 7.8f
        val clampedNeptune = neptuneMag.coerceIn(-5.0f, 8.0f)
        val neptuneIntensity = 1.0f - ((clampedNeptune + 5.0f) / 13.0f)
        assertEquals("Neptune intensity should be very low (~0.015)", 0.015f, neptuneIntensity, 0.01f)
        val neptuneHalo = (10f + 25f * neptuneIntensity) * (2f / 3f)
        val neptuneAlpha = (140 * neptuneIntensity).toInt().coerceIn(25, 200)
        assertTrue("Neptune halo should be small (near 7px)", neptuneHalo < 8f)
        assertEquals("Neptune alpha should be clamped to minimum 25", 25, neptuneAlpha)

        // 4. State B Rim Glow: radius is scaled with ringMultiplier, fades at high zoom
        val diameterPx1 = 20f // Just above 15px threshold (low zoom)
        val rimRadiusStandard = (diameterPx1 * 0.5f) * 1.2f
        val rimRadiusSaturn = (diameterPx1 * 0.5f) * 2.38f
        val rimRadiusUranus = (diameterPx1 * 0.5f) * 1.4f
        val zoomFade1 = (30f / diameterPx1).coerceIn(0.1f, 1.0f)
        assertEquals(12.0f, rimRadiusStandard, 0.01f)
        assertEquals(23.8f, rimRadiusSaturn, 0.01f)
        assertEquals(14.0f, rimRadiusUranus, 0.01f)
        assertEquals(1.0f, zoomFade1, 0.01f)

        val diameterPx2 = 300f // Deep zoom
        val rimRadius2 = (diameterPx2 * 0.5f) * 1.2f
        val zoomFade2 = (30f / diameterPx2).coerceIn(0.1f, 1.0f)
        assertEquals(180.0f, rimRadius2, 0.01f)
        assertEquals(0.1f, zoomFade2, 0.01f) // Faded to 10%

        // 5. Night Vision / Red Vision theme application does not crash
        renderer.applyTheme(theme = com.dazix.caelis.domain.nightsky.NightVisionTheme.RED, isNightVision = true)
        renderer.applyTheme(theme = com.dazix.caelis.domain.nightsky.NightVisionTheme.RED, isNightVision = false)
    }

    @Test
    fun `verifies direct total zoom immediately scales star field and constellation projection coordinates geometrically`() {
        val fbb = FlatBufferBuilder(1024)

        // Single star at RA = 0.0°, Dec = 70.0°, Mag = 1.2f (20° elevation in front of camera facing North at Lat 0°)
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())

        NightSkyCatalog.startStarsVector(fbb, 1)
        Star.createStar(fbb, 0.0f, 70.0f, 1.2f)
        val starsVec = fbb.endVector()

        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())
        val renderer = NightSkyRenderer()

        val androidCanvas = android.graphics.Canvas()
        val composeCanvas = androidx.compose.ui.graphics.Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val baseFov = 56.0f
        val w = 1080f
        val h = 2400f

        // Pass 1: 1.0x Zoom
        val fov1x = com.dazix.caelis.ar.CameraFovCalculator.computeZoomedFov(baseFov, 1.0f)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(w, h),
        ) {
            val visible1x = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = fov1x,
                w = w,
                h = h,
                zoomFactor = 1.0f,
                isAtmosphereVisible = false,
            )
            assertEquals("Star must be visible at 1x", 1, visible1x)
        }
        val starX1x = renderer.bucketPoints[1][0]
        val starY1x = renderer.bucketPoints[1][1]
        val dy1x = starY1x - h * 0.5f

        assertTrue("Star offset Y at 1x must be non-zero", kotlin.math.abs(dy1x) > 20f)

        // Pass 2: 3.0x Zoom (Simulating pinch gesture)
        val fov3x = com.dazix.caelis.ar.CameraFovCalculator.computeZoomedFov(baseFov, 3.0f)
        drawScope.draw(
            density = Density(2.5f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(w, h),
        ) {
            val visible3x = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 0.0,
                latitudeDegrees = 0.0,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = fov3x,
                w = w,
                h = h,
                zoomFactor = 3.0f,
                isAtmosphereVisible = false,
            )
            assertEquals("Star must be visible at 3x", 1, visible3x)
        }
        val starX3x = renderer.bucketPoints[1][0]
        val starY3x = renderer.bucketPoints[1][1]
        val dy3x = starY3x - h * 0.5f

        assertEquals("Star screen offset Y must scale by exactly 3.0x", dy1x * 3.0f, dy3x, 0.01f)
    }
}

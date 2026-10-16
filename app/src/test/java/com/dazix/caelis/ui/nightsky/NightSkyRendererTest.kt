package com.dazix.caelis.ui.nightsky

import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.dazix.caelis.ar.scene.NightSkyFrame
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.DsoType
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.data.nightsky.StarMetadata
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.ConstellationFocusState
import com.dazix.caelis.domain.nightsky.NightSkyCatalogLoader
import com.dazix.caelis.domain.nightsky.NightVisionState
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.PlanetState
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * The CPU half of the GPU night sky: the hit test as the screen runs it (project the frame the
 * scene last drew with [NightSkyRenderer.prepareHitTest], then [NightSkyRenderer.findHitAt]), the
 * overlay paints and the selection reticle.
 *
 * These were once `NightSkyRendererZeroAllocTest`, which drove everything through the Canvas
 * `drawNightSky`. That renderer is gone; the state the hit test reads is now produced by
 * `prepareHitTest` from the same projector, celestial matrix and focus state the Filament scene
 * uses, so each test below exercises the production path rather than a parallel one. Robolectric,
 * because the paint assertions need real `Paint` objects rather than the JVM stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightSkyRendererTest {

    /** Row-major device -> ENU: phone upright, back camera facing the north horizon. */
    private val northMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** The scene-side inputs of one frame, built the way `NightSkyScene.update` builds them. */
    private inner class SkyFrame(
        val catalog: NightSkyCatalog,
        latitudeDegrees: Double = 0.0,
        lstDegrees: Double = 0.0,
        fovHorizontalDegrees: Float = 60f,
        val width: Float = 1080f,
        val height: Float = 2400f,
        zoomRatio: Float = 1f,
        val density: Float = 2.5f,
    ) {
        val frame = NightSkyFrame().also {
            northMatrix.copyInto(it.deviceToEnu)
            it.viewportWidth = width
            it.viewportHeight = height
            it.fovHorizontalDegrees = fovHorizontalDegrees
            it.zoomRatio = zoomRatio
            it.density = density
            it.latitudeDegrees = latitudeDegrees
            it.lstDegrees = lstDegrees
        }
        val projector = SkyProjector()
        val celestialToEnu = FloatArray(16)
        val focus = ConstellationFocusState(256)
        private val centroidCel = FloatArray(256 * 3)
        private val constellationCount = minOf(catalog.constellationsLength(), 256)

        init {
            val model = FloatArray(16)
            SkyFrameMath.cameraModelMatrix(frame.deviceToEnu, 0, model)
            projector.update(model, width, height, fovHorizontalDegrees)
            SkyFrameMath.celestialToEnu(lstDegrees, latitudeDegrees, celestialToEnu)
            val c = Constellation()
            for (i in 0 until constellationCount) {
                catalog.constellations(c, i)
                val ra = Math.toRadians(c.centroidRa().toDouble())
                val dec = Math.toRadians(c.centroidDec().toDouble())
                centroidCel[i * 3] = (cos(dec) * cos(ra)).toFloat()
                centroidCel[i * 3 + 1] = sin(dec).toFloat()
                centroidCel[i * 3 + 2] = (cos(dec) * sin(ra)).toFloat()
            }
        }

        /** Runs the scene's focus animation for [seconds] at 20 fps. */
        fun settle(seconds: Float = 1f) {
            repeat((seconds / 0.05f).toInt()) {
                focus.update(constellationCount, centroidCel, celestialToEnu, projector, frame.selectedConstellation, 0.05f)
            }
        }

        fun prepare(renderer: NightSkyRenderer, showConstellations: Boolean = true, showMeteorShowers: Boolean = true) {
            renderer.prepareHitTest(projector, celestialToEnu, focus, frame, catalog, showConstellations, showMeteorShowers, density)
        }
    }

    private fun catalog(build: (FlatBufferBuilder) -> Int): NightSkyCatalog {
        val fbb = FlatBufferBuilder(1024)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, build(fbb))
        return NightSkyCatalogLoader.loadFromByteBuffer(fbb.dataBuffer())
    }

    private fun planet(id: String, magnitude: Float, dec: Double = 90.0, diameter: Float = 0.01f) = PlanetState(
        id = id,
        nameEn = id,
        nameCs = id,
        raDegrees = 0.0,
        decDegrees = dec,
        magnitude = magnitude,
        angularDiameterDeg = diameter,
        distanceFormatted = "",
        illuminationFraction = 1.0f,
        colorRgb = 0xFFFFFFFF.toInt(),
        isMoon = false,
    )

    @Test
    fun `overlay paints are preallocated and follow every theme and density`() {
        val renderer = NightSkyRenderer()
        assertEquals(NightSkyRenderer.MAX_METEOR_SHOWERS, renderer.meteorWorldX.size)
        assertEquals(NightSkyRenderer.MAX_METEOR_SHOWERS, renderer.meteorInFront.size)
        assertEquals(NightSkyRenderer.MAX_DSOS, renderer.dsoInFront.size)
        assertEquals(NightSkyRenderer.MAX_STARS, renderer.forceRenderStar.size)
        assertEquals(NightSkyRenderer.MAX_STARS, renderer.starCelX.size)
        assertEquals(256, renderer.constellationProgress.size)
        assertEquals(16, renderer.planetScreenX.size)
        assertNotNull(renderer.reticlePaint)
        assertNotNull(renderer.arrowPath)
        assertNotNull(renderer.radiantPath)

        renderer.applyTheme(NightVisionState(isActive = false))
        val normalLabel = renderer.constellationLabelPaint.color
        for (theme in NightVisionTheme.entries) {
            renderer.applyTheme(NightVisionState(isActive = true, theme = theme))
            assertTrue("$theme must recolour the labels", renderer.constellationLabelPaint.color != normalLabel)
        }

        renderer.updateOpticalPaints(density = 1f)
        val label1x = renderer.constellationLabelPaint.textSize
        renderer.updateOpticalPaints(density = 2.5f)
        assertEquals(label1x * 2.5f, renderer.constellationLabelPaint.textSize, 0.001f)
        assertEquals(15f * 2.5f, renderer.reticleTextPaint.textSize, 0.001f)

        assertTrue(renderer.findHitAt(100f, 100f, 48f, 2.5f, null) is NightSkyRenderer.HitTarget.Miss)
    }

    @Test
    fun `stars of a visible figure bypass the telescope limit and others do not`() {
        val catalog = catalog { fbb ->
            val name = fbb.createString("Crater")
            val lines = Constellation.createLinesVector(fbb, intArrayOf(0, 1))
            val c = Constellation.createConstellation(fbb, name, 0f, 90f, lines, fbb.createString("Pohár"), 0, 0)
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(c))
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 4)
            Star.createStar(fbb, 0f, 87f, 1.2f) // 3: bright, standalone
            Star.createStar(fbb, 0f, 88f, 6.7f) // 2: faint, standalone
            Star.createStar(fbb, 0f, 89f, 6.6f) // 1: faint, in Crater
            Star.createStar(fbb, 0f, 90f, 6.8f) // 0: faint, in Crater
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val sky = SkyFrame(catalog).also { it.settle() }
        val renderer = NightSkyRenderer()

        sky.prepare(renderer, showConstellations = true)
        assertTrue("Crater is in focus", renderer.constellationProgress[0] > 0.001f)
        assertTrue(renderer.forceRenderStar[0])
        assertTrue(renderer.forceRenderStar[1])
        assertFalse(renderer.forceRenderStar[2])
        assertFalse("bright enough on its own", renderer.forceRenderStar[3])
        assertEquals(listOf(true, true, false, true), (0..3).map { renderer.starInFront[it] })

        sky.prepare(renderer, showConstellations = false)
        assertFalse(renderer.forceRenderStar[0])
        assertFalse(renderer.forceRenderStar[1])
        assertEquals(listOf(false, false, false, true), (0..3).map { renderer.starInFront[it] })
    }

    @Test
    fun `asterisms focus and hit test independently of their parent constellation`() {
        val catalog = catalog { fbb ->
            val um = Constellation.createConstellation(
                fbb, fbb.createString("Ursa Major"), 0f, 90f, Constellation.createLinesVector(fbb, intArrayOf(0, 1)),
                fbb.createString("Velká medvědice"), 0, 0, false,
            )
            val bd = Constellation.createConstellation(
                fbb, fbb.createString("Big Dipper (Plough)"), 0f, 85f, Constellation.createLinesVector(fbb, intArrayOf(0, 1)),
                fbb.createString("Velký vůz"), 0, 0, true,
            )
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(um, bd))
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 2)
            Star.createStar(fbb, 0f, 85f, 1.8f)
            Star.createStar(fbb, 0f, 90f, 1.8f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val sky = SkyFrame(catalog).also { it.settle() }
        val renderer = NightSkyRenderer()
        sky.prepare(renderer)

        assertTrue(renderer.constellationProgress[0] > 0.3f)
        assertTrue(renderer.constellationProgress[1] > 0.3f)
        val hit = renderer.findHitAt(
            renderer.constellationCentroidScreenX[1], renderer.constellationCentroidScreenY[1],
            48f * 2.5f, 2.5f, catalog,
        )
        assertTrue(hit is NightSkyRenderer.HitTarget.ConstellationHit)
        assertEquals(1, (hit as NightSkyRenderer.HitTarget.ConstellationHit).index)
    }

    @Test
    fun `off-screen bearing indicator for all directions and behind the camera`() {
        val w = 1080f
        val h = 2400f
        val pad = 64f
        val cx = w * 0.5f
        val cy = h * 0.5f
        fun indicator(xs: Float, ys: Float, zs: Float, sx: Float = Float.NaN, sy: Float = Float.NaN) =
            NightSkyRenderer.computeOffscreenIndicator(xs, ys, zs, sx, sy, w, h, pad)

        indicator(1f, 0f, 1f, w + 200f, cy).let {
            assertEquals(0f, it.angleDeg, 0.01f); assertEquals(w - pad, it.arrowX, 0.01f); assertEquals(cy, it.arrowY, 0.01f)
        }
        indicator(-1f, 0f, 1f, -200f, cy).let {
            assertEquals(180f, kotlin.math.abs(it.angleDeg), 0.01f); assertEquals(pad, it.arrowX, 0.01f)
        }
        indicator(0f, 1f, 1f, cx, -300f).let {
            assertEquals(-90f, it.angleDeg, 0.01f); assertEquals(cx, it.arrowX, 0.01f); assertEquals(pad, it.arrowY, 0.01f)
        }
        indicator(0f, -1f, 1f, cx, h + 300f).let {
            assertEquals(90f, it.angleDeg, 0.01f); assertEquals(h - pad, it.arrowY, 0.01f)
        }
        // Behind: the camera-space bearing, not the mirrored projection.
        indicator(1f, 0f, -1f).let { assertEquals(0f, it.angleDeg, 0.01f); assertEquals(w - pad, it.arrowX, 0.01f) }
        indicator(0f, 1f, -1f).let { assertEquals(-90f, it.angleDeg, 0.01f); assertEquals(pad, it.arrowY, 0.01f) }
        // Dead behind on the optical axis points down rather than nowhere.
        indicator(0f, 0f, -1f).let { assertEquals(90f, it.angleDeg, 0.01f); assertEquals(h - pad, it.arrowY, 0.01f) }
    }

    @Test
    fun `selection reticle draws an off-screen indicator for a body behind the camera`() {
        val renderer = NightSkyRenderer()
        renderer.applyTheme(redVisionMode = false)
        val scope = CanvasDrawScope()
        val canvas = androidx.compose.ui.graphics.Canvas(android.graphics.Canvas())
        val behind = CelestialBodyDetails(
            name = "Alpha Centauri", subtitle = "Star",
            property1 = Pair("Constellation", "Centaurus"), property2 = Pair("Distance", "4.37 ly"),
            magnitude = -0.27f, raDegrees = 180.0, decDegrees = -60.0,
        )
        for ((star, constellation) in listOf(0 to -1, -1 to 5)) {
            renderer.selectedStarIndex = star
            renderer.selectedConstellationIndex = constellation
            scope.draw(Density(2.5f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(1080f, 2400f)) {
                renderer.drawSelectionPointer(
                    drawScope = this, selectedBody = behind, matrix = northMatrix, lstDegrees = 0.0,
                    latitudeDegrees = 0.0, displayRotation = android.view.Surface.ROTATION_0,
                    fovHorizontalDegrees = 60f, w = 1080f, h = 2400f, density = 2.5f,
                )
            }
        }
    }

    @Test
    fun `planets are hit-testable only while shown or selected, with priority over the star behind them`() {
        val catalog = catalog { fbb ->
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 1.8f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val planets = com.dazix.caelis.domain.nightsky.PlanetaryEphemerisCalculator.computeAll(
            java.time.Instant.parse("2026-08-25T20:00:00Z"),
        )
        assertEquals(8, planets.size)
        val sky = SkyFrame(catalog, latitudeDegrees = 90.0)
        sky.frame.planets = planets
        val renderer = NightSkyRenderer()

        sky.frame.showPlanets = true
        sky.prepare(renderer)
        assertTrue(renderer.activePlanetsCount > 0)
        val visible = (0 until 16).first { renderer.planetInFront[it] }
        val hit = renderer.findHitAt(
            renderer.planetScreenX[visible], renderer.planetScreenY[visible], 48f, 2.5f, catalog, planets = planets,
        )
        assertTrue(hit is NightSkyRenderer.HitTarget.PlanetHit)
        assertEquals(renderer.planetIds[visible], (hit as NightSkyRenderer.HitTarget.PlanetHit).planetId)
        val hitOff = renderer.findHitAt(
            renderer.planetScreenX[visible], renderer.planetScreenY[visible], 48f, 2.5f, catalog, planets = emptyList(),
        )
        assertFalse(hitOff is NightSkyRenderer.HitTarget.PlanetHit)

        sky.frame.showPlanets = false
        sky.prepare(renderer)
        assertEquals(0, renderer.activePlanetsCount)
        assertTrue((0 until 16).none { renderer.planetInFront[it] })
    }

    @Test
    fun `a selected planet stays hit-testable with the planet layer off`() {
        val catalog = catalog { fbb ->
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            val dsos = NightSkyCatalog.createDsosVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 1f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts, dsos)
        }
        val sky = SkyFrame(catalog)
        sky.frame.planets = listOf(planet("Mars", 0f), planet("Jupiter", -2f))
        sky.frame.showPlanets = false
        val renderer = NightSkyRenderer()

        sky.frame.selectedPlanetId = null
        sky.prepare(renderer)
        assertFalse(renderer.planetInFront[0])
        assertFalse(renderer.planetInFront[1])

        sky.frame.selectedPlanetId = "Mars"
        sky.prepare(renderer)
        assertTrue(renderer.planetInFront[0])
        assertFalse(renderer.planetInFront[1])
    }

    @Test
    fun `hit priority is planet over constellation over star over miss`() {
        val catalog = catalog { fbb ->
            val c = Constellation.createConstellation(
                fbb, fbb.createString("Ursa Minor"), 0f, 90f, Constellation.createLinesVector(fbb, intArrayOf(0, 0)),
                fbb.createString("Malý medvěd"), 0, 0, false,
            )
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(c))
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 2f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val renderer = NightSkyRenderer()
        val jupiter = planet("Jupiter", -2.5f, diameter = 0.013f)
        renderer.planetScreenX[0] = 540f
        renderer.planetScreenY[0] = 1200f
        renderer.planetRadiusPx[0] = 48f
        renderer.planetInFront[0] = true
        renderer.planetIds[0] = "Jupiter"
        renderer.activePlanetsCount = 1
        renderer.constellationCentroidScreenX[0] = 540f
        renderer.constellationCentroidScreenY[0] = 1200f
        renderer.constellationCentroidInFront[0] = true
        renderer.constellationProgress[0] = 1f

        val hitPlanet = renderer.findHitAt(540f, 1200f, 48f, 2.5f, catalog, planets = listOf(jupiter))
        assertEquals("Jupiter", (hitPlanet as NightSkyRenderer.HitTarget.PlanetHit).planetId)
        val hitConst = renderer.findHitAt(540f, 1200f, 48f, 2.5f, catalog, planets = emptyList())
        assertEquals(0, (hitConst as NightSkyRenderer.HitTarget.ConstellationHit).index)
        renderer.constellationProgress[0] = 0f
        assertTrue(renderer.findHitAt(100f, 100f, 48f, 2.5f, catalog) is NightSkyRenderer.HitTarget.Miss)
    }

    @Test
    fun `zoom scales hit-test positions exactly with the optical zoom`() {
        val catalog = catalog { fbb ->
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 70f, 1.2f) // 20 deg above the north horizon at latitude 0
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val renderer = NightSkyRenderer()
        val baseFov = 56f
        val h = 2400f

        SkyFrame(catalog, fovHorizontalDegrees = com.dazix.caelis.ar.CameraFovCalculator.computeZoomedFov(baseFov, 1f), zoomRatio = 1f)
            .prepare(renderer)
        assertTrue(renderer.starInFront[0])
        val dy1 = renderer.starScreenY[0] - h * 0.5f
        assertTrue(kotlin.math.abs(dy1) > 20f)

        SkyFrame(catalog, fovHorizontalDegrees = com.dazix.caelis.ar.CameraFovCalculator.computeZoomedFov(baseFov, 3f), zoomRatio = 3f)
            .prepare(renderer)
        assertTrue(renderer.starInFront[0])
        assertEquals(dy1 * 3f, renderer.starScreenY[0] - h * 0.5f, 0.05f)
    }

    @Test
    fun `a planet in conjunction with a star offers both`() {
        val catalog = catalog { fbb ->
            val meta = NightSkyCatalog.createMetadataVector(
                fbb, intArrayOf(StarMetadata.createStarMetadata(fbb, 0, fbb.createString("Spica"), 0, 0, 250f)),
            )
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 0.98f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val jupiter = planet("Jupiter", -2.5f)
        val sky = SkyFrame(catalog)
        sky.frame.planets = listOf(jupiter)
        val renderer = NightSkyRenderer()
        sky.prepare(renderer)

        val hit = renderer.findHitAt(550f, 1205f, 48f * 2.5f, 2.5f, catalog, planets = listOf(jupiter))
        val multi = hit as NightSkyRenderer.HitTarget.MultipleHits
        assertEquals(2, multi.candidates.size)
        val planetCandidate = multi.candidates.first { it is NightSkyRenderer.CelestialCandidate.Planet }
        assertEquals("Jupiter", (planetCandidate as NightSkyRenderer.CelestialCandidate.Planet).planetId)
        assertTrue(planetCandidate.target is NightSkyRenderer.HitTarget.PlanetHit)
        val starCandidate = multi.candidates.first { it is NightSkyRenderer.CelestialCandidate.Star }
        assertEquals("Spica", starCandidate.name)
        assertTrue(starCandidate.target is NightSkyRenderer.HitTarget.StarHit)
    }

    @Test
    fun `a dense star cluster offers every close candidate`() {
        val catalog = catalog { fbb ->
            val m1 = StarMetadata.createStarMetadata(fbb, 0, fbb.createString("Alcyone"), 0, 0, 440f)
            val m2 = StarMetadata.createStarMetadata(fbb, 1, fbb.createString("Maia"), 0, 0, 360f)
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf(m1, m2))
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 2)
            Star.createStar(fbb, 0f, 90f, 2.8f)
            Star.createStar(fbb, 0.2f, 90f, 3.8f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts)
        }
        val renderer = NightSkyRenderer()
        SkyFrame(catalog).prepare(renderer)

        val multi = renderer.findHitAt(542f, 1200f, 48f * 2.5f, 2.5f, catalog, planets = emptyList())
            as NightSkyRenderer.HitTarget.MultipleHits
        assertEquals(setOf("Alcyone", "Maia"), multi.candidates.map { it.name }.toSet())
    }

    @Test
    fun `a selected DSO stays hit-testable with the DSO layer off`() {
        val catalog = catalog { fbb ->
            fun dso(m: Int, type: Byte, size: Float, ratio: Float) = DeepSkyObject.createDeepSkyObject(
                fbb, m.toShort(), 0f, 90f, 8f, size, type, fbb.createString("M$m"), fbb.createString("X"),
                fbb.createString("Object $m"), fbb.createString("NGC $m"), 0f, ratio, fbb.createString(""), 0f, 0f,
            )
            val dsos = NightSkyCatalog.createDsosVector(fbb, intArrayOf(dso(1, DsoType.NEBULA, 6f, 0f), dso(31, DsoType.GALAXY, 178f, 0.32f)))
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 1f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts, dsos)
        }
        val sky = SkyFrame(catalog)
        sky.frame.showDsos = false
        val renderer = NightSkyRenderer()

        sky.frame.selectedDso = -1
        sky.prepare(renderer)
        assertFalse(renderer.dsoInFront[0])
        assertFalse(renderer.dsoInFront[1])

        sky.frame.selectedDso = 1
        sky.prepare(renderer)
        assertFalse(renderer.dsoInFront[0])
        assertTrue(renderer.dsoInFront[1])

        sky.frame.showDsos = true
        sky.prepare(renderer)
        assertTrue(renderer.dsoInFront[0])
        assertTrue("DSO world vectors are written for the occlusion test", renderer.dsoWorldY[0] > 0.99f)
    }

    @Test
    fun `with constellations off only the selected figure forces its stars`() {
        val catalog = catalog { fbb ->
            val orion = Constellation.createConstellation(
                fbb, fbb.createString("Orion"), 0f, 90f, Constellation.createLinesVector(fbb, intArrayOf(0, 1)),
                fbb.createString("Orion"), 0, 0, false, 0, 0, 0,
            )
            val uma = Constellation.createConstellation(
                fbb, fbb.createString("Ursa Major"), 0f, 90f, Constellation.createLinesVector(fbb, intArrayOf(2, 3)),
                fbb.createString("Velká medvědice"), 0, 0, false, 0, 0, 0,
            )
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(orion, uma))
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            val dsos = NightSkyCatalog.createDsosVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 4)
            repeat(4) { Star.createStar(fbb, 0f, 90f, 10f) }
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts, dsos)
        }
        val sky = SkyFrame(catalog).also { it.settle() }
        val renderer = NightSkyRenderer()

        sky.frame.selectedConstellation = -1
        sky.prepare(renderer, showConstellations = false)
        assertTrue((0..3).none { renderer.forceRenderStar[it] })

        sky.frame.selectedConstellation = 0
        sky.prepare(renderer, showConstellations = false)
        assertEquals(listOf(true, true, false, false), (0..3).map { renderer.forceRenderStar[it] })
    }

    @Test
    fun `meteor radiants are projected and hit-testable`() {
        val catalog = catalog { fbb ->
            val shower = com.dazix.caelis.data.nightsky.MeteorShower.createMeteorShower(
                fbb, 1.toShort(), fbb.createString("Perseids"), 0f, 90f, 100.toShort(), 8.toByte(), 12.toByte(), fbb.createString("Per"),
            )
            val meteors = NightSkyCatalog.createMeteorShowersVector(fbb, intArrayOf(shower))
            val consts = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf())
            val meta = NightSkyCatalog.createMetadataVector(fbb, intArrayOf())
            val dsos = NightSkyCatalog.createDsosVector(fbb, intArrayOf())
            NightSkyCatalog.startStarsVector(fbb, 1)
            Star.createStar(fbb, 0f, 90f, 2f)
            NightSkyCatalog.createNightSkyCatalog(fbb, fbb.endVector(), meta, consts, dsos, meteors)
        }
        val sky = SkyFrame(catalog)
        val renderer = NightSkyRenderer()

        sky.prepare(renderer, showMeteorShowers = true)
        assertTrue(renderer.meteorInFront[0])
        val hit = renderer.findHitAt(renderer.meteorScreenX[0], renderer.meteorScreenY[0], 48f, 2.5f, catalog)
        assertTrue(
            hit is NightSkyRenderer.HitTarget.MeteorRadiantHit ||
                (hit is NightSkyRenderer.HitTarget.MultipleHits && hit.candidates.any { it is NightSkyRenderer.CelestialCandidate.MeteorShower }),
        )

        sky.prepare(renderer, showMeteorShowers = false)
        assertFalse(renderer.meteorInFront[0])
    }
}

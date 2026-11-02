package com.dazix.caelis.ar.scene

import androidx.compose.ui.graphics.Color
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.SolarTrajectory

/**
 * What the Sun AR screen draws on the GPU: the alt-az grid, the terrain silhouette, the sun and
 * moon day arcs and the marks on them (time bubbles, rise/set badges, the live discs, the grid's
 * labels), all Earth-fixed in ENU (no sidereal rotation). Only the off-screen pointers stay in the
 * Compose overlay: they sit on the screen edge, not on the sky, and project through [projector].
 *
 * All geometry is static between data changes: the grid is built once, the silhouette when the
 * profile changes, the arcs when a trajectory, the profile or the shading switch changes, the
 * mark atlas when its labels do. A frame is a camera write, plus a mark upload when a mark moved.
 */
class SunArScene(private val surface: SkySurface) {

    val projector = SkyProjector()

    private val lineMaterial = surface.loadMaterial("line")
    private val grid = LineBatch(surface, lineMaterial, AltAzGridBuilder.SEGMENT_COUNT, 1, Priority.GRID)

    // Each line over its own dark casing, as on the Canvas: a thin coloured stroke alone vanishes
    // against a bright sky or a pale field. Casing and line are separate batches so every casing is
    // under every line, rather than the next segment's casing nicking the joint of the previous one.
    private val silhouetteCasing = LineBatch(surface, lineMaterial, HorizonProfile.SAMPLE_COUNT, 1, Priority.SILHOUETTE_CASING)
    private val silhouette = LineBatch(surface, lineMaterial, HorizonProfile.SAMPLE_COUNT, 1, Priority.SILHOUETTE)
    private val arcCasing = LineBatch(surface, lineMaterial, MAX_ARC_SEGMENTS, 1, Priority.ARC_CASING)
    private val arcs = LineBatch(surface, lineMaterial, MAX_ARC_SEGMENTS, 1, Priority.ARC)

    // Marks over the arcs: time bubbles, rise/set badges, the grid's labels (sprites of [markAtlas])
    // and the sun and moon discs, badge dots and grid markers (glyphs). Listed by the screen each
    // frame after [update]; they were a Compose overlay once, which the TextureView showed 2-3 vsyncs
    // apart from the arcs.
    private val labelMaterial = surface.loadMaterial("label")
    private val glyphMaterial = surface.loadMaterial("glyph")
    val markAtlas = SpriteAtlas(surface.engine, 2048, 1024, MAX_MARK_SPRITES)
    val markGlyphs = GlyphBatch(surface, glyphMaterial, MAX_MARK_GLYPHS, Priority.MARKS, 1)
    val markLabels = LabelBatch(surface, labelMaterial, markAtlas, MAX_MARK_SPRITES, Priority.MARKS, 2)

    // Under a virtual place the camera is off: a neutral backdrop stands in for the picture and the
    // place's terrain is filled solid below its ridge (terrain.mat), so the skyline reads as land.
    private val backdropMaterial = surface.loadMaterial("virtual_backdrop")
    private val terrainMaterial = surface.loadMaterial("terrain")
    private val backdrop = FullScreenLayer(surface, backdropMaterial, Priority.BACKDROP)
    private val terrainFill = TerrainRing(surface, terrainMaterial, Priority.TERRAIN_FILL)
    private var virtualBackdrop = false
    private var lastFrameNanos = 0L

    private val cameraModel = FloatArray(16)
    private val a = FloatArray(3)

    // One path at a time: its points on the sky sphere, so each segment can see its neighbours.
    private val points = FloatArray(MAX_PATH_POINTS * 3)
    private val azimuths = DoubleArray(MAX_PATH_POINTS)

    private var builtProfile: HorizonProfile? = null
    private var profileBuilt = false
    private var builtSolar: SolarTrajectory? = null
    private var builtLunar: LunarTrajectory? = null
    private var builtShadeProfile: HorizonProfile? = null
    private var builtDensity = 0f
    private var destroyed = false

    init {
        grid.fillAltAzGrid(nightVision = false, theme = NightVisionTheme.AMBER)
        setProfile(null)
        arcCasing.begin(); arcCasing.end()
        arcs.begin(); arcs.end()
        backdrop.setVisible(false)
        terrainFill.setColor(TerrainFill.red, TerrainFill.green, TerrainFill.blue, 1f)
        terrainFill.setOffsets(0f, 0f)
        terrainFill.setVisible(false)
    }

    /**
     * The neutral backdrop and the filled terrain of a virtual place, in place of the camera picture.
     * The fill is drawn without the compass calibration: there is no real skyline to have aligned
     * it against, and the arcs are not calibrated either.
     */
    fun setVirtualBackdrop(enabled: Boolean) {
        if (enabled == virtualBackdrop) return
        virtualBackdrop = enabled
        backdrop.setVisible(enabled)
        terrainFill.setVisible(enabled)
    }

    /** The terrain skyline, or null for none. */
    fun setProfile(profile: HorizonProfile?) {
        terrainFill.setProfile(profile)
        if (profileBuilt && profile === builtProfile) return
        profileBuilt = true
        builtProfile = profile
        silhouetteCasing.begin()
        silhouette.begin()
        if (profile != null) {
            val n = HorizonProfile.SAMPLE_COUNT
            for (i in 0 until n) {
                writePoint(i, HorizonProfile.azimuthForIndex(i), profile.elevationsDegrees[i].toDouble())
            }
            // A closed ring: every segment has neighbours on both sides. Widths are raw pixels, as
            // the Canvas silhouette was drawn.
            for (i in 0 until n) {
                addJoined(silhouetteCasing, (i + n - 1) % n, i, (i + 1) % n, (i + 2) % n, true, true, 3.4f, SilhouetteCasing, SilhouetteCasing.alpha)
                addJoined(silhouette, (i + n - 1) % n, i, (i + 1) % n, (i + 2) % n, true, true, 2.2f, NormalTerrain, NormalTerrain.alpha)
            }
        }
        silhouetteCasing.end()
        silhouette.end()
    }

    /**
     * The day arcs. [shadeProfile] is the profile whose terrain dims the stretches it hides, or
     * null to draw them all at full strength. Widths are in dp, scaled by [density].
     */
    fun setTrajectories(
        solar: SolarTrajectory,
        lunar: LunarTrajectory,
        shadeProfile: HorizonProfile?,
        density: Float,
    ) {
        if (solar === builtSolar && lunar === builtLunar && shadeProfile === builtShadeProfile && density == builtDensity) return
        builtSolar = solar
        builtLunar = lunar
        builtShadeProfile = shadeProfile
        builtDensity = density
        arcCasing.begin()
        arcs.begin()
        val sun = solar.pathPoints
        val sunCount = minOf(sun.size, MAX_PATH_POINTS)
        for (i in 0 until sunCount) writePoint(i, sun[i].coordinates.azimuthDegrees, sun[i].coordinates.apparentElevationDegrees)
        for (i in 0 until sunCount - 1) {
            addArcSegment(i, sunCount, sun[i].coordinates.apparentElevationDegrees, sun[i + 1].coordinates.apparentElevationDegrees, NormalGoldSolar, shadeProfile, density)
        }
        val moon = lunar.pathPoints
        val moonCount = minOf(moon.size, MAX_PATH_POINTS)
        for (i in 0 until moonCount) writePoint(i, moon[i].coordinates.azimuthDegrees, moon[i].coordinates.apparentElevationDegrees)
        for (i in 0 until moonCount - 1) {
            addArcSegment(i, moonCount, moon[i].coordinates.apparentElevationDegrees, moon[i + 1].coordinates.apparentElevationDegrees, MoonArc, shadeProfile, density)
        }
        arcCasing.end()
        arcs.end()
    }

    /**
     * The step from path point [i] to i + 1 (already in [points]), styled as the Canvas drew it:
     * only stretches with an end above -15° are shown, and behind a ridge the arc is thin and faint
     * with no casing — the shaded stretch is how much of the golden hour the terrain takes away,
     * legible without becoming a competing line.
     */
    private fun addArcSegment(
        i: Int, count: Int, el1: Double, el2: Double,
        color: Color, shadeProfile: HorizonProfile?, density: Float,
    ) {
        if (el1 < MIN_ARC_ELEVATION && el2 < MIN_ARC_ELEVATION) return
        val occluded = shadeProfile != null &&
            shadeProfile.isOccluded(azimuths[i], el1) && shadeProfile.isOccluded(azimuths[i + 1], el2)
        val hasPrevious = i > 0
        val hasNext = i + 2 < count
        val previous = if (hasPrevious) i - 1 else i
        val next = if (hasNext) i + 2 else i + 1
        if (!occluded) addJoined(arcCasing, previous, i, i + 1, next, hasPrevious, hasNext, 3.2f * density, ArcCasing, ArcCasing.alpha)
        val alpha = if (occluded) 0.3f else color.alpha
        val width = (if (occluded) 1.4f else 2.0f) * density
        addJoined(arcs, previous, i, i + 1, next, hasPrevious, hasNext, width, color, alpha)
    }

    /** Stores path point [i] as an ENU position on the sky sphere (and its azimuth). */
    private fun writePoint(i: Int, azimuthDeg: Double, elevationDeg: Double) {
        AltAzGridBuilder.direction(azimuthDeg, elevationDeg, a)
        val r = SkySurface.SKY_RADIUS
        points[i * 3] = a[0] * r
        points[i * 3 + 1] = a[1] * r
        points[i * 3 + 2] = a[2] * r
        azimuths[i] = azimuthDeg
    }

    private fun addJoined(
        batch: LineBatch, p: Int, a: Int, b: Int, n: Int,
        hasPrevious: Boolean, hasNext: Boolean,
        widthPx: Float, color: Color, alpha: Float,
    ) {
        val s = points
        batch.addJoinedSegment(
            s[p * 3], s[p * 3 + 1], s[p * 3 + 2], hasPrevious,
            s[a * 3], s[a * 3 + 1], s[a * 3 + 2],
            s[b * 3], s[b * 3 + 1], s[b * 3 + 2],
            s[n * 3], s[n * 3 + 1], s[n * 3 + 2], hasNext,
            widthPx, color.red, color.green, color.blue, alpha,
        )
    }

    /** Called from the frame loop: the camera, and the projector the overlay draws through. */
    fun update(
        deviceToEnu: FloatArray,
        displayRotationDegrees: Int,
        width: Float,
        height: Float,
        fovHorizontalDegrees: Float,
        frameTimeNanos: Long = 0L,
    ) {
        // The filled ridge rises into place when a profile lands, as in Night Sky (HorizonMorph).
        val dt = if (lastFrameNanos == 0L || frameTimeNanos == 0L) 0.016f else (frameTimeNanos - lastFrameNanos) / 1e9f
        lastFrameNanos = frameTimeNanos
        terrainFill.advance(dt)
        SkyFrameMath.cameraModelMatrix(deviceToEnu, displayRotationDegrees, cameraModel)
        surface.camera.setModelMatrix(cameraModel)
        surface.setFovHorizontal(fovHorizontalDegrees.toDouble())
        projector.update(cameraModel, width, height, fovHorizontalDegrees)
    }

    /** Releases every engine object. Idempotent; must run before the [SkySurface] is destroyed. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        markLabels.destroy()
        markGlyphs.destroy()
        markAtlas.destroy()
        surface.engine.destroyMaterial(labelMaterial)
        surface.engine.destroyMaterial(glyphMaterial)
        arcs.destroy()
        arcCasing.destroy()
        silhouette.destroy()
        silhouetteCasing.destroy()
        grid.destroy()
        surface.engine.destroyMaterial(lineMaterial)
        terrainFill.destroy()
        backdrop.destroy()
        surface.engine.destroyMaterial(terrainMaterial)
        surface.engine.destroyMaterial(backdropMaterial)
    }

    private object Priority {
        const val BACKDROP = 0
        const val TERRAIN_FILL = 1
        const val GRID = 2
        const val SILHOUETTE_CASING = 3
        const val SILHOUETTE = 4
        const val ARC_CASING = 5
        const val ARC = 6
        const val MARKS = 7
    }

    companion object {
        /** Sun and moon together; each is a day sampled every 2 minutes (720 points). */
        private const val MAX_ARC_SEGMENTS = 2 * 1440

        /** The longest polyline: the terrain ring, larger than either day path. */
        private const val MAX_PATH_POINTS = HorizonProfile.SAMPLE_COUNT
        private const val MIN_ARC_ELEVATION = -15.0

        /** Two days of bubbles every 15 minutes, four badges, the grid's labels and the peak names. */
        val MAX_MARK_SPRITES = 2 * 96 + 4 + SkyGridMarkers.LABEL_COUNT + LandmarkMarks.MAX_LANDMARKS
        private const val MAX_MARK_GLYPHS = 4 + 8 + SkyGridMarkers.GLYPH_COUNT

        /** The moon's arc and pointer colour (the screen's `MoonSilverCyan`). */
        val MoonArc = Color(0xFF80DEEA)
        private val ArcCasing = Color(0x99000000)

        /** The land of a virtual place: darker than the backdrop's horizon haze, lighter than its ground. */
        private val TerrainFill = Color(0xFF3A3833)
    }
}

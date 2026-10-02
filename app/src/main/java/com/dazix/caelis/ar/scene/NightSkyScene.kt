package com.dazix.caelis.ar.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.dazix.caelis.ar.NormalGrid
import com.dazix.caelis.ar.NormalHorizon
import com.dazix.caelis.ar.paletteForTheme
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.ConstellationFocusState
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.StarOptics
import com.google.android.filament.Texture
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the Night Sky screen draws on the GPU, owned in one place and driven by one
 * [NightSkyFrame] per vsync.
 *
 * Layers, in draw order (see [SkyPriority]): Milky Way, atmosphere, stars, constellation artwork and
 * lines, the stylised landscape or the real terrain ring, the alt-az grid. Labels, the reticle and
 * the off-screen chevron are not here — they are text and screen-space UI, drawn by the Compose
 * overlay through the same [SkyProjector] this scene updates, so they cannot drift from the sky.
 *
 * Per-frame work is matrix writes, uniform writes and uploads only when something changed; the
 * frame loop allocates nothing.
 */
class NightSkyScene(
    private val context: Context,
    private val surface: SkySurface,
    private val catalog: NightSkyCatalog,
) {
    val projector = SkyProjector()
    val constellationCount: Int = catalog.constellationsLength().coerceAtMost(MAX_CONSTELLATIONS)
    val focus = ConstellationFocusState(constellationCount)

    private val starMaterial = surface.loadMaterial("star")
    private val lineMaterial = surface.loadMaterial("line")
    private val texturedMaterial = surface.loadMaterial("textured")
    private val milkyWayMaterial = surface.loadMaterial("milkyway")
    private val atmosphereMaterial = surface.loadMaterial("atmosphere")
    private val landscapeMaterial = surface.loadMaterial("landscape")
    private val terrainMaterial = surface.loadMaterial("terrain")

    private val milkyWay = FullScreenLayer(surface, milkyWayMaterial, SkyPriority.MILKY_WAY)
    private val atmosphere = FullScreenLayer(surface, atmosphereMaterial, SkyPriority.ATMOSPHERE)
    val stars = StarField(surface, catalog, starMaterial)
    private val constellationLines: LineBatch
    private val landscape = FullScreenLayer(surface, landscapeMaterial, SkyPriority.LANDSCAPE)
    private val terrain = TerrainRing(surface, terrainMaterial, SkyPriority.LANDSCAPE)
    private val grid = LineBatch(surface, lineMaterial, AltAzGridBuilder.SEGMENT_COUNT, 1, SkyPriority.GRID)

    private var milkyWayTexture: Texture? = null
    private var landscapeTexture: Texture? = null

    // Per-constellation data, read from the catalogue once.
    private val centroidCel = FloatArray(constellationCount * 3)
    private val isAsterism = BooleanArray(constellationCount)
    private val artName = arrayOfNulls<String>(constellationCount)
    private val artCorners = FloatArray(constellationCount * 12)
    private val artQuads = arrayOfNulls<TexturedQuad>(constellationCount)
    private val active = BooleanArray(constellationCount)
    private val starCel = FloatArray(stars.starCount * 3)

    /** Decoded artwork, the same 64-entry LRU the Canvas renderer kept. */
    private val artworkCache = object : LruCache<String, Bitmap>(64) {}
    private val artTextures = HashMap<String, Texture>()

    private val celestialToEnu = FloatArray(16)
    private val cameraModel = FloatArray(16)
    private val reusableConstellation = Constellation()

    // What the static geometry was last built for; a change rebuilds it.
    private var builtTheme: NightVisionTheme? = null
    private var builtNightVision = false
    private var builtSelected = Int.MIN_VALUE
    private var builtArtBoost = false
    private var builtDensity = 0f
    private var builtShowConstellations = false

    init {
        readCatalog()
        var totalSegments = 0
        for (c in 0 until constellationCount) {
            catalog.constellations(reusableConstellation, c)
            totalSegments += reusableConstellation.linesLength() / 2
        }
        // Selected constellations are drawn twice (halo + core).
        constellationLines = LineBatch(
            surface, lineMaterial, totalSegments + MAX_SELECTED_SEGMENTS, constellationCount,
            SkyPriority.CELESTIAL_OVERLAY,
        )
        landscape.setVisible(false)
    }

    fun setMilkyWayBitmap(bitmap: Bitmap) {
        milkyWayTexture?.let { surface.engine.destroyTexture(it) }
        val texture = createBitmapTexture(surface.engine, bitmap)
        milkyWayTexture = texture
        milkyWay.materialInstance.setParameter("milkyWay", texture, skySampler(repeatU = true))
    }

    fun setLandscapeBitmap(bitmap: Bitmap) {
        landscapeTexture?.let { surface.engine.destroyTexture(it) }
        val texture = createBitmapTexture(surface.engine, bitmap)
        landscapeTexture = texture
        landscape.materialInstance.setParameter("panorama", texture, skySampler(repeatU = true))
    }

    fun setHorizonProfile(profile: HorizonProfile?) = terrain.setProfile(profile)

    /** Called from the frame loop. */
    fun update(frame: NightSkyFrame) {
        SkyFrameMath.cameraModelMatrix(frame.deviceToEnu, frame.displayRotationDegrees, cameraModel)
        surface.camera.setModelMatrix(cameraModel)
        surface.setFovHorizontal(frame.fovHorizontalDegrees.toDouble())
        projector.update(cameraModel, frame.viewportWidth, frame.viewportHeight, frame.fovHorizontalDegrees)
        SkyFrameMath.celestialToEnu(frame.lstDegrees, frame.latitudeDegrees, celestialToEnu)

        val nightVision = frame.nightVisionActive
        surface.setNightVision(nightVision, NightVisionColors.forTheme(frame.nightVisionTheme))

        // Celestial layers turn with the sky; the grid, landscape and terrain are Earth-fixed.
        stars.setTransform(celestialToEnu)
        constellationLines.setTransform(celestialToEnu)
        milkyWay.materialInstance.setParameter(
            "enuToCelestial",
            com.google.android.filament.MaterialInstance.FloatElement.MAT3,
            enuToCelestial3(), 0, 1,
        )

        // Stars
        val zoom = frame.zoomRatio
        val opticScale = StarOptics.computeOpticScale(zoom)
        val artScale = if (frame.showConstellationArt) 1.5f else 1f
        stars.materialInstance.setParameter("limitMagnitude", StarOptics.computeFaintestVisibleMagnitude(zoom))
        stars.materialInstance.setParameter("pixelScale", frame.density.coerceAtLeast(0.5f) * opticScale * artScale)
        stars.materialInstance.setParameter("extinction", if (frame.showAtmosphere) 1f else 0f)

        // Background layers
        milkyWay.setVisible(frame.showMilkyWay && milkyWayTexture != null)
        atmosphere.setVisible(frame.showAtmosphere)
        val realHorizon = frame.useRealHorizon && terrain.hasUsableProfile
        landscape.setVisible(frame.showLandscape && !realHorizon && landscapeTexture != null)
        landscape.materialInstance.setParameter("azimuthOffset", Math.toRadians(frame.alignAzimuthOffsetDeg.toDouble()).toFloat())
        landscape.materialInstance.setParameter("elevationOffset", Math.toRadians(frame.alignElevationOffsetDeg.toDouble()).toFloat())
        terrain.setVisible(realHorizon)
        terrain.setOffsets(frame.alignAzimuthOffsetDeg, frame.alignElevationOffsetDeg)
        grid.setVisible(frame.showGrid)

        // Constellations
        val selected = frame.selectedConstellation
        focus.update(
            constellationCount, centroidCel, celestialToEnu, projector, selected, frame.dtSeconds,
            frame.focusRadiusMultiplier,
        )
        rebuildIfNeeded(frame)
        updateConstellationProgressAndForcedStars(frame)
        updateArtwork(frame)
        stars.flush()
    }

    fun destroy() {
        artQuads.forEach { it?.destroy() }
        artTextures.values.forEach { surface.engine.destroyTexture(it) }
        artTextures.clear()
        milkyWayTexture?.let { surface.engine.destroyTexture(it) }
        landscapeTexture?.let { surface.engine.destroyTexture(it) }
        grid.destroy()
        terrain.destroy()
        landscape.destroy()
        constellationLines.destroy()
        stars.destroy()
        atmosphere.destroy()
        milkyWay.destroy()
        val engine = surface.engine
        listOf(
            starMaterial, lineMaterial, texturedMaterial, milkyWayMaterial,
            atmosphereMaterial, landscapeMaterial, terrainMaterial,
        ).forEach { engine.destroyMaterial(it) }
    }

    private val enuToCel3 = FloatArray(9)

    /** Column-major 3x3 of ENU -> celestial: the transpose of the rotation part of [celestialToEnu]. */
    private fun enuToCelestial3(): FloatArray {
        val m = celestialToEnu
        for (col in 0 until 3) for (row in 0 until 3) enuToCel3[col * 3 + row] = m[row * 4 + col]
        return enuToCel3
    }

    private fun readCatalog() {
        val bb = catalog.byteBuffer
        val base = catalog.starsVectorOffset()
        for (i in 0 until stars.starCount) {
            val offset = base + i * 12
            celestialVector(bb.getFloat(offset).toDouble(), bb.getFloat(offset + 4).toDouble(), starCel, i * 3)
        }
        for (c in 0 until constellationCount) {
            catalog.constellations(reusableConstellation, c)
            celestialVector(reusableConstellation.centroidRa().toDouble(), reusableConstellation.centroidDec().toDouble(), centroidCel, c * 3)
            isAsterism[c] = reusableConstellation.isAsterism()
            val name = reusableConstellation.artResourceName()
            if (!name.isNullOrEmpty() && reusableConstellation.artCornerRaLength() == 4) {
                artName[c] = name
                for (k in 0 until 4) {
                    celestialVector(
                        reusableConstellation.artCornerRa(k).toDouble(),
                        reusableConstellation.artCornerDec(k).toDouble(),
                        artCorners, c * 12 + k * 3,
                    )
                }
            }
        }
    }

    private fun rebuildIfNeeded(frame: NightSkyFrame) {
        val theme = frame.nightVisionTheme
        val nv = frame.nightVisionActive
        if (theme == builtTheme && nv == builtNightVision) {
            if (frame.selectedConstellation == builtSelected && frame.showConstellationArt == builtArtBoost &&
                frame.density == builtDensity && frame.showConstellations == builtShowConstellations
            ) return
        } else {
            buildGrid(theme, nv)
        }
        builtTheme = theme
        builtNightVision = nv
        builtSelected = frame.selectedConstellation
        builtArtBoost = frame.showConstellationArt
        builtDensity = frame.density
        builtShowConstellations = frame.showConstellations
        buildConstellationLines(frame)
    }

    private fun buildGrid(theme: NightVisionTheme, nightVision: Boolean) {
        val palette = paletteForTheme(theme)
        val horizon = if (nightVision) palette.secondary else NormalHorizon
        val major = if (nightVision) palette.secondary.copy(alpha = 0.6f) else NormalHorizon.copy(alpha = 0.55f)
        val minor = if (nightVision) palette.grid else NormalGrid
        grid.begin()
        AltAzGridBuilder.build { ax, ay, az, bx, by, bz, w, role ->
            val c = when (role) {
                AltAzGridBuilder.Role.HORIZON -> horizon
                AltAzGridBuilder.Role.MAJOR -> major
                AltAzGridBuilder.Role.MINOR -> minor
            }
            val r = SkySurface.SKY_RADIUS
            grid.addSegment(ax * r, ay * r, az * r, bx * r, by * r, bz * r, w, c.red, c.green, c.blue, c.alpha)
        }
        grid.end()
    }

    /**
     * Constellation figures, in the celestial frame. Colours and widths are the Canvas paints':
     * ordinary figures at alpha 48 (asterisms 32) x the art boost, the selected one as a halo
     * (alpha 42) under a core (alpha 240). Progress scales both length and alpha in line.mat.
     */
    private fun buildConstellationLines(frame: NightSkyFrame) {
        val density = frame.density.coerceAtLeast(0.5f)
        val lineScale = if (frame.showConstellationArt) 1.25f else 1f
        val alphaBoost = lineScale
        val gapPx = 7f * density
        val palette = paletteForTheme(frame.nightVisionTheme)
        val nv = frame.nightVisionActive
        val r = SkySurface.SKY_RADIUS

        constellationLines.begin()
        for (c in 0 until constellationCount) {
            val selected = c == frame.selectedConstellation
            if (!frame.showConstellations && !selected) continue
            val asterism = isAsterism[c]
            catalog.constellations(reusableConstellation, c)
            val n = reusableConstellation.linesLength()
            val passes = if (selected) 2 else 1
            for (pass in 0 until passes) {
                // Colour roles as in NightSkyRenderer.applyTheme; alpha as the drawNightSky override.
                val (rgb, alpha, width) = when {
                    !selected -> Triple(
                        if (nv) palette.primary else if (asterism) ASTERISM else CONSTELLATION,
                        (if (asterism) 32f else 48f) * alphaBoost / 255f,
                        (if (asterism) 1.15f else 1.45f) * density * lineScale,
                    )
                    pass == 0 -> Triple(
                        if (nv) palette.primary else if (asterism) ASTERISM else CONSTELLATION_HALO,
                        42f * alphaBoost / 255f,
                        (if (asterism) 4.2f else 5.4f) * density * lineScale,
                    )
                    else -> Triple(
                        if (nv) palette.secondary else if (asterism) ASTERISM_CORE else CONSTELLATION_CORE,
                        (240f * alphaBoost).coerceAtMost(255f) / 255f,
                        (if (asterism) 1.7f else 2.16f) * density * lineScale,
                    )
                }
                var k = 0
                while (k < n - 1) {
                    val i1 = reusableConstellation.lines(k)
                    val i2 = reusableConstellation.lines(k + 1)
                    k += 2
                    if (i1 !in 0 until stars.starCount || i2 !in 0 until stars.starCount) continue
                    constellationLines.addSegment(
                        starCel[i1 * 3] * r, starCel[i1 * 3 + 1] * r, starCel[i1 * 3 + 2] * r,
                        starCel[i2 * 3] * r, starCel[i2 * 3 + 1] * r, starCel[i2 * 3 + 2] * r,
                        width, rgb.red, rgb.green, rgb.blue, alpha,
                        gapPx = gapPx, group = c,
                    )
                }
            }
        }
        constellationLines.end()
    }

    private fun updateConstellationProgressAndForcedStars(frame: NightSkyFrame) {
        var activeChanged = false
        for (c in 0 until constellationCount) {
            val selected = c == frame.selectedConstellation
            val p = if (selected) 1f else focus.progress[c]
            constellationLines.setGroupProgress(c, p)
            val nowActive = (frame.showConstellations || selected) && (p > 0.001f || selected)
            if (nowActive != active[c]) {
                active[c] = nowActive
                activeChanged = true
            }
        }
        constellationLines.flushProgress()
        if (!activeChanged) return
        // Stars of a visible or selected figure bypass the telescope limit (forceRenderStar).
        stars.clearForced()
        for (c in 0 until constellationCount) {
            if (!active[c]) continue
            catalog.constellations(reusableConstellation, c)
            for (j in 0 until reusableConstellation.linesLength()) stars.setForced(reusableConstellation.lines(j), true)
        }
    }

    /** Artwork quads, created and textured only once a figure starts to appear (Canvas: p > 0). */
    private fun updateArtwork(frame: NightSkyFrame) {
        for (c in 0 until constellationCount) {
            val name = artName[c] ?: continue
            val selected = c == frame.selectedConstellation
            val allowed = (frame.showConstellations && frame.showConstellationArt) || selected
            val p = if (selected) 1f else focus.progress[c]
            val quad = artQuads[c]
            if (!allowed || (p <= MIN_DISPLAY_PROGRESS && !selected)) {
                quad?.setVisible(false)
                continue
            }
            val texture = artTexture(name) ?: continue
            val q = quad ?: TexturedQuad(surface, texturedMaterial, SkyPriority.CELESTIAL).also {
                it.setCorners(artCorners.copyOfRange(c * 12, c * 12 + 12))
                artQuads[c] = it
            }
            q.setTexture(texture)
            q.setTransform(celestialToEnu)
            q.setOpacity((90f * p).coerceIn(0f, 255f) / 255f)
            q.setVisible(true)
        }
    }

    private fun artTexture(name: String): Texture? {
        artTextures[name]?.let { return it }
        // Same decode as NightSkyRenderer.getOrLoadArtwork: unscaled ARGB_8888.
        val bitmap = artworkCache.get(name) ?: run {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            BitmapFactory.decodeResource(context.resources, id, options)?.also { artworkCache.put(name, it) }
        } ?: return null
        return createBitmapTexture(surface.engine, bitmap).also { artTextures[name] = it }
    }

    private fun celestialVector(raDeg: Double, decDeg: Double, out: FloatArray, offset: Int) {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        out[offset] = (cos(dec) * cos(ra)).toFloat()
        out[offset + 1] = sin(dec).toFloat()
        out[offset + 2] = (cos(dec) * sin(ra)).toFloat()
    }

    companion object {
        const val MAX_CONSTELLATIONS = 256
        private const val MAX_SELECTED_SEGMENTS = 256
        private const val MIN_DISPLAY_PROGRESS = 0.001f

        // Normal-mode colours of NightSkyRenderer.applyTheme (alpha is overridden per draw).
        private val CONSTELLATION = androidx.compose.ui.graphics.Color(77, 208, 225)
        private val CONSTELLATION_HALO = androidx.compose.ui.graphics.Color(128, 222, 234)
        private val CONSTELLATION_CORE = androidx.compose.ui.graphics.Color(255, 255, 255)
        private val ASTERISM = androidx.compose.ui.graphics.Color(144, 202, 249)
        private val ASTERISM_CORE = androidx.compose.ui.graphics.Color(240, 248, 255)
    }
}

/**
 * Everything [NightSkyScene.update] needs for one frame, in one mutable holder the screen fills in
 * place — no per-frame allocation.
 */
class NightSkyFrame {
    /** Row-major device -> ENU (sensor or ARCore), calibration included. */
    val deviceToEnu = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    var displayRotationDegrees = 0
    var viewportWidth = 1f
    var viewportHeight = 1f
    var fovHorizontalDegrees = 60f
    var zoomRatio = 1f
    var density = 1f
    var dtSeconds = 0.016f
    var lstDegrees = 0.0
    var latitudeDegrees = 0.0

    var showConstellations = true
    var showConstellationArt = true
    var showMilkyWay = true
    var showAtmosphere = true
    var showLandscape = true
    var useRealHorizon = false
    var showGrid = false
    var selectedConstellation = -1
    var focusRadiusMultiplier = ConstellationFocusState.DEFAULT_FOCUS_RADIUS_MULTIPLIER
    var alignAzimuthOffsetDeg = 0f
    var alignElevationOffsetDeg = 0f

    var nightVisionActive = false
    var nightVisionTheme = NightVisionTheme.AMBER
}

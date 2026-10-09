package com.dazix.caelis.ar.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.dazix.caelis.ar.NormalGrid
import com.dazix.caelis.ar.NormalHorizon
import com.dazix.caelis.ar.paletteForTheme
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.arframe.SkyQuadMath
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.ConstellationFocusState
import com.dazix.caelis.domain.nightsky.DsoArtGeometry
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.PlanetAppearance
import com.dazix.caelis.domain.nightsky.PlanetState
import com.dazix.caelis.domain.nightsky.StarOptics
import com.google.android.filament.Texture
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the Night Sky screen draws on the GPU, owned in one place and driven by one
 * [NightSkyFrame] per vsync.
 *
 * Layers, in draw order (see [SkyPriority]): Milky Way, atmosphere, stars, constellation artwork,
 * DSO photographs and planet glows, constellation lines and planet discs, the stylised landscape or
 * the real terrain ring, the alt-az grid. Labels, DSO and meteor glyphs, the reticle and the
 * off-screen chevron are not here — they are text and screen-space vector UI, drawn by the Compose
 * overlay through the same [SkyProjector] this scene updates, so they cannot drift from the sky.
 *
 * Per-frame work is matrix writes, uniform writes and uploads only when something changed; the
 * frame loop allocates nothing.
 */
class NightSkyScene(
    private val context: Context,
    private val surface: SkySurface,
    private val catalog: NightSkyCatalog,
    starGeometry: StarGeometry,
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
    private val discMaterial = surface.loadMaterial("disc")

    private val milkyWay = FullScreenLayer(surface, milkyWayMaterial, SkyPriority.MILKY_WAY)
    private val atmosphere = FullScreenLayer(surface, atmosphereMaterial, SkyPriority.ATMOSPHERE)
    val stars = StarField(surface, starGeometry, starMaterial)
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
    private val starCel = starGeometry.celestial

    /** Decoded artwork, the same 64-entry LRU the Canvas renderer kept. */
    private val artworkCache = object : LruCache<String, Bitmap>(64) {}
    private val artTextures = HashMap<String, Texture>()
    private val missingArt = HashSet<String>()

    // Artwork is decoded off the main thread: decoding a constellation or DSO picture inline stalls
    // the frame for as long as the decode takes, every time a figure fades in during a pan. Until a
    // decode lands the object is simply drawn without its picture for a frame or two.
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "sky-art-decode").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoding = HashSet<String>()

    // Planets, the Sun and the Moon: glows and point cores in one disc batch, a textured quad each
    // once a body is large enough to show its face.
    private val planetGlows = DiscBatch(surface, discMaterial, MAX_PLANETS * 2, SkyPriority.CELESTIAL)
    private val planetQuads = arrayOfNulls<TexturedQuad>(MAX_PLANETS)
    private val planetQuadIds = arrayOfNulls<String>(MAX_PLANETS)
    private val planetResourceNames = HashMap<String, String>()

    /** What each drawn planet looked like this frame, by index into [NightSkyFrame.planets]. */
    val planetAppearance = Array(MAX_PLANETS) { PlanetAppearance() }

    /** Celestial unit vectors of this frame's planets (x, y, z per index). */
    val planetCel = FloatArray(MAX_PLANETS * 3)
    var planetCount = 0
        private set

    // Deep-sky photographs, created the first time an object comes near the view.
    val dsoCount: Int = catalog.dsosLength().coerceAtMost(MAX_DSOS)
    val dsoCel = FloatArray(dsoCount * 3)
    private val dsoArtName = arrayOfNulls<String>(dsoCount)
    private val dsoArtQuads = arrayOfNulls<TexturedQuad>(dsoCount)

    /** True for each DSO whose photograph is on screen this frame; the overlay skips its glyph. */
    val dsoArtShown = BooleanArray(dsoCount)
    private val reusableDso = DeepSkyObject()

    /** Column-major celestial -> ENU of the last [update]; shared with the overlay and hit test. */
    val celestialToEnu = FloatArray(16)
    private val cameraModel = FloatArray(16)
    private val reusableConstellation = Constellation()
    private val scratchCorners = FloatArray(12)
    private val scratchProjected = FloatArray(3)
    private var forcedSelectedStar = -1

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

        // Background layers. The planetarium sky is the Canvas renderer's opaque fill colour.
        if (frame.opaqueBackground) {
            if (nightVision) surface.setClearColor(8f / 255f, 0f, 0f, 1f)
            else surface.setClearColor(5f / 255f, 8f / 255f, 16f / 255f, 1f)
        } else {
            surface.setClearColor(0f, 0f, 0f, 0f)
        }
        milkyWay.setVisible(frame.showMilkyWay && milkyWayTexture != null)
        atmosphere.setVisible(frame.showAtmosphere)
        // While a profile is on its way the terrain ring is flat and rises into place when it lands.
        val realHorizon = frame.useRealHorizon
        landscape.setVisible(frame.showLandscape && !realHorizon && landscapeTexture != null)
        landscape.materialInstance.setParameter("azimuthOffset", Math.toRadians(frame.alignAzimuthOffsetDeg.toDouble()).toFloat())
        landscape.materialInstance.setParameter("elevationOffset", Math.toRadians(frame.alignElevationOffsetDeg.toDouble()).toFloat())
        terrain.setVisible(realHorizon)
        terrain.setOffsets(frame.alignAzimuthOffsetDeg, frame.alignElevationOffsetDeg)
        terrain.advance(frame.dtSeconds)
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
        updatePlanets(frame)
        updateDsoArt(frame)
        stars.flush()
    }

    private var destroyed = false

    /** Releases every engine object. Idempotent; must run before the [SkySurface] is destroyed. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        decoder.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        artQuads.forEach { it?.destroy() }
        planetQuads.forEach { it?.destroy() }
        dsoArtQuads.forEach { it?.destroy() }
        planetGlows.destroy()
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
        materials().forEach { engine.destroyMaterial(it) }
    }

    private fun materials() = listOf(
        starMaterial, lineMaterial, texturedMaterial, milkyWayMaterial,
        atmosphereMaterial, landscapeMaterial, terrainMaterial, discMaterial,
    )

    private val enuToCel3 = FloatArray(9)

    /** Column-major 3x3 of ENU -> celestial: the transpose of the rotation part of [celestialToEnu]. */
    private fun enuToCelestial3(): FloatArray {
        val m = celestialToEnu
        for (col in 0 until 3) for (row in 0 until 3) enuToCel3[col * 3 + row] = m[row * 4 + col]
        return enuToCel3
    }

    private fun readCatalog() {
        for (d in 0 until dsoCount) {
            catalog.dsos(reusableDso, d)
            celestialVector(reusableDso.ra().toDouble(), reusableDso.dec().toDouble(), dsoCel, d * 3)
            dsoArtName[d] = reusableDso.artResourceName()?.takeIf { it.isNotEmpty() }
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
        if (!activeChanged && frame.selectedStar == forcedSelectedStar) return
        // Stars of a visible or selected figure, and the selected star itself, bypass the telescope
        // limit (forceRenderStar). Both sets change rarely, so the union is simply rebuilt.
        stars.clearForced()
        for (c in 0 until constellationCount) {
            if (!active[c]) continue
            catalog.constellations(reusableConstellation, c)
            for (j in 0 until reusableConstellation.linesLength()) stars.force(reusableConstellation.lines(j))
        }
        forcedSelectedStar = frame.selectedStar
        if (forcedSelectedStar >= 0) stars.force(forcedSelectedStar)
    }

    private fun updatePlanets(frame: NightSkyFrame) {
        val planets = frame.planets
        val n = minOf(planets.size, MAX_PLANETS)
        planetCount = n
        val nv = frame.nightVisionActive
        val multiply = NightVisionColors.forTheme(frame.nightVisionTheme).artMultiply
        val focal = projector.focalLengthPixels
        val density = frame.density
        planetGlows.begin()
        for (i in 0 until n) {
            val planet = planets[i]
            celestialVector(planet.raDegrees, planet.decDegrees, planetCel, i * 3)
            val selected = frame.selectedPlanetId != null && planet.id.equals(frame.selectedPlanetId, ignoreCase = true)
            val quad = planetQuads[i]
            if (!frame.showPlanets && !selected) {
                quad?.setVisible(false)
                continue
            }
            val a = planetAppearance[i]
            a.compute(planet.id, planet.isMoon, planet.angularDiameterDeg, planet.magnitude, focal, density, frame.zoomRatio)

            // Glow colour; night vision is the old MULTIPLY filter. The Canvas left the point core
            // unfiltered, which put a white dot into red mode; here it is filtered like the glow.
            val rgb = PlanetAppearance.glowColorRgb(planet.id)
            var r = ((rgb shr 16) and 0xFF) / 255f
            var g = ((rgb shr 8) and 0xFF) / 255f
            var b = (rgb and 0xFF) / 255f
            if (nv) { r *= multiply[0]; g *= multiply[1]; b *= multiply[2] }
            val cx = planetCel[i * 3]; val cy = planetCel[i * 3 + 1]; val cz = planetCel[i * 3 + 2]

            val texture = if (a.textured) artTexture(planetResource(planet.id)) else null
            if (texture == null) {
                quad?.setVisible(false)
                if (a.textured) {
                    // Texture missing: a plain disc of the body's size.
                    planetGlows.addDisc(cx, cy, cz, a.fallbackHitRadiusPx(density), 0, r, g, b, 1f)
                } else {
                    planetGlows.addDisc(cx, cy, cz, a.glowRadiusPx, 1, r, g, b, a.glowAlpha)
                    planetGlows.addDisc(cx, cy, cz, a.coreRadiusPx, 0, r, g, b, 1f)
                }
                continue
            }
            planetGlows.addDisc(cx, cy, cz, a.glowRadiusPx, 1, r, g, b, a.glowAlpha)
            val q = quad ?: TexturedQuad(surface, texturedMaterial, SkyPriority.CELESTIAL_OVERLAY).also { planetQuads[i] = it }
            if (planetQuadIds[i] != planet.id) {
                q.setTexture(texture)
                q.setOpacity(1f)
                planetQuadIds[i] = planet.id
            }
            // Top of the image towards the celestial pole, which is +Y in the celestial frame.
            val halfWidth = a.diameterPx * a.imageToBodyRatio * 0.5f / focal
            val halfHeight = halfWidth * texture.getHeight(0) / texture.getWidth(0).coerceAtLeast(1)
            if (SkyQuadMath.orientedCorners(cx, cy, cz, 0f, 1f, 0f, halfWidth, halfHeight, scratchCorners)) {
                q.setCorners(scratchCorners)
            }
            q.setTransform(celestialToEnu)
            q.setVisible(true)
        }
        for (i in n until MAX_PLANETS) planetQuads[i]?.setVisible(false)
        planetGlows.end()
        planetGlows.setTransform(celestialToEnu)
    }

    private fun planetResource(id: String): String =
        planetResourceNames.getOrPut(id) { "planet_${id.lowercase()}" }

    /**
     * Deep-sky photographs. An object's texture is decoded the first time it comes within the
     * Canvas renderer's 100 px margin of the view, never for the whole catalogue at once.
     */
    private fun updateDsoArt(frame: NightSkyFrame) {
        val margin = 100f
        val w = projector.viewportWidth
        val h = projector.viewportHeight
        for (d in 0 until dsoCount) {
            dsoArtShown[d] = false
            val name = dsoArtName[d] ?: continue
            val selected = d == frame.selectedDso
            var quad = dsoArtQuads[d]
            if (!frame.showDsos && !selected) {
                quad?.setVisible(false)
                continue
            }
            val inFront = projector.projectCelestial(
                celestialToEnu, dsoCel[d * 3], dsoCel[d * 3 + 1], dsoCel[d * 3 + 2], scratchProjected,
            )
            val near = inFront && scratchProjected[0] in -margin..(w + margin) && scratchProjected[1] in -margin..(h + margin)
            if (!near) {
                quad?.setVisible(false)
                continue
            }
            if (quad == null) {
                val texture = artTexture(name) ?: continue
                catalog.dsos(reusableDso, d)
                DsoArtGeometry.corners(
                    reusableDso.ra().toDouble(), reusableDso.dec().toDouble(),
                    DsoArtGeometry.widthDeg(reusableDso.artWidthDeg(), reusableDso.sizeArcmin()),
                    texture.getHeight(0).toDouble() / texture.getWidth(0).coerceAtLeast(1),
                    reusableDso.positionAngleDeg().toDouble() + reusableDso.artRotationDeg().toDouble(),
                    scratchCorners,
                )
                quad = TexturedQuad(surface, texturedMaterial, SkyPriority.CELESTIAL).also {
                    it.setCorners(scratchCorners)
                    it.setTexture(texture)
                    it.setOpacity(230f / 255f)
                }
                dsoArtQuads[d] = quad
            }
            quad.setTransform(celestialToEnu)
            quad.setVisible(true)
            dsoArtShown[d] = true
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
        if (name in missingArt) return null
        val bitmap = artworkCache.get(name)
        if (bitmap == null) {
            requestDecode(name)
            return null
        }
        return createBitmapTexture(surface.engine, bitmap).also { artTextures[name] = it }
    }

    private fun requestDecode(name: String) {
        if (!decoding.add(name)) return
        decoder.execute {
            // Same decode as NightSkyRenderer.getOrLoadArtwork: unscaled ARGB_8888.
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            val bitmap = if (id == 0) null else BitmapFactory.decodeResource(
                context.resources,
                id,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            )
            mainHandler.post {
                if (destroyed) return@post
                decoding.remove(name)
                // Not every body has a picture (no planet_sun); do not look it up again.
                if (bitmap == null) missingArt.add(name) else artworkCache.put(name, bitmap)
            }
        }
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
        const val MAX_PLANETS = 16
        const val MAX_DSOS = 128
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
    /** Terrain skyline wanted: the real one once a profile exists, a flat ring until then. */
    var useRealHorizon = false
    var showGrid = false
    var showPlanets = true
    var showDsos = true
    var opaqueBackground = false
    var selectedConstellation = -1
    var selectedStar = -1
    var selectedDso = -1
    var selectedPlanetId: String? = null

    /** Planets, Sun and Moon for this frame; a reference to the view model's list, never copied. */
    var planets: List<PlanetState> = emptyList()
    var focusRadiusMultiplier = ConstellationFocusState.DEFAULT_FOCUS_RADIUS_MULTIPLIER
    var alignAzimuthOffsetDeg = 0f
    var alignElevationOffsetDeg = 0f

    var nightVisionActive = false
    var nightVisionTheme = NightVisionTheme.AMBER
}

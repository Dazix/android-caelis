package com.dazix.caelis.ui.solar

import android.graphics.Paint
import android.graphics.RectF
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dazix.caelis.R
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.pose.SkyPoseFrame
import com.dazix.caelis.ar.pose.SkyPoseRig
import com.dazix.caelis.ar.scene.CameraBackground
import com.dazix.caelis.ar.scene.GlyphBatch
import com.dazix.caelis.ar.scene.NormalTerrain
import com.dazix.caelis.ar.scene.LabelBatch
import com.dazix.caelis.ar.scene.LandmarkMarks
import com.dazix.caelis.ar.scene.NormalGoldSolar
import com.dazix.caelis.ar.scene.SpriteAtlas
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.BadgeOnArc
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import com.dazix.caelis.ar.scene.NormalSunrise
import com.dazix.caelis.ar.scene.NormalSunset
import com.dazix.caelis.ar.scene.SkyGridMarkers
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.SunArScene
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.arframe.TextBackdrop
import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.LunarCoordinates
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.SolarCoordinates
import com.dazix.caelis.domain.solar.SolarTrajectory
import com.dazix.caelis.domain.arframe.MarkClearance
import com.dazix.caelis.domain.solar.RiseSetBadge
import com.dazix.caelis.domain.solar.RiseSetBadgePolicy
import com.dazix.caelis.domain.solar.RiseSetBadges
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val MoonDisk = Color(0xFFE0F7FA)
private val MoonRise = Color(0xFF4FC3F7)
private val MoonSet = Color(0xFF26A69A)
private val Casing = Color(0x99000000)

/**
 * The Sun AR sky drawn by Filament ([SunArScene]: grid, terrain silhouette, day arcs and the marks
 * on them), with a Compose overlay only for the off-screen pointers.
 *
 * Each vsync latches the newest camera image, samples the pose at that image's instant, updates the
 * scene, lists its marks ([SunArMarks]) and renders. The bubbles, badges and discs were the Compose
 * overlay once, drawn with the same pose in the same vsync; but the TextureView puts a GPU frame on
 * screen 2-3 vsyncs later than the overlay, so held in the hand they jumped against the arcs. The
 * camera picture is drawn by the same Filament frame ([CameraBackground]) rather than by a
 * `PreviewView` underneath, for the same reason; the sky renders into a TextureView for the reason
 * Night Sky does (see `NightSkyGpuSky`).
 */
@Composable
internal fun SunArGpuSky(
    orientation: DeviceOrientation?,
    onGetRenderMatrix: (timestampNs: Long, out: FloatArray) -> Unit,
    rig: SkyPoseRig,
    displayRotation: Int,
    fovHorizontalDegrees: Float,
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    currentSunPos: SolarCoordinates?,
    currentMoonPos: LunarCoordinates?,
    horizonProfile: HorizonProfile?,
    shadeOccludedTrajectory: Boolean,
    terrainTimes: TerrainTimes,
    showPeakNames: Boolean,
    showRidgeLines: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * A virtual place is shown: no camera picture (it shows where the phone is), a neutral backdrop
     * and the place's terrain filled below its ridge instead, and the live pose every vsync.
     */
    virtualPlace: Boolean = false,
) {
    val density = LocalDensity.current.density
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    // With the real skyline drawn, the badges say when and where the body meets that skyline.
    val badges = remember(solarTrajectory, lunarTrajectory, terrainTimes) {
        val t = terrainTimes
        RiseSetBadges(
            sunrise = RiseSetBadgePolicy.sun(solarTrajectory.sunriseMarker, t.sunrise, t.terrainActive, t.hiddenAllDay),
            sunset = RiseSetBadgePolicy.sun(solarTrajectory.sunsetMarker, t.sunset, t.terrainActive, t.hiddenAllDay),
            moonrise = RiseSetBadgePolicy.moon(lunarTrajectory.moonriseMarker, t.moonrise, t.terrainActive, t.moonHiddenAllDay),
            moonset = RiseSetBadgePolicy.moon(lunarTrajectory.moonsetMarker, t.moonset, t.terrainActive, t.moonHiddenAllDay),
        )
    }
    var surface by remember { mutableStateOf<SkySurface?>(null) }
    var scene by remember { mutableStateOf<SunArScene?>(null) }
    var viewportWidth by remember { mutableStateOf(1f) }
    var viewportHeight by remember { mutableStateOf(1f) }

    // Hidden until the first frame is drawn (the first one compiles the line shader), then faded in.
    var revealed by remember { mutableStateOf(false) }
    val skyAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "sunArReveal",
    )

    DisposableEffect(surface) {
        val built = surface?.let { SunArScene(it) }
        scene = built
        onDispose { built?.destroy() }
    }
    var background by remember { mutableStateOf<CameraBackground?>(null) }
    DisposableEffect(surface) {
        val built = surface?.let { CameraBackground(it) }
        background = built
        onDispose { built?.destroy() }
    }
    SideEffect {
        scene?.setVirtualBackdrop(virtualPlace)
        scene?.setProfile(horizonProfile, showRidgeLines)
        scene?.setTrajectories(
            solarTrajectory,
            lunarTrajectory,
            shadeProfile = if (shadeOccludedTrajectory) horizonProfile else null,
            density = density,
        )
    }

    val currentOrientation by rememberUpdatedState(orientation)
    val currentGetMatrix by rememberUpdatedState(onGetRenderMatrix)
    val renderMatrix = remember { FloatArray(16) }
    val currentRotation by rememberUpdatedState(displayRotation)
    val currentFov by rememberUpdatedState(fovHorizontalDegrees)
    val currentRig by rememberUpdatedState(rig)
    val currentPictureWanted by rememberUpdatedState(!virtualPlace)
    val currentSolar by rememberUpdatedState(solarTrajectory)
    val currentLunar by rememberUpdatedState(lunarTrajectory)
    val currentBadges by rememberUpdatedState(badges)
    val currentSun by rememberUpdatedState(currentSunPos)
    val currentMoon by rememberUpdatedState(currentMoonPos)
    // Peak names exist only on a profile that is drawn, and only while the layer is switched on.
    val peakNames = if (showPeakNames) horizonProfile?.drawnLandmarks.orEmpty() else emptyList()
    val currentPeakNames by rememberUpdatedState(peakNames)
    val currentLanguageTag by rememberUpdatedState(languageTag)
    val fontScale = LocalDensity.current.fontScale
    val currentFontScale by rememberUpdatedState(fontScale)
    val marks = remember { SunArMarks() }
    marks.grid.updateLabels(
        stringResource(R.string.sun_ar_zenith),
        stringResource(R.string.sun_ar_nadir),
        arrayOf(
            stringResource(R.string.sun_ar_cardinal_n),
            stringResource(R.string.sun_ar_cardinal_ne),
            stringResource(R.string.sun_ar_cardinal_e),
            stringResource(R.string.sun_ar_cardinal_se),
            stringResource(R.string.sun_ar_cardinal_s),
            stringResource(R.string.sun_ar_cardinal_sw),
            stringResource(R.string.sun_ar_cardinal_w),
            stringResource(R.string.sun_ar_cardinal_nw),
        ),
    )
    val poseFrame = remember { SkyPoseFrame() }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { nanos ->
                val sc = scene
                val sf = surface
                if (sc != null && sf != null && currentOrientation != null) {
                    // The newest camera image goes to the GPU first, and the pose is the one it was
                    // taken with, not the newest sensor sample: the picture is tens of milliseconds
                    // old, and the newest pose ran the overlay ahead of the ridge on every turn
                    // (PoseTimestampPolicy). With no image yet the pose follows the phone live.
                    //
                    // The rig also decides who owns the camera (CameraX or ARCore) and whose pose
                    // draws: ARCore's own matrix for the frame it just handed over, or the sensors'
                    // at the image's instant. Sun AR has no zoom, so the zoom is always 1x.
                    //
                    // Under a virtual place the picture is not wanted: the rig parks the camera (as
                    // Night Sky does outside planetarium mode) and the pose is the live one.
                    val bg = background
                    val pictureWanted = currentPictureWanted
                    bg?.enabled = pictureWanted
                    currentRig.advance(
                        background = bg,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        displayRotation = currentRotation,
                        zoomRatio = 1f,
                        pictureEnabled = pictureWanted,
                        livePoseAllowed = true,
                        out = poseFrame,
                    )
                    if (poseFrame.useRigMatrix) {
                        poseFrame.matrix.copyInto(renderMatrix, 0, 0, 16)
                    } else {
                        currentGetMatrix(poseFrame.poseTimestampNs, renderMatrix)
                    }
                    sc.update(renderMatrix, currentRotation * 90, viewportWidth, viewportHeight, currentFov, nanos)
                    // Bubbles, badges, discs and grid labels are part of this GPU frame.
                    marks.update(
                        sc, currentSolar, currentLunar, currentBadges, currentSun, currentMoon,
                        currentPeakNames, currentLanguageTag, density, currentFontScale,
                    )
                    sf.renderFrame(nanos)
                    revealed = true
                    frameTick = nanos
                }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { surface = SkySurface(ctx, it, ownFrameLoop = false) } },
            onRelease = {
                // Whichever of this and the scene's DisposableEffect runs first, the scene and the
                // camera picture are released before the engine they live in (both destroys are
                // idempotent).
                scene?.destroy()
                scene = null
                background?.destroy()
                background = null
                surface?.destroy()
                surface = null
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = skyAlpha }
                .onSizeChanged {
                    viewportWidth = it.width.toFloat()
                    viewportHeight = it.height.toFloat()
                },
        )
        val sc = scene
        if (sc != null && revealed) {
            SunArOverlay(
                projector = sc.projector,
                frameTick = frameTick,
                currentSunPos = currentSunPos,
                currentMoonPos = currentMoonPos,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = skyAlpha },
            )
        }
    }
}

/**
 * What Sun AR draws as marks in its GPU frame ([SunArScene.markLabels] / [SunArScene.markGlyphs]):
 * the grid's labels, the time bubbles, the rise/set badges and the live sun and moon. They were the
 * Compose overlay once, which the TextureView showed 2-3 vsyncs apart from the arcs. Bubbles and
 * badges are drawn into the scene's atlas as whole sprites (pill, border and text) when a
 * trajectory, the density or the font scale changes; each frame only lists them.
 *
 * Paints, sizes and colours are the Canvas ones. Zero allocations per frame.
 */
internal class SunArMarks {
    val grid = SkyGridMarkers()
    private val peaks = LandmarkMarks()
    private val rect = RectF()
    private val projected = FloatArray(3)
    private val direction = FloatArray(3)

    private val bubbleText = textPaint(android.graphics.Color.WHITE)
    private val bubbleBg = fillPaint(android.graphics.Color.argb(220, 20, 20, 20))
    private val bubbleBorder = strokePaint(android.graphics.Color.argb(255, 255, 179, 0))
    private val markerText = textPaint(android.graphics.Color.WHITE)
    private val markerBg = fillPaint(android.graphics.Color.argb(230, 20, 20, 20))
    private val markerBorder = strokePaint(android.graphics.Color.argb(255, 255, 179, 0))

    private var builtScene: SunArScene? = null
    private var builtSolar: SolarTrajectory? = null
    private var builtLunar: LunarTrajectory? = null
    private var builtBadges: RiseSetBadges? = null
    private var builtDensity = 0f
    private var builtFontScale = 0f
    private var builtGridKey = -1
    private var builtPeaksRevision = -1
    private var sunBubbleSprites = IntArray(0)
    private var moonBubbleSprites = IntArray(0)
    private val markerSprites = IntArray(4) { -1 }

    // Each badge's slot: its sky direction, its pill's half width and a point of its own arc below the
    // horizon (before the dot at a rise, after it at a set), and this frame's projection of the pill's
    // centre, so a time bubble under a badge can be left out (MarkClearance).
    private val markerAzimuth = DoubleArray(4)
    private val markerElevation = DoubleArray(4)
    private val markerHalfWidth = FloatArray(4)
    private val markerScreenX = FloatArray(4)
    private val markerScreenY = FloatArray(4)
    private val markerOnScreen = BooleanArray(4)
    private val belowKnown = BooleanArray(4)
    private val belowAzimuth = DoubleArray(4)
    private val belowElevation = DoubleArray(4)
    private val pillOffsetX = FloatArray(4)
    private val pillOffsetY = FloatArray(4)
    private val pillOffset = FloatArray(2)
    private var badgeHalfHeight = 0f
    private var bubbleHalfWidth = 0f
    private var bubbleHalfHeight = 0f

    /**
     * Lists this frame's marks into [scene], after `scene.update` and before the frame is rendered.
     * The sun and moon discs are listed while they are on screen; off it, the Compose pointer
     * ([SunArOverlay]) takes over.
     */
    fun update(
        scene: SunArScene,
        solar: SolarTrajectory,
        lunar: LunarTrajectory,
        badges: RiseSetBadges,
        sunPos: SolarCoordinates?,
        moonPos: LunarCoordinates?,
        peakNames: List<HorizonLandmark>,
        languageTag: String,
        density: Float,
        fontScale: Float,
    ) {
        peaks.update(peakNames, languageTag, NormalTerrain.toArgb(), density, fontScale, TextBackdrop.NAME_ARGB)
        ensureAtlas(scene, solar, lunar, badges, density, fontScale)
        val labels = scene.markLabels
        val glyphs = scene.markGlyphs
        val projector = scene.projector
        val d = direction
        labels.begin()
        glyphs.begin()
        grid.emit(labels, glyphs)

        // What the peak names have to keep clear of: the badges, then the bubbles that were drawn.
        peaks.beginClearance()
        for (slot in 0 until 4) {
            markerOnScreen[slot] = markerSprites[slot] >= 0 &&
                projector.projectAltAz(markerAzimuth[slot], markerElevation[slot], projected)
            if (!markerOnScreen[slot]) continue
            val dotX = projected[0]
            val dotY = projected[1]
            // The pill lies on the arc, on its stretch below the horizon: towards the arc's point there,
            // or straight down when the arc is not known or that point is behind the camera.
            var towardX = 0f
            var towardY = 1f
            if (belowKnown[slot] && projector.projectAltAz(belowAzimuth[slot], belowElevation[slot], projected)) {
                towardX = projected[0] - dotX
                towardY = projected[1] - dotY
            }
            BadgeOnArc.offset(
                towardX, towardY, markerHalfWidth[slot], badgeHalfHeight,
                BADGE_GAP_DP * density, BADGE_MIN_GAP_DP * density, pillOffset,
            )
            pillOffsetX[slot] = pillOffset[0]
            pillOffsetY[slot] = pillOffset[1]
            markerScreenX[slot] = dotX + pillOffset[0]
            markerScreenY[slot] = dotY + pillOffset[1]
            peaks.reserve(markerScreenX[slot], markerScreenY[slot], markerHalfWidth[slot], badgeHalfHeight)
        }

        // Only points above -15° are labelled, as only those stretches of the arcs are drawn.
        val sunBubbles = solar.timeBubbles
        for (i in sunBubbles.indices) {
            val c = sunBubbles[i].coordinates
            if (c.apparentElevationDegrees < MIN_BUBBLE_ELEVATION) continue
            if (!bubbleFits(projector, c.azimuthDegrees, c.apparentElevationDegrees)) continue
            AltAzGridBuilder.direction(c.azimuthDegrees, c.apparentElevationDegrees, d)
            labels.add(sunBubbleSprites.getOrElse(i) { -1 }, d[0], d[1], d[2])
        }
        val moonBubbles = lunar.timeBubbles
        for (i in moonBubbles.indices) {
            val c = moonBubbles[i].coordinates
            if (c.apparentElevationDegrees < MIN_BUBBLE_ELEVATION) continue
            if (!bubbleFits(projector, c.azimuthDegrees, c.apparentElevationDegrees)) continue
            AltAzGridBuilder.direction(c.azimuthDegrees, c.apparentElevationDegrees, d)
            labels.add(moonBubbleSprites.getOrElse(i) { -1 }, d[0], d[1], d[2])
        }

        if (markerSprites[0] >= 0) addMarkerAt(labels, glyphs, 0, NormalSunrise, density)
        if (markerSprites[1] >= 0) addMarkerAt(labels, glyphs, 1, NormalSunset, density)
        if (markerSprites[2] >= 0) addMarkerAt(labels, glyphs, 2, MoonRise, density)
        if (markerSprites[3] >= 0) addMarkerAt(labels, glyphs, 3, MoonSet, density)

        val shape = GlyphBatch.Shape
        if (sunPos != null && isOnScreen(scene.projector, sunPos.azimuthDegrees, sunPos.apparentElevationDegrees)) {
            AltAzGridBuilder.direction(sunPos.azimuthDegrees, sunPos.apparentElevationDegrees, d)
            glyphs.add(shape.DISC, d[0], d[1], d[2], 11f * density, 0f, NormalGoldSolar.toArgb())
            glyphs.add(shape.DISC, d[0], d[1], d[2], 4f * density, 0f, android.graphics.Color.WHITE)
            glyphs.add(shape.RING, d[0], d[1], d[2], 11.5f * density, 1.2f * density, Casing.toArgb())
        }
        if (moonPos != null && isOnScreen(scene.projector, moonPos.azimuthDegrees, moonPos.apparentElevationDegrees)) {
            AltAzGridBuilder.direction(moonPos.azimuthDegrees, moonPos.apparentElevationDegrees, d)
            glyphs.add(shape.DISC, d[0], d[1], d[2], 10.5f * density, 0f, SunArScene.MoonArc.toArgb())
            glyphs.add(shape.DISC, d[0], d[1], d[2], 7.5f * density, 0f, MoonDisk.toArgb())
            glyphs.add(shape.DISC, d[0], d[1], d[2], 3.5f * density, 0f, android.graphics.Color.WHITE)
            glyphs.add(shape.RING, d[0], d[1], d[2], 11f * density, 1.2f * density, Casing.toArgb())
        }
        // Last, so every peak name yields to a bubble or a badge it would land on.
        peaks.emit(labels, projector)
        glyphs.end()
        labels.end()
    }

    private fun isOnScreen(projector: SkyProjector, azimuth: Double, elevation: Double): Boolean {
        if (!projector.projectAltAz(azimuth, elevation, projected)) return false
        return projected[0] in 0f..projector.viewportWidth && projected[1] in 0f..projector.viewportHeight
    }

    /**
     * Whether a time bubble at this direction may be drawn: not when it would land on one of this
     * frame's badges. A bubble that is drawn on screen is reserved, so the peak names keep off it.
     */
    private fun bubbleFits(projector: SkyProjector, azimuth: Double, elevation: Double): Boolean {
        if (!projector.projectAltAz(azimuth, elevation, projected)) return true
        val x = projected[0]
        val y = projected[1]
        for (slot in 0 until 4) {
            if (!markerOnScreen[slot]) continue
            if (MarkClearance.overlaps(
                    markerScreenX[slot], markerScreenY[slot], markerHalfWidth[slot], badgeHalfHeight,
                    x, y, bubbleHalfWidth, bubbleHalfHeight,
                )
            ) return false
            // Nor one standing on the stretch of arc between the dot and the pill: it would sit between
            // the badge and the point the badge names.
            if (BadgeOnArc.segmentHitsRect(
                    markerScreenX[slot] - pillOffsetX[slot], markerScreenY[slot] - pillOffsetY[slot],
                    markerScreenX[slot], markerScreenY[slot],
                    x, y, bubbleHalfWidth, bubbleHalfHeight,
                )
            ) return false
        }
        peaks.reserve(x, y, bubbleHalfWidth, bubbleHalfHeight)
        return true
    }

    /**
     * A rise or set badge: a dot on the horizon (or on the skyline) and, on the arc below it, the pill with
     * the time and azimuth: before the dot at a rise, after it at a set.
     */
    private fun addMarkerAt(
        labels: LabelBatch,
        glyphs: GlyphBatch,
        slot: Int,
        accent: Color,
        density: Float,
    ) {
        val d = direction
        AltAzGridBuilder.direction(markerAzimuth[slot], markerElevation[slot], d)
        glyphs.add(GlyphBatch.Shape.DISC, d[0], d[1], d[2], BADGE_DOT_DP * density, 0f, accent.toArgb())
        // The sprite is anchored on the dot with its pill BADGE_LIFT_DP above; the offset moves the pill
        // from there to its place on the arc. Off screen the GPU hides it with the dot.
        if (markerOnScreen[slot]) {
            labels.add(markerSprites[slot], d[0], d[1], d[2], pillOffsetX[slot], pillOffsetY[slot] + BADGE_LIFT_DP * density)
        } else {
            labels.add(markerSprites[slot], d[0], d[1], d[2])
        }
    }

    /**
     * Redraws the atlas when its labels changed: a trajectory, the badges, the density, the font
     * scale, the grid.
     */
    private fun ensureAtlas(
        scene: SunArScene,
        solar: SolarTrajectory,
        lunar: LunarTrajectory,
        badges: RiseSetBadges,
        density: Float,
        fontScale: Float,
    ) {
        val sp = density * fontScale
        grid.horizonTextPaint.textSize = 5.3f * sp
        grid.gridTextPaint.textSize = 4.5f * sp
        if (scene === builtScene && solar === builtSolar && lunar === builtLunar && badges === builtBadges &&
            density == builtDensity && fontScale == builtFontScale && grid.contentKey == builtGridKey &&
            peaks.revision == builtPeaksRevision
        ) return
        builtScene = scene
        builtSolar = solar
        builtLunar = lunar
        builtBadges = badges
        builtDensity = density
        builtFontScale = fontScale
        builtGridKey = grid.contentKey
        builtPeaksRevision = peaks.revision
        bubbleText.textSize = 11f * sp
        bubbleBorder.strokeWidth = 1.2f * density
        markerText.textSize = 12f * sp
        markerBorder.strokeWidth = 1.5f * density

        val atlas = scene.markAtlas
        atlas.begin()
        grid.addSprites(atlas)
        peaks.addSprites(atlas)
        // A bubble is the same sprite wherever its time repeats on the same arc.
        val shared = HashMap<String, Int>()
        sunBubbleSprites = IntArray(solar.timeBubbles.size) {
            val text = solar.timeBubbles[it].formattedTime
            shared.getOrPut("s$text") { addBubble(atlas, text, SUN_BORDER_ARGB, density) }
        }
        moonBubbleSprites = IntArray(lunar.timeBubbles.size) {
            val text = lunar.timeBubbles[it].formattedTime
            shared.getOrPut("m$text") { addBubble(atlas, text, MOON_BORDER_ARGB, density) }
        }
        bubbleHalfWidth = BUBBLE_WIDTH_DP * density * 0.5f
        bubbleHalfHeight = BUBBLE_HEIGHT_DP * density * 0.5f
        // The box a badge keeps clear is its pill; the dot stands beside it, outside.
        badgeHalfHeight = BADGE_HEIGHT_DP * density * 0.5f
        val solarPath = solar.pathPoints
        val lunarPath = lunar.pathPoints
        setBadge(atlas, 0, badges.sunrise, "🌅", NormalSunrise, density, beforeDot = true, solarPath.size, { solarPath[it].coordinates.azimuthDegrees }, { solarPath[it].coordinates.apparentElevationDegrees })
        setBadge(atlas, 1, badges.sunset, "🌇", NormalSunset, density, beforeDot = false, solarPath.size, { solarPath[it].coordinates.azimuthDegrees }, { solarPath[it].coordinates.apparentElevationDegrees })
        setBadge(atlas, 2, badges.moonrise, "🌙", MoonRise, density, beforeDot = true, lunarPath.size, { lunarPath[it].coordinates.azimuthDegrees }, { lunarPath[it].coordinates.apparentElevationDegrees })
        setBadge(atlas, 3, badges.moonset, "🌘", MoonSet, density, beforeDot = false, lunarPath.size, { lunarPath[it].coordinates.azimuthDegrees }, { lunarPath[it].coordinates.apparentElevationDegrees })
        atlas.end()
    }

    private fun setBadge(
        atlas: SpriteAtlas,
        slot: Int,
        badge: RiseSetBadge?,
        emoji: String,
        accent: Color,
        density: Float,
        beforeDot: Boolean,
        pathSize: Int,
        pathAzimuth: (Int) -> Double,
        pathElevation: (Int) -> Double,
    ) {
        belowKnown[slot] = false
        if (badge == null) {
            markerSprites[slot] = -1
            return
        }
        val label = markerLabel(emoji, badge.formattedTime, badge.azimuthDegrees)
        markerSprites[slot] = addBadge(atlas, label, accent, density)
        markerAzimuth[slot] = badge.azimuthDegrees
        markerElevation[slot] = badge.apparentElevationDegrees
        markerHalfWidth[slot] = (markerText.measureText(label) + 16f * density) * 0.5f
        findBelowPoint(slot, badge, beforeDot, pathSize, pathAzimuth, pathElevation)
    }

    /**
     * A point of the day's arc [ARC_REACH_DEGREES] of sky from the badge's crossing, on the stretch that runs
     * below the horizon: earlier in the day than the crossing at a rise, later at a set (or the end of the
     * sampled path, if that comes first). It is a sky direction; each frame projects it, and the line from
     * the dot to it is the way the arc leaves the dot on the screen. Found when the atlas is built: a badge
     * changes with its trajectory.
     */
    private fun findBelowPoint(
        slot: Int,
        badge: RiseSetBadge,
        beforeDot: Boolean,
        pathSize: Int,
        pathAzimuth: (Int) -> Double,
        pathElevation: (Int) -> Double,
    ) {
        if (pathSize < 3) return
        var nearest = 0
        var nearestDistance = Double.MAX_VALUE
        for (i in 0 until pathSize) {
            val distance = skyDistanceSquared(badge.azimuthDegrees, badge.apparentElevationDegrees, pathAzimuth(i), pathElevation(i))
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = i
            }
        }
        val reachSquared = ARC_REACH_DEGREES * ARC_REACH_DEGREES
        var end = nearest
        val step = if (beforeDot) -1 else 1
        while (end + step in 0 until pathSize &&
            skyDistanceSquared(pathAzimuth(end), pathElevation(end), badge.azimuthDegrees, badge.apparentElevationDegrees) < reachSquared
        ) end += step
        if (end == nearest) return
        belowAzimuth[slot] = pathAzimuth(end)
        belowElevation[slot] = pathElevation(end)
        belowKnown[slot] = true
    }

    /** Squared angular distance in degrees, azimuth wrapped and shortened by the elevation; for ordering and a reach, not for navigation. */
    private fun skyDistanceSquared(az0: Double, el0: Double, az1: Double, el1: Double): Double {
        var dAz = abs(az0 - az1) % 360.0
        if (dAz > 180.0) dAz = 360.0 - dAz
        val dAzScaled = dAz * cos(Math.toRadians((el0 + el1) * 0.5))
        val dEl = el0 - el1
        return dAzScaled * dAzScaled + dEl * dEl
    }

    /** A time pill, 46 x 21 dp, centred on its point of the arc. */
    private fun addBubble(atlas: SpriteAtlas, text: String, borderArgb: Int, density: Float): Int {
        val pw = BUBBLE_WIDTH_DP * density
        val ph = BUBBLE_HEIGHT_DP * density
        val border = bubbleBorder.strokeWidth
        val w = ceil(pw + border).toInt() + 2
        val h = ceil(ph + border).toInt() + 2
        val ax = w * 0.5f
        val ay = h * 0.5f
        return atlas.add(w, h, ax, ay) { canvas, left, top ->
            val cx = left + ax
            val cy = top + ay
            rect.set(cx - pw * 0.5f, cy - ph * 0.5f, cx + pw * 0.5f, cy + ph * 0.5f)
            canvas.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, bubbleBg)
            bubbleBorder.color = borderArgb
            canvas.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, bubbleBorder)
            canvas.drawText(text, cx, cy + bubbleText.textSize * 0.35f, bubbleText)
        }
    }

    /** A badge pill, 26 dp tall, centred 16 dp above its dot (the anchor). */
    private fun addBadge(atlas: SpriteAtlas, label: String, accent: Color, density: Float): Int {
        val pw = markerText.measureText(label) + 16f * density
        val ph = BADGE_HEIGHT_DP * density
        val lift = BADGE_LIFT_DP * density
        val border = markerBorder.strokeWidth
        val w = ceil(pw + border).toInt() + 2
        val h = ceil(ph + border).toInt() + 2
        val ax = w * 0.5f
        val ay = h * 0.5f + lift
        return atlas.add(w, h, ax, ay) { canvas, left, top ->
            val cx = left + w * 0.5f
            val cy = top + h * 0.5f
            rect.set(cx - pw * 0.5f, cy - ph * 0.5f, cx + pw * 0.5f, cy + ph * 0.5f)
            markerBorder.color = accent.toArgb()
            canvas.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, markerBg)
            canvas.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, markerBorder)
            canvas.drawText(label, cx, cy + markerText.textSize * 0.35f, markerText)
        }
    }

    private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
    }

    private companion object {
        const val MIN_BUBBLE_ELEVATION = -15.0
        const val BUBBLE_WIDTH_DP = 46f
        const val BUBBLE_HEIGHT_DP = 21f
        const val BADGE_HEIGHT_DP = 26f
        const val BADGE_LIFT_DP = 16f
        const val BADGE_DOT_DP = 4f

        /** What the rectangle placement left between the dot and the pill's bounding box; [BadgeOnArc.offset] keeps 0.75 of what that meant to the outline. */
        const val BADGE_GAP_DP = 6f

        /** The least from the dot to the pill's outline: the dot's radius ([BADGE_DOT_DP]) and 1 dp of air. */
        const val BADGE_MIN_GAP_DP = 5f

        /** How far along the arc, in degrees of sky, the point is that says which way the arc leaves the dot. */
        const val ARC_REACH_DEGREES = 6.0
        val SUN_BORDER_ARGB = android.graphics.Color.argb(255, 255, 179, 0)
        val MOON_BORDER_ARGB = android.graphics.Color.argb(180, 128, 222, 234)
    }
}

/** Paints and scratch space for [SunArOverlay], allocated once. Colours are the Canvas ones. */
internal class SunArPointerPaints {
    val rect = RectF()
    val projected = FloatArray(3)
    val bearing = FloatArray(2)
    val pointerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.argb(255, 255, 179, 0)
    }
    val pointerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(220, 20, 20, 20)
    }
    val pointerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.argb(255, 255, 179, 0)
    }

    companion object {
        val MOON_BORDER_ARGB = android.graphics.Color.argb(180, 128, 222, 234)
    }
}

/**
 * The Compose half of Sun AR: the pointer to the sun or the moon while it is off screen. It sits on
 * the screen edge, not on the sky, so it may lag the GPU frame by the vsync or two a TextureView
 * takes without anything sliding against the picture; every mark that does sit on the sky is in
 * the GPU frame ([SunArMarks]). [frameTick] only has to change to redraw it after [projector] moved.
 */
@Composable
internal fun SunArOverlay(
    projector: SkyProjector,
    frameTick: Long,
    currentSunPos: SolarCoordinates?,
    currentMoonPos: LunarCoordinates?,
    modifier: Modifier = Modifier,
) {
    val paints = remember { SunArPointerPaints() }
    // Strings built when the data changes, not on every frame.
    val sunPointerLabel = remember(currentSunPos) { currentSunPos?.let { signedDegrees(it.elevationDegrees) } }
    val moonPointerLabel = remember(currentMoonPos) { currentMoonPos?.let { "${it.phaseGlyph} ${signedDegrees(it.elevationDegrees)}" } }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        val w = size.width
        val h = size.height
        paints.pointerText.textSize = 11.sp.toPx()
        paints.pointerBorder.strokeWidth = 1.2.dp.toPx()

        if (currentSunPos != null) {
            val az = currentSunPos.azimuthDegrees
            val el = currentSunPos.apparentElevationDegrees
            val inFront = projector.projectAltAz(az, el, paints.projected)
            val x = paints.projected[0]
            val y = paints.projected[1]
            if (!(inFront && x in 0f..w && y in 0f..h)) drawOffscreenPointer(projector, paints, az, el, sunPointerLabel!!, isMoon = false)
        }
        if (currentMoonPos != null) {
            val az = currentMoonPos.azimuthDegrees
            val el = currentMoonPos.apparentElevationDegrees
            val inFront = projector.projectAltAz(az, el, paints.projected)
            val x = paints.projected[0]
            val y = paints.projected[1]
            if (!(inFront && x in 0f..w && y in 0f..h)) drawOffscreenPointer(projector, paints, az, el, moonPointerLabel!!, isMoon = true)
        }
    }
}

private fun markerLabel(emoji: String, time: String, azimuthDegrees: Double) = "$emoji $time (${azimuthDegrees.toInt()}°)"

private fun signedDegrees(elevation: Double) = "${if (elevation >= 0) "+" else ""}${elevation.toInt()}°"

/**
 * The sun or moon pinned to the screen edge in the direction to turn, with an arrow outwards and
 * its elevation on a tag inwards. The edge keeps clear of the top bar and the bottom controls.
 */
private fun DrawScope.drawOffscreenPointer(
    projector: SkyProjector,
    paints: SunArPointerPaints,
    azimuth: Double,
    elevation: Double,
    tag: String,
    isMoon: Boolean,
) {
    val az = Math.toRadians(azimuth)
    val el = Math.toRadians(elevation)
    projector.screenBearing(
        (sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat(), paints.bearing,
    )
    val dirX = paints.bearing[0]
    val dirY = paints.bearing[1]
    val w = size.width
    val h = size.height

    val marginX = 46.dp.toPx()
    val marginTop = 92.dp.toPx()
    val marginBottom = 150.dp.toPx()
    val cx = w * 0.5f
    val cy = h * 0.5f
    val halfW = cx - marginX
    val halfH = if (dirY < 0f) cy - marginTop else cy - marginBottom
    val tx = if (abs(dirX) > 0.0001f) halfW / abs(dirX) else Float.MAX_VALUE
    val ty = if (abs(dirY) > 0.0001f) halfH / abs(dirY) else Float.MAX_VALUE
    val t = minOf(tx, ty)
    val edgeX = (cx + dirX * t).coerceIn(marginX, w - marginX)
    val edgeY = (cy + dirY * t).coerceIn(marginTop, h - marginBottom)
    val edge = Offset(edgeX, edgeY)

    val accent = if (isMoon) SunArScene.MoonArc else NormalGoldSolar
    val bodyRadius = 6.5.dp.toPx()
    if (isMoon) {
        drawCircle(color = accent, radius = bodyRadius, center = edge)
        drawCircle(color = MoonDisk, radius = bodyRadius - 1.5.dp.toPx(), center = edge)
    } else {
        drawCircle(color = accent, radius = bodyRadius, center = edge)
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = edge)
    }
    drawCircle(color = Casing, radius = bodyRadius + 0.5.dp.toPx(), center = edge, style = Stroke(width = 1.0.dp.toPx()))
    if (!isMoon) {
        val rayInner = bodyRadius + 2.0.dp.toPx()
        val rayOuter = rayInner + 3.0.dp.toPx()
        for (i in 0 until 8) {
            val angle = (i * Math.PI / 4.0).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = accent,
                start = Offset(edgeX + cosA * rayInner, edgeY + sinA * rayInner),
                end = Offset(edgeX + cosA * rayOuter, edgeY + sinA * rayOuter),
                strokeWidth = 1.4.dp.toPx(),
            )
        }
    }

    val arrowDist = 18.dp.toPx()
    val arrowLen = 10.dp.toPx()
    val arrowSpread = 6.dp.toPx()
    val tip = Offset(edgeX + dirX * arrowDist, edgeY + dirY * arrowDist)
    val perpX = -dirY
    val perpY = dirX
    val baseLeft = Offset(tip.x - dirX * arrowLen + perpX * arrowSpread, tip.y - dirY * arrowLen + perpY * arrowSpread)
    val baseRight = Offset(tip.x - dirX * arrowLen - perpX * arrowSpread, tip.y - dirY * arrowLen - perpY * arrowSpread)
    drawLine(color = Casing, start = tip, end = baseLeft, strokeWidth = 3.2.dp.toPx())
    drawLine(color = Casing, start = tip, end = baseRight, strokeWidth = 3.2.dp.toPx())
    drawLine(color = accent, start = tip, end = baseLeft, strokeWidth = 2.0.dp.toPx())
    drawLine(color = accent, start = tip, end = baseRight, strokeWidth = 2.0.dp.toPx())

    val textW = paints.pointerText.measureText(tag) + 12.dp.toPx()
    val ph = 18.dp.toPx()
    val radius = ph * 0.5f
    val tagDist = 20.dp.toPx()
    val tagX = edgeX - dirX * tagDist
    val tagY = edgeY - dirY * tagDist
    val left = tagX - textW * 0.5f
    val top = tagY - ph * 0.5f
    paints.rect.set(left, top, left + textW, top + ph)
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(paints.rect, radius, radius, paints.pointerBg)
    val border = paints.pointerBorder.color
    if (isMoon) paints.pointerBorder.color = SunArPointerPaints.MOON_BORDER_ARGB
    canvas.drawRoundRect(paints.rect, radius, radius, paints.pointerBorder)
    paints.pointerBorder.color = border
    canvas.drawText(tag, tagX, top + ph * 0.5f + paints.pointerText.textSize * 0.35f, paints.pointerText)
}

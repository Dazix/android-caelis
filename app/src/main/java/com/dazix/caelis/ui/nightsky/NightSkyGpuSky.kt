package com.dazix.caelis.ui.nightsky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.dazix.caelis.R
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.pose.SkyPoseFrame
import com.dazix.caelis.ar.pose.SkyPoseRig
import com.dazix.caelis.ar.scene.CameraBackground
import com.dazix.caelis.ar.scene.NightSkyFrame
import com.dazix.caelis.ar.scene.NightSkyScene
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.StarGeometry
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.NightVisionState
import com.dazix.caelis.domain.nightsky.PlanetState
import com.dazix.caelis.domain.nightsky.SiderealTime
import com.dazix.caelis.domain.nightsky.TimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The live GPU scene and the frame it was last drawn from, for the tap handler: hit testing
 * projects against exactly what is on screen (see [NightSkyRenderer.prepareHitTest]).
 */
class NightSkyGpuHandle {
    var scene: NightSkyScene? = null
        internal set
    val frame = NightSkyFrame()
}

/**
 * The night sky drawn by Filament, names, glyphs and reticle included, with a thin Compose overlay
 * for what sits on the screen edge rather than the sky (the off-screen chevron) and debug layers.
 *
 * Takes the same inputs as the Canvas `NightSkyOverlayCanvas` it replaces. In each vsync the frame
 * loop samples the pose once, updates the scene, lists its marks and renders. The marks used to be
 * the Compose overlay, drawn with the same pose in the same vsync; measured on a Pixel 7 Pro, the
 * TextureView put the GPU frame on screen about 20 ms (2-3 vsyncs) later than the overlay, so held
 * in the hand the names jumped against the stars. In one GPU frame they cannot.
 *
 * The camera picture is drawn by that same Filament frame ([CameraBackground], fed by
 * [CameraFrameSource]), and the pose is taken at the instant of the image being drawn, so a star
 * cannot run ahead of the photographed sky during a turn. Where no picture can be seen
 * ([isCameraPictureVisible] false: outside planetarium mode, or under the opaque Milky Way) the camera is
 * not running at all and the live pose is used, so the sky moves every vsync.
 *
 * [orientation] is read in the frame loop, never in composition: it changes every 15 ms.
 *
 * The sky renders into a TextureView, not a SurfaceView. A SurfaceView's buffers are presented by
 * SurfaceFlinger on their own schedule, and with the labels in the window they slid visibly against
 * the stars during a pan on a Pixel 7 Pro. A TextureView is composited by the window, but not in the
 * vsync its frame was rendered in (see above), which is why nothing that sits on the sky is drawn by
 * Compose any more. It is translucent only where no camera image covers it: the picture is opaque.
 */
@Composable
internal fun NightSkyGpuSky(
    handle: NightSkyGpuHandle,
    renderer: NightSkyRenderer,
    orientation: () -> DeviceOrientation?,
    isCameraPictureVisible: Boolean,
    onGetRenderMatrix: (timestampNs: Long, out: FloatArray) -> Boolean,
    isLivePoseActive: () -> Boolean,
    rig: SkyPoseRig,
    displayRotation: Int,
    fovHorizontalDegrees: Float,
    zoomRatio: Float,
    catalog: NightSkyCatalog?,
    latitudeDegrees: Double,
    timeState: TimeState,
    longitudeDegrees: Double,
    nightVisionState: NightVisionState,
    isSkyBackgroundOpaque: Boolean,
    isMilkyWayVisible: Boolean,
    isAtmosphereVisible: Boolean,
    isLandscapeVisible: Boolean,
    isGridVisible: Boolean,
    horizonProfile: HorizonProfile?,
    useRealHorizon: Boolean,
    showPeakNames: Boolean,
    isPlanetsVisible: Boolean,
    isDsosVisible: Boolean,
    isMeteorShowersVisible: Boolean,
    isConstellationsVisible: Boolean,
    isConstellationArtVisible: Boolean,
    constellationArtOpacity: Float,
    isAsterismNamesVisible: Boolean,
    selectedBody: CelestialBodyDetails?,
    selectedPlanetId: String?,
    planets: List<PlanetState>,
    isDebugFocusRegionVisible: Boolean,
    debugFocusRadiusMultiplier: Float,
    debugFocusDisplayThreshold: Float,
    showTapRadiusIndicator: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val zenithLabel = stringResource(R.string.sun_ar_zenith)
    val nadirLabel = stringResource(R.string.sun_ar_nadir)
    val cardinalLabels = arrayOf(
        stringResource(R.string.sun_ar_cardinal_n),
        stringResource(R.string.sun_ar_cardinal_ne),
        stringResource(R.string.sun_ar_cardinal_e),
        stringResource(R.string.sun_ar_cardinal_se),
        stringResource(R.string.sun_ar_cardinal_s),
        stringResource(R.string.sun_ar_cardinal_sw),
        stringResource(R.string.sun_ar_cardinal_w),
        stringResource(R.string.sun_ar_cardinal_nw),
    )
    renderer.gridMarkers.updateLabels(zenithLabel, nadirLabel, cardinalLabels)
    renderer.applyTheme(nightVisionState)
    renderer.selectedPlanetId = selectedPlanetId

    val milkyWayBitmap by produceState<Bitmap?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { decodeUnscaled(context, R.drawable.milky_way_starless) }
    }
    val landscapeBitmap by produceState<Bitmap?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { decodeUnscaled(context, R.drawable.landscape_photorealistic) }
    }

    // Turning 100k catalogue entries into quads is the bulk of building the scene; done on the main
    // thread it froze the screen transition, so it happens here and the engine objects follow.
    val starGeometry by produceState<StarGeometry?>(initialValue = null, catalog) {
        val c = catalog
        value = if (c == null) null else withContext(Dispatchers.Default) { StarGeometry(c) }
    }
    // The sky stays hidden until its first frame is drawn and then fades in, rather than popping in
    // half-built (the first frame uploads the whole catalogue and compiles the shaders).
    var revealed by remember { mutableStateOf(false) }
    val skyAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "skyReveal",
    )
    var surface by remember { mutableStateOf<SkySurface?>(null) }
    var scene by remember { mutableStateOf<NightSkyScene?>(null) }
    val frame = handle.frame

    // The scene needs the GL surface, the catalogue and its star geometry; it is rebuilt if any changes.
    DisposableEffect(surface, catalog, starGeometry) {
        val s = surface
        val g = starGeometry
        val built = if (s != null && catalog != null && g != null) NightSkyScene(context, s, catalog, g) else null
        scene = built
        handle.scene = built
        onDispose {
            handle.scene = null
            built?.destroy()
        }
    }
    var background by remember { mutableStateOf<CameraBackground?>(null) }
    DisposableEffect(surface) {
        val built = surface?.let { CameraBackground(it) }
        background = built
        onDispose { built?.destroy() }
    }
    LaunchedEffect(scene, milkyWayBitmap) { milkyWayBitmap?.let { scene?.setMilkyWayBitmap(it) } }
    LaunchedEffect(scene, landscapeBitmap) { landscapeBitmap?.let { scene?.setLandscapeBitmap(it) } }
    LaunchedEffect(scene, horizonProfile) {
        // The renderer keeps the profile for the overlay's and the hit test's ground occlusion.
        renderer.setHorizonProfile(horizonProfile)
        scene?.setHorizonProfile(horizonProfile)
    }

    // Everything that changes only on recomposition goes into the frame here, not per vsync.
    SideEffect {
        frame.fovHorizontalDegrees = fovHorizontalDegrees
        frame.zoomRatio = zoomRatio
        frame.density = density
        frame.latitudeDegrees = latitudeDegrees
        frame.displayRotationDegrees = displayRotation * 90
        frame.showConstellations = isConstellationsVisible
        frame.showConstellationArt = isConstellationArtVisible
        frame.constellationArtOpacity = constellationArtOpacity
        frame.showAsterismNames = isAsterismNamesVisible
        frame.showMilkyWay = isMilkyWayVisible
        frame.showAtmosphere = isAtmosphereVisible
        frame.showLandscape = isLandscapeVisible
        frame.useRealHorizon = useRealHorizon
        frame.showPeakNames = showPeakNames
        frame.languageTag = languageTag
        frame.showGrid = isGridVisible
        frame.showPlanets = isPlanetsVisible
        frame.showDsos = isDsosVisible
        frame.opaqueBackground = isSkyBackgroundOpaque
        frame.planets = planets
        frame.selectedPlanetId = selectedPlanetId
        frame.nightVisionActive = nightVisionState.isActive
        frame.nightVisionTheme = nightVisionState.theme
        frame.focusRadiusMultiplier = debugFocusRadiusMultiplier
        // The calibration is already inside the render matrix; the ground is true-north ENU and
        // must not be turned by it a second time (the Canvas landscape was drawn with 0 too).
        frame.alignAzimuthOffsetDeg = 0f
        frame.alignElevationOffsetDeg = 0f
    }

    val currentTimeState by rememberUpdatedState(timeState)
    val currentLongitude by rememberUpdatedState(longitudeDegrees)
    val currentOrientation by rememberUpdatedState(orientation)
    val currentGetMatrix by rememberUpdatedState(onGetRenderMatrix)
    val currentPictureVisible by rememberUpdatedState(isCameraPictureVisible)
    val currentRig by rememberUpdatedState(rig)
    val currentRotation by rememberUpdatedState(displayRotation)
    val currentZoom by rememberUpdatedState(zoomRatio)
    val currentIsLive by rememberUpdatedState(isLivePoseActive)
    val currentCatalog by rememberUpdatedState(catalog)
    val currentSelectedBody by rememberUpdatedState(selectedBody)
    val currentShowConstellations by rememberUpdatedState(isConstellationsVisible)
    val currentShowMeteorShowers by rememberUpdatedState(isMeteorShowersVisible)
    val currentDisplayThreshold by rememberUpdatedState(debugFocusDisplayThreshold)
    val poseFrame = remember { SkyPoseFrame() }
    var frameTick by remember { mutableLongStateOf(0L) }
    var frameLst by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { nanos ->
                val sc = scene
                val sf = surface
                frame.dtSeconds = if (lastFrameNanos == 0L) 0.016f else ((nanos - lastFrameNanos) / 1e9f)
                lastFrameNanos = nanos
                // Over the camera, the newest image goes to the GPU and the pose is the one it was
                // taken with; where no picture is shown (outside planetarium mode, under the opaque
                // Milky Way) there is nothing to match and the live pose moves the sky every vsync.
                //
                // The rig also decides who owns the camera (CameraX or ARCore) and whose pose draws.
                // ARCore's pose is used only while the pose is the phone's (not a manual pan) and at
                // 1x; a pinch hands the camera back to CameraX, which is the one that can zoom.
                val rigInstance = currentRig
                val bg = background
                bg?.enabled = currentPictureVisible
                rigInstance.advance(
                    background = bg,
                    viewportWidth = frame.viewportWidth,
                    viewportHeight = frame.viewportHeight,
                    displayRotation = currentRotation,
                    zoomRatio = currentZoom,
                    pictureEnabled = currentPictureVisible,
                    livePoseAllowed = currentIsLive(),
                    out = poseFrame,
                )
                val poseReady = if (poseFrame.useRigMatrix) {
                    poseFrame.matrix.copyInto(frame.deviceToEnu, 0, 0, 16)
                    true
                } else {
                    sampleMatrix(frame.deviceToEnu, poseFrame.poseTimestampNs, currentGetMatrix, currentOrientation())
                }
                if (sc == null && sf != null && bg != null && rigInstance.hasPicture && currentPictureVisible) {
                    // The catalogue is still being turned into geometry. The camera used to be on
                    // screen from the first frame (it was a separate view), so it is shown meanwhile
                    // rather than a black screen; the sky then appears in its first frame.
                    sf.renderFrame(nanos)
                    revealed = true
                } else if (sc != null && sf != null && poseReady) {
                    val ts = currentTimeState
                    val millis = if (ts.isVirtual && ts.virtualTime != null) ts.virtualTime.toEpochMilli() else System.currentTimeMillis()
                    frame.lstDegrees = SiderealTime.calculateLST(SiderealTime.toJulianDate(millis), currentLongitude)
                    frame.selectedConstellation = renderer.selectedConstellationIndex
                    frame.selectedStar = renderer.selectedStarIndex
                    frame.selectedDso = renderer.selectedDsoIndex
                    sc.update(frame)
                    // The marks are part of this GPU frame, listed after update() moved the focus.
                    currentCatalog?.let {
                        renderer.updateOpticalPaints(density = frame.density)
                        renderer.updateGpuMarks(
                            scene = sc,
                            frame = frame,
                            catalog = it,
                            showConstellations = currentShowConstellations,
                            showMeteorShowers = currentShowMeteorShowers,
                            selectedBody = currentSelectedBody,
                            debugFocusDisplayThreshold = currentDisplayThreshold,
                        )
                    }
                    sf.renderFrame(nanos)
                    revealed = true
                }
                frameLst = frame.lstDegrees
                frameTick = nanos
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { surface = SkySurface(ctx, it, ownFrameLoop = false) } },
            onRelease = {
                // Whichever of this and the scene's DisposableEffect runs first, the scene is
                // released before the engine it lives in (NightSkyScene.destroy is idempotent).
                handle.scene?.destroy()
                handle.scene = null
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
                    frame.viewportWidth = it.width.toFloat()
                    frame.viewportHeight = it.height.toFloat()
                },
        )
        var lastCountMillis by remember { mutableLongStateOf(0L) }
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = skyAlpha }) {
            @Suppress("UNUSED_VARIABLE")
            val tick = frameTick
            val sc = scene ?: return@Canvas
            val cat = catalog ?: return@Canvas
            renderer.drawScreenOverlay(
                drawScope = this,
                scene = sc,
                frame = frame,
                displayRotation = displayRotation,
                lstDegrees = frameLst,
                latitudeDegrees = latitudeDegrees,
                selectedBody = selectedBody,
                isDebugFocusRegionVisible = isDebugFocusRegionVisible,
                debugFocusRadiusMultiplier = debugFocusRadiusMultiplier,
                debugFocusDisplayThreshold = debugFocusDisplayThreshold,
                showTapRadiusIndicator = showTapRadiusIndicator,
            )
            // The HUD's star count is read once a second; counting 100k stars every frame would
            // put back on the CPU what the GPU path took off it.
            val now = SystemClock.elapsedRealtime()
            if (now - lastCountMillis >= 1000L) {
                lastCountMillis = now
                renderer.countVisibleStars(sc, frame, cat)
            }
        }
    }
}

/** The view model's matrix when it has one (manual mode, routes), else the live sensor one. */
private fun sampleMatrix(
    out: FloatArray,
    timestampNs: Long,
    getMatrix: (Long, FloatArray) -> Boolean,
    orientation: DeviceOrientation?,
): Boolean {
    if (getMatrix(timestampNs, out)) return true
    val m = orientation?.rotationMatrix ?: return false
    m.copyInto(out, 0, 0, minOf(16, m.size))
    return true
}

private fun decodeUnscaled(context: android.content.Context, id: Int): Bitmap? = try {
    BitmapFactory.decodeResource(
        context.resources,
        id,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        },
    )
} catch (_: Exception) {
    null
}

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.dazix.caelis.R
import com.dazix.caelis.ar.DeviceOrientation
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
 * The night sky drawn by Filament, with the Compose overlay for text and vector glyphs on top.
 *
 * Takes the same inputs as the Canvas `NightSkyOverlayCanvas` it replaces. One frame loop drives
 * both halves: in each vsync it samples the pose once, updates and renders the GPU scene, and then
 * invalidates the overlay, which draws through the scene's projector — so a label can never be a
 * frame behind its star.
 *
 * The sky renders into a translucent TextureView, not a SurfaceView. A SurfaceView's buffers are
 * presented by SurfaceFlinger on their own schedule, and with the labels in the window they slid
 * visibly against the stars during a pan on a Pixel 7 Pro; a TextureView is composited by the
 * window's own frame, the same frame the overlay is in. It sits over the CameraX SurfaceView, which
 * stays below the window.
 */
@Composable
internal fun NightSkyGpuSky(
    handle: NightSkyGpuHandle,
    renderer: NightSkyRenderer,
    orientation: DeviceOrientation?,
    onGetRenderMatrix: (FloatArray) -> Boolean,
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
    isPlanetsVisible: Boolean,
    isDsosVisible: Boolean,
    isMeteorShowersVisible: Boolean,
    isConstellationsVisible: Boolean,
    isConstellationArtVisible: Boolean,
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
        frame.showMilkyWay = isMilkyWayVisible
        frame.showAtmosphere = isAtmosphereVisible
        frame.showLandscape = isLandscapeVisible
        frame.useRealHorizon = useRealHorizon
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
                if (sc != null && sf != null && sampleMatrix(frame.deviceToEnu, currentGetMatrix, currentOrientation)) {
                    val ts = currentTimeState
                    val millis = if (ts.isVirtual && ts.virtualTime != null) ts.virtualTime.toEpochMilli() else System.currentTimeMillis()
                    frame.lstDegrees = SiderealTime.calculateLST(SiderealTime.toJulianDate(millis), currentLongitude)
                    frame.selectedConstellation = renderer.selectedConstellationIndex
                    frame.selectedStar = renderer.selectedStarIndex
                    frame.selectedDso = renderer.selectedDsoIndex
                    sc.update(frame)
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
            renderer.updateOpticalPaints(density = density)
            renderer.drawGpuOverlay(
                drawScope = this,
                scene = sc,
                frame = frame,
                catalog = cat,
                displayRotation = displayRotation,
                showConstellations = isConstellationsVisible,
                showMeteorShowers = isMeteorShowersVisible,
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
    getMatrix: (FloatArray) -> Boolean,
    orientation: DeviceOrientation?,
): Boolean {
    if (getMatrix(out)) return true
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

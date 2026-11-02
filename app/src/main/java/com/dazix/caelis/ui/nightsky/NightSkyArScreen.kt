package com.dazix.caelis.ui.nightsky

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.dazix.caelis.ui.AppToastHost
import com.dazix.caelis.ui.AppToastHostState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.caelis.R
import com.dazix.caelis.ar.CameraFrameSource
import com.dazix.caelis.ar.CameraPreview
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.PreviewFrameClock
import com.dazix.caelis.ar.stillBackCameraFov
import com.dazix.caelis.domain.nightsky.NightSkyCameraPolicy
import com.dazix.caelis.ar.scene.RedPrimary
import com.dazix.caelis.ar.scene.RedSecondary
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.NightVisionState
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.SiderealTime
import com.dazix.caelis.domain.nightsky.StarCatalog
import com.dazix.caelis.domain.nightsky.TimeState
import com.dazix.caelis.service.DisplayGeometry
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.ImmersiveModeEffect
import com.dazix.caelis.ui.Permissions
import com.dazix.caelis.ui.rememberBottomGestureSlopPx
import com.dazix.caelis.ui.rememberSkyPoseRig
import com.dazix.caelis.domain.arframe.SystemGestureZone
import java.time.ZoneId
import com.dazix.caelis.ui.solar.HorizonConsentDialog

import android.graphics.BitmapFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightSkyArScreen(
    onBack: () -> Unit,
    onOpenTerrainSettings: () -> Unit = {},
    viewModel: NightSkyArViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val alignAzimuthOffset by viewModel.panAzimuthOffset.collectAsStateWithLifecycle()
    val alignElevationOffset by viewModel.panPitchOffset.collectAsStateWithLifecycle()
    // Read where it is used, never here: a new orientation arrives every 15 ms, and reading it in this
    // scope recomposed the whole sky and HUD at that rate. The frame loop reads it per vsync and the
    // HUD only when its whole degrees change (hudOrientation below).
    val orientationState = viewModel.orientation.collectAsStateWithLifecycle()
    val hudOrientation by remember {
        derivedStateOf(WholeDegreesPolicy) { orientationState.value }
    }
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val horizonProfile by viewModel.horizonProfile.collectAsStateWithLifecycle()
    val useRealHorizon by viewModel.useRealHorizon.collectAsStateWithLifecycle()
    val showStylisedLandscape by viewModel.showStylisedLandscape.collectAsStateWithLifecycle()
    val horizonSettings by viewModel.horizonSettings.collectAsStateWithLifecycle()

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        confirmValueChange = { true },
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    var isLayerSheetOpen by remember { mutableStateOf(false) }
    var showDsoLegendInAr by remember { mutableStateOf(false) }
    val previewFrameClock = remember { PreviewFrameClock() }
    // The camera picture is drawn by the sky's own Filament frame; see CameraFrameSource.
    val cameraFrameSource = remember(previewFrameClock) { CameraFrameSource(previewFrameClock) }
    DisposableEffect(cameraFrameSource) {
        onDispose { cameraFrameSource.release() }
    }

    val density = LocalDensity.current
    val renderer = viewModel.renderer
    val maxTouchRadiusPx = with(density) { uiState.debugPreferences.starTapRadiusDp.dp.toPx() }

    // Track the active selection identity to prevent partialExpand loops on periodic data updates
    val selectedBody = uiState.selectedBody
    val selectedTargetKey = when {
        uiState.selectedPlanetId != null -> "planet_${uiState.selectedPlanetId}"
        uiState.selectedStarIndex != -1 -> "star_${uiState.selectedStarIndex}"
        uiState.selectedConstellationIndex != -1 -> "const_${uiState.selectedConstellationIndex}"
        uiState.selectedDsoIndex != -1 -> "dso_${uiState.selectedDsoIndex}"
        uiState.selectedMeteorIndex != -1 -> "meteor_${uiState.selectedMeteorIndex}"
        selectedBody != null -> "body_${selectedBody.name}"
        else -> null
    }

    // Synchronize ViewModel State with UI State
    LaunchedEffect(
        selectedTargetKey,
        uiState.selectedPlanetId,
        uiState.selectedStarIndex,
        uiState.selectedConstellationIndex,
        uiState.selectedDsoIndex,
        uiState.selectedMeteorIndex,
    ) {
        renderer.selectedStarIndex = uiState.selectedStarIndex
        renderer.selectedConstellationIndex = uiState.selectedConstellationIndex
        renderer.selectedDsoIndex = uiState.selectedDsoIndex
        renderer.selectedMeteorIndex = uiState.selectedMeteorIndex
        renderer.selectedPlanetId = uiState.selectedPlanetId
        if (selectedTargetKey != null) {
            // Only partially expand if the sheet is currently hidden or switching to a new target
            if (sheetState.targetValue == SheetValue.Hidden || sheetState.currentValue == SheetValue.Hidden) {
                sheetState.partialExpand()
            }
        } else {
            sheetState.hide()
        }
    }

    // Sync swipe-down dismiss back to ViewModel safely without anchor layout race conditions
    LaunchedEffect(sheetState) {
        var wasVisible = false
        snapshotFlow { sheetState.currentValue }
            .collect { value ->
                if (value != SheetValue.Hidden) {
                    wasVisible = true
                } else if (wasVisible) {
                    viewModel.clearSelectedBody()
                    wasVisible = false
                }
            }
    }

    var hasCameraPermission by remember {
        mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA))
    }

    // Who owns the camera (CameraX, or ARCore if the user agreed) and whose pose draws the sky. A
    // zoom other than 1x always belongs to CameraX, the only camera that can zoom.
    val poseRig = rememberSkyPoseRig(
        sensorPose = viewModel.sensorPose,
        cameraSource = cameraFrameSource,
        cameraPermission = hasCameraPermission,
        onFov = viewModel::setCameraFov,
    )
    val cameraXEnabled by poseRig.cameraXEnabled.collectAsStateWithLifecycle()
    val poseIndicator by poseRig.indicator.collectAsStateWithLifecycle()
    val arCoreConsented by poseRig.consentedFlow.collectAsStateWithLifecycle()

    val showConsentDialog by viewModel.showConsentDialog.collectAsStateWithLifecycle()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshLocation()
    }
    // Once per screen: a declined location is not asked for again on every layer toggle.
    var locationAsked by remember { mutableStateOf(false) }
    val askLocationOnce = {
        if (!locationAsked) {
            locationAsked = true
            val hasLocation = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!hasLocation) {
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        askLocationOnce()
    }

    BackHandler {
        when {
            showDsoLegendInAr -> showDsoLegendInAr = false
            uiState.isSearchActive -> viewModel.setSearchActive(false)
            uiState.selectedBody != null -> viewModel.clearSelectedBody()
            uiState.multipleHitsTarget != null -> viewModel.dismissDisambiguation()
            isLayerSheetOpen -> isLayerSheetOpen = false
            uiState.isDebugOptionsEnabled && uiState.isDebugFocusRegionVisible -> viewModel.setDebugFocusRegionVisible(false)
            else -> onBack()
        }
    }

    ImmersiveModeEffect()

    val view = LocalView.current
    DisposableEffect(view, uiState.keepScreenOn) {
        view.keepScreenOn = uiState.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.startSensors()
        onDispose {
            viewModel.stopSensors()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val toastHostState = remember(coroutineScope) { AppToastHostState(coroutineScope) }

    val displayRotation = DisplayGeometry.currentRotation(context)
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 ||
        displayRotation == android.view.Surface.ROTATION_180

    val cameraPictureVisible = NightSkyCameraPolicy.isPictureVisible(
        planetariumMode = uiState.isSkyBackgroundOpaque,
        milkyWayVisible = uiState.isMilkyWayVisible,
    )
    // The camera permission is asked for when its picture is wanted, not on entry: outside planetarium
    // mode the camera never runs. The camera's answer then asks for the location, so the two dialogs
    // never race.
    LaunchedEffect(cameraPictureVisible) {
        if (cameraPictureVisible && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            askLocationOnce()
        }
    }
    // With the camera off the sky is still drawn at the scale the camera would show it.
    LaunchedEffect(cameraPictureVisible, isPortrait) {
        if (cameraPictureVisible) return@LaunchedEffect
        val bounds = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val fov = withContext(Dispatchers.Default) {
            stillBackCameraFov(context, bounds.width().toFloat(), bounds.height().toFloat(), isPortrait)
        }
        fov?.let(viewModel::setPredictedCameraFov)
    }

    val activePalette = paletteForTheme(uiState.nightVisionState.theme)
    val bgColor = if (uiState.redVisionMode) Color(0xFF000000) else Color(0xFF0A0E14)
    val sheetContainerColor = if (uiState.redVisionMode) Color(0xFF000000) else Color(0xF0121622)
    val sheetBorderColor = if (uiState.redVisionMode) activePalette.primary.copy(alpha = 0.3f) else Color(0xFF283248)

    var headerHeightPx by remember { mutableStateOf(0) }
    val headerHeightDp = with(density) { headerHeightPx.toDp() }

    var fullHeightPx by remember { mutableStateOf(0) }
    val fullHeightDp = with(density) { fullHeightPx.toDp() }

    val sheetPeekHeight = if (uiState.selectedBody != null) {
        if (headerHeightDp > 0.dp) headerHeightDp else 100.dp
    } else {
        0.dp
    }

    val isSheetExpanded = sheetState.targetValue == SheetValue.Expanded || sheetState.currentValue == SheetValue.Expanded
    val activeSheetHeightDp = if (isSheetExpanded) {
        if (fullHeightDp > 0.dp) fullHeightDp else 240.dp
    } else {
        if (headerHeightDp > 0.dp) headerHeightDp else 100.dp
    }

    val targetBottomPadding = if (uiState.selectedBody != null && activeSheetHeightDp > 0.dp) {
        activeSheetHeightDp + 14.dp
    } else {
        24.dp
    }
    val animatedBottomPadding by animateDpAsState(
        targetValue = targetBottomPadding,
        label = "bottomControlsOffset",
    )

    val currentCatalog by rememberUpdatedState(catalog)
    val currentPlanets by rememberUpdatedState(if (uiState.isPlanetsVisible || uiState.selectedPlanetId != null) uiState.planetStates else emptyList())
    val currentMaxTouchRadiusPx by rememberUpdatedState(maxTouchRadiusPx)
    val currentDensity by rememberUpdatedState(density.density)
    val currentCameraFovH by rememberUpdatedState(uiState.cameraFovHorizontal)
    val currentDebugPreferences by rememberUpdatedState(uiState.debugPreferences)
    val currentLandscapeVisible by rememberUpdatedState(uiState.isLandscapeVisible)
    val currentUseRealHorizon by rememberUpdatedState(horizonSettings.realHorizonInNightSky && horizonSettings.enabled)
    val currentHorizonProfile by rememberUpdatedState(horizonProfile)
    val gpuSky = remember { NightSkyGpuHandle() }
    val currentConstellationsVisible by rememberUpdatedState(uiState.isConstellationsVisible)
    val currentMeteorShowersVisible by rememberUpdatedState(uiState.isMeteorShowersVisible)

    val bottomGestureSlop = rememberUpdatedState(rememberBottomGestureSlopPx())
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetDragHandle = null,
        sheetContainerColor = sheetContainerColor,
        sheetContentColor = if (uiState.redVisionMode) activePalette.primary else Color.White,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContent = {
            CelestialDetailSheetContent(
                selectedBody = uiState.selectedBody,
                visibilityState = uiState.visibilityState,
                localScheduleDate = uiState.localScheduleDate,
                activeGlobalDate = uiState.timeState.activeInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                onSetLocalDate = viewModel::setLocalScheduleDate,
                onStepLocalDate = viewModel::stepLocalScheduleDate,
                redVisionMode = uiState.redVisionMode,
                onHeaderMeasured = { height ->
                    if (height > 0 && kotlin.math.abs(height - headerHeightPx) > 2) {
                        headerHeightPx = height
                    }
                },
                onFullHeightMeasured = { height ->
                    if (height > 0 && kotlin.math.abs(height - fullHeightPx) > 2) {
                        fullHeightPx = height
                    }
                },
                onRouteToTarget = { viewModel.routeCameraToSelection(displayRotation) },
                onShowDsoLegend = if (uiState.selectedDsoIndex != -1) {
                    { showDsoLegendInAr = true }
                } else null,
                onClose = viewModel::clearSelectedBody,
                nightVisionTheme = uiState.nightVisionState.theme,
            )
        },
        containerColor = Color.Transparent,
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectArSkyGestures(
                        bottomGestureSlopPx = { bottomGestureSlop.value },
                        onTap = { offset ->
                            if (currentDebugPreferences.showTapRadiusIndicator) {
                                renderer.triggerTapIndicator(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    radiusPx = currentMaxTouchRadiusPx,
                                    radiusDp = currentDebugPreferences.starTapRadiusDp,
                                )
                            }
                            // The GPU path keeps no per-frame screen positions on the CPU; project
                            // the last drawn frame now, so the hit test sees what is on screen.
                            val gpuScene = gpuSky.scene
                            val tapCatalog = currentCatalog
                            if (gpuScene != null && tapCatalog != null) {
                                renderer.prepareHitTest(
                                    scene = gpuScene,
                                    frame = gpuSky.frame,
                                    catalog = tapCatalog,
                                    showConstellations = currentConstellationsVisible,
                                    showMeteorShowers = currentMeteorShowersVisible,
                                    density = currentDensity,
                                )
                            }
                            val hitTarget = renderer.findHitAt(
                                tapX = offset.x,
                                tapY = offset.y,
                                maxStarRadiusPx = currentMaxTouchRadiusPx,
                                density = currentDensity,
                                catalog = currentCatalog,
                                planets = currentPlanets,
                                // A wanted skyline still waiting for its profile is a flat ring: ground below 0°.
                                isLandscapeVisible = currentLandscapeVisible || currentUseRealHorizon,
                                useRealHorizon = currentUseRealHorizon,
                                horizonProfile = currentHorizonProfile,
                            )
                            when (hitTarget) {
                                is NightSkyRenderer.HitTarget.ConstellationHit -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedConstellationIndex = hitTarget.index
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedDsoIndex = -1
                                    renderer.selectedMeteorIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectConstellationByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.StarHit -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedStarIndex = hitTarget.index
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedDsoIndex = -1
                                    renderer.selectedMeteorIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectStarByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.DsoHit -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedDsoIndex = hitTarget.index
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedMeteorIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectDsoByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.MeteorRadiantHit -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedMeteorIndex = hitTarget.index
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedDsoIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectMeteorByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.PlanetHit -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedDsoIndex = -1
                                    renderer.selectedMeteorIndex = -1
                                    renderer.selectedPlanetId = null
                                    renderer.selectedConstellationIndex = -1
                                    viewModel.selectPlanetById(hitTarget.planetId)
                                }
                                is NightSkyRenderer.HitTarget.MultipleHits -> {
                                    viewModel.showDisambiguation(hitTarget)
                                }
                                is NightSkyRenderer.HitTarget.Miss -> {
                                    viewModel.dismissDisambiguation()
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedDsoIndex = -1
                                    renderer.selectedMeteorIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.clearSelectedBody()
                                }
                            }
                        },
                        onDoubleTap = {
                            viewModel.resetZoom()
                        },
                        onPan = { deltaX, deltaY ->
                            val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                            viewModel.onPanGesture(
                                deltaX = deltaX,
                                deltaY = deltaY,
                                viewWidth = viewWidth,
                                fovDeg = currentCameraFovH,
                            )
                        },
                        onZoom = { zoom ->
                            if (zoom != 1.0f) {
                                viewModel.adjustZoomBy(zoom)
                            }
                        },
                    )
                }
        ) {
            if (hasCameraPermission) {
                // Camera stream and FOV; the picture itself is drawn by NightSkyGpuSky below. Only in
                // planetarium mode with the Milky Way off (NightSkyCameraPolicy): anywhere else the
                // camera would be running unseen — and, worse, the sky would be posed at its images'
                // instants, which moved the stars 15-30 times a second instead of every vsync.
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    lifecycleOwner = lifecycleOwner,
                    isPortrait = isPortrait,
                    // The rig forwards CameraX's field of view only while CameraX is the camera.
                    onFovCalculated = poseRig::onCameraXFov,
                    onCameraBound = viewModel::attachCamera,
                    frameClock = previewFrameClock,
                    frameSource = cameraFrameSource,
                    displayRotation = displayRotation,
                    cameraEnabled = cameraXEnabled && cameraPictureVisible,
                    onCameraReleased = poseRig::onCameraXReleased,
                )
            }

            // The sky needs no camera: without the permission it is drawn all the same, and a chip
            // under the top bar offers the permission while the picture is wanted.
            NightSkyGpuSky(
                handle = gpuSky,
                renderer = renderer,
                orientation = { orientationState.value },
                isCameraPictureVisible = cameraPictureVisible,
                onGetRenderMatrix = viewModel::getCurrentRenderMatrix,
                isLivePoseActive = { viewModel.isLivePoseActive },
                rig = poseRig,
                displayRotation = displayRotation,
                fovHorizontalDegrees = uiState.cameraFovHorizontal,
                zoomRatio = uiState.zoomRatio,
                catalog = catalog,
                latitudeDegrees = uiState.location.latitude,
                timeState = uiState.timeState,
                longitudeDegrees = uiState.location.longitude,
                nightVisionState = uiState.nightVisionState,
                isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                isMilkyWayVisible = uiState.isMilkyWayVisible,
                isAtmosphereVisible = uiState.isAtmosphereVisible,
                isLandscapeVisible = showStylisedLandscape,
                isGridVisible = uiState.isGridVisible,
                horizonProfile = horizonProfile,
                useRealHorizon = useRealHorizon,
                showPeakNames = horizonSettings.peakNamesInNightSky,
                isPlanetsVisible = uiState.isPlanetsVisible,
                isDsosVisible = uiState.isDsosVisible,
                isMeteorShowersVisible = uiState.isMeteorShowersVisible,
                isConstellationsVisible = uiState.isConstellationsVisible,
                isConstellationArtVisible = uiState.isConstellationArtVisible,
                selectedBody = uiState.selectedBody,
                selectedPlanetId = uiState.selectedPlanetId,
                planets = uiState.planetStates,
                isDebugFocusRegionVisible = uiState.debugPreferences.enableNightSkyFocusRegion && uiState.isDebugFocusRegionVisible,
                debugFocusRadiusMultiplier = uiState.debugFocusRadiusMultiplier,
                debugFocusDisplayThreshold = uiState.debugFocusDisplayThreshold,
                showTapRadiusIndicator = uiState.debugPreferences.showTapRadiusIndicator,
                modifier = Modifier.fillMaxSize(),
            )

            // In-App Focus Area Debugger Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = (uiState.debugPreferences.enableNightSkyFocusRegion || uiState.isDebugOptionsEnabled) && uiState.isDebugFocusRegionVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, top = 80.dp, bottom = 80.dp),
            ) {
                FocusRegionDebugOverlay(
                    radiusMultiplier = uiState.debugFocusRadiusMultiplier,
                    displayThreshold = uiState.debugFocusDisplayThreshold,
                    tapRadiusDp = uiState.debugPreferences.starTapRadiusDp,
                    showTapRadiusIndicator = uiState.debugPreferences.showTapRadiusIndicator,
                    redVisionMode = uiState.redVisionMode,
                    onRadiusMultiplierChange = viewModel::setDebugFocusRadiusMultiplier,
                    onDisplayThresholdChange = viewModel::setDebugFocusDisplayThreshold,
                    onTapRadiusDpChange = viewModel::setDebugStarTapRadius,
                    onShowTapRadiusIndicatorChange = viewModel::setShowTapRadiusIndicator,
                    onReset = viewModel::resetDebugFocusRegion,
                    onClose = { viewModel.setDebugFocusRegionVisible(false) },
                    nightVisionTheme = uiState.nightVisionState.theme,
                )
            }

            // Top Controls: Global Celestial Search Bar or Top Bar HUD
            if (uiState.isSearchActive) {
                CelestialSearchBar(
                    query = uiState.searchQuery,
                    results = uiState.searchResults,
                    history = uiState.searchHistory,
                    active = uiState.isSearchActive,
                    onQueryChange = viewModel::setSearchQuery,
                    onActiveChange = viewModel::setSearchActive,
                    onSelectItem = { item -> viewModel.selectCelestialBody(item, displayRotation) },
                    onClearHistory = viewModel::clearSearchHistory,
                    onRemoveHistoryItem = viewModel::removeSearchHistoryItem,
                    redVisionMode = uiState.redVisionMode,
                    nightVisionTheme = uiState.nightVisionState.theme,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp),
                )
            } else {
                NightSkyTopBar(
                    hasGpsFix = uiState.hasGpsFix,
                    locationText = uiState.location.formatShort(),
                    orientation = hudOrientation,
                    cameraFovHorizontal = uiState.cameraFovHorizontal,
                    isDynamicStreamCalculated = uiState.isDynamicStreamCalculated,
                    zoomRatio = uiState.zoomRatio,
                    isCalibrated = uiState.isCalibrated,
                    isVirtualTime = uiState.timeState.isVirtual,
                    horizonState = uiState.horizonState,
                    redVisionMode = uiState.redVisionMode,
                    onBack = onBack,
                    onOpenSearch = {
                        viewModel.setSearchActive(true)
                    },
                    onOpenLayers = { isLayerSheetOpen = true },
                    onResetZoom = {
                        viewModel.resetZoom()
                    },
                    nightVisionTheme = uiState.nightVisionState.theme,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp, start = 12.dp, end = 12.dp),
                )
                val headingState by viewModel.headingState.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 100.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val chipColor = if (uiState.redVisionMode) activePalette.primary else null
                    com.dazix.caelis.ui.TerrainStatusChip(
                        state = uiState.horizonState,
                        layerOn = horizonSettings.enabled && horizonSettings.realHorizonInNightSky,
                        onOpenSettings = onOpenTerrainSettings,
                        nightVisionColor = chipColor,
                    )
                    if (cameraPictureVisible && !hasCameraPermission) {
                        CameraPermissionChip(
                            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            nightVisionColor = chipColor,
                        )
                    }
                    com.dazix.caelis.ui.HeadingStatusChip(state = headingState, nightVisionColor = chipColor)
                    com.dazix.caelis.ui.PoseSourceChip(
                        indicator = poseIndicator,
                        arCoreConsented = arCoreConsented,
                        nightVisionColor = chipColor,
                    )
                }
            }

            // Bottom Controls Layer: Absolute Centered HUD + Decoupled Contextual Reset View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = animatedBottomPadding, start = 16.dp, end = 16.dp),
            ) {
                // Main Bottom HUD (LST & Visible Stars) — Strictly centered on screen, never moves
                NightSkyBottomHud(
                    lstFormatted = uiState.lstFormatted,
                    visibleStarCount = uiState.visibleStarCount,
                    timeState = uiState.timeState,
                    redVisionMode = uiState.redVisionMode,
                    nightVisionTheme = uiState.nightVisionState.theme,
                    modifier = Modifier.align(Alignment.Center),
                )

                // Contextual Reset View / Calibration Button (Decoupled on the end side, 0 layout shifting)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isCalibrated,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.redVisionMode) Color(0xFF000000) else Color(0xDD1A1A1A),
                        border = BorderStroke(1.dp, if (uiState.redVisionMode) activePalette.primary.copy(alpha = 0.3f) else Color(0x33FFFFFF)),
                        modifier = Modifier.size(44.dp),
                    ) {
                        IconButton(
                            onClick = {
                                toastHostState.showToast(
                                    resId = R.string.calib_reset_toast,
                                    icon = AppIcons.Compass,
                                )
                                viewModel.resumeSensorTracking()
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                imageVector = AppIcons.Compass,
                                contentDescription = stringResource(R.string.sun_ar_calib_reset),
                                tint = if (uiState.redVisionMode) activePalette.primary else Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }

            // Theme-Aware Floating Toast
            val toastContainerColor = if (uiState.redVisionMode) Color(0xFF140505) else Color(0xEE1E1E1E)
            val toastContentColor = if (uiState.redVisionMode) activePalette.primary else Color.White
            val toastBorderColor = if (uiState.redVisionMode) activePalette.primary.copy(alpha = 0.4f) else Color(0x33FFFFFF)
            AppToastHost(
                hostState = toastHostState,
                containerColor = toastContainerColor,
                contentColor = toastContentColor,
                borderColor = toastBorderColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = animatedBottomPadding + 64.dp),
            )

            // Disambiguation Floating Menu for Multiple Hits
            uiState.multipleHitsTarget?.let { multipleHits ->
                CelestialDisambiguationMenu(
                    target = multipleHits,
                    redVisionMode = uiState.redVisionMode,
                    nightVisionTheme = uiState.nightVisionState.theme,
                    onSelectCandidate = { candidate ->
                        when (candidate) {
                            is NightSkyRenderer.CelestialCandidate.Planet -> {
                                renderer.selectedStarIndex = -1
                                renderer.selectedConstellationIndex = -1
                                renderer.selectedPlanetId = candidate.planetId
                                renderer.selectedDsoIndex = -1
                                renderer.selectedMeteorIndex = -1
                                viewModel.selectPlanetById(candidate.planetId)
                            }
                            is NightSkyRenderer.CelestialCandidate.Star -> {
                                renderer.selectedStarIndex = candidate.starIndex
                                renderer.selectedConstellationIndex = -1
                                renderer.selectedPlanetId = null
                                renderer.selectedDsoIndex = -1
                                renderer.selectedMeteorIndex = -1
                                viewModel.selectStarByIndex(candidate.starIndex)
                            }
                            is NightSkyRenderer.CelestialCandidate.Constellation -> {
                                renderer.selectedConstellationIndex = candidate.constellationIndex
                                renderer.selectedStarIndex = -1
                                renderer.selectedPlanetId = null
                                renderer.selectedDsoIndex = -1
                                renderer.selectedMeteorIndex = -1
                                viewModel.selectConstellationByIndex(candidate.constellationIndex)
                            }
                            is NightSkyRenderer.CelestialCandidate.Dso -> {
                                renderer.selectedDsoIndex = candidate.dsoIndex
                                renderer.selectedStarIndex = -1
                                renderer.selectedConstellationIndex = -1
                                renderer.selectedMeteorIndex = -1
                                renderer.selectedPlanetId = null
                                viewModel.selectDsoByIndex(candidate.dsoIndex)
                            }
                            is NightSkyRenderer.CelestialCandidate.MeteorShower -> {
                                renderer.selectedMeteorIndex = candidate.showerIndex
                                renderer.selectedStarIndex = -1
                                renderer.selectedConstellationIndex = -1
                                renderer.selectedDsoIndex = -1
                                renderer.selectedPlanetId = null
                                viewModel.selectMeteorByIndex(candidate.showerIndex)
                            }
                        }
                    },
                    onDismiss = viewModel::dismissDisambiguation,
                )
            }
        }
    }

    // Modal Bottom Sheet for Layer Controls
    if (isLayerSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isLayerSheetOpen = false },
            containerColor = if (uiState.redVisionMode) Color(0xFF000000) else Color(0xF0151820),
            contentColor = if (uiState.redVisionMode) activePalette.primary else Color.White,
            scrimColor = if (uiState.redVisionMode) Color.Black.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.5f),
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = if (uiState.redVisionMode) activePalette.primary.copy(alpha = 0.3f) else Color(0xFF718096),
                )
            },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            NightSkyLayerSettingsSheet(
                isLandscapeVisible = uiState.isLandscapeVisible,
                isRealHorizonEnabled = horizonSettings.realHorizonInNightSky,
                isRealHorizonAvailable = horizonSettings.enabled,
                isPeakNamesVisible = horizonSettings.peakNamesInNightSky,
                isAtmosphereVisible = uiState.isAtmosphereVisible,
                isMilkyWayVisible = uiState.isMilkyWayVisible,
                isPlanetsVisible = uiState.isPlanetsVisible,
                isDsosVisible = uiState.isDsosVisible,
                isMeteorShowersVisible = uiState.isMeteorShowersVisible,
                isConstellationsVisible = uiState.isConstellationsVisible,
                isConstellationArtVisible = uiState.isConstellationArtVisible,
                isGridVisible = uiState.isGridVisible,
                isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                redVisionMode = uiState.redVisionMode,
                isSensorTrackingEnabled = uiState.isSensorTrackingEnabled,
                isKeepScreenOn = uiState.keepScreenOn,
                isDebugFocusRegionVisible = uiState.isDebugFocusRegionVisible,
                enableNightSkyFocusRegion = uiState.debugPreferences.enableNightSkyFocusRegion,
                isDebugOptionsEnabled = uiState.isDebugOptionsEnabled,
                timeState = uiState.timeState,
                onSetVirtualTime = viewModel::setVirtualTime,
                onStepVirtualTime = viewModel::stepVirtualTime,
                onResetToLive = viewModel::resetToLiveTime,
                onToggleLandscapeVisible = viewModel::toggleLandscapeVisible,
                onToggleRealHorizon = viewModel::toggleRealHorizonInNightSky,
                onTogglePeakNames = viewModel::togglePeakNames,
                onToggleAtmosphereVisible = viewModel::toggleAtmosphereVisible,
                onToggleMilkyWayVisible = viewModel::toggleMilkyWayVisible,
                onTogglePlanetsVisible = viewModel::togglePlanetsVisible,
                onToggleDsosVisible = viewModel::toggleDsosVisible,
                onToggleMeteorShowersVisible = viewModel::toggleMeteorShowersVisible,
                onToggleConstellationsVisible = viewModel::toggleConstellationsVisible,
                onToggleConstellationArtVisible = viewModel::toggleConstellationArtVisible,
                onToggleGridVisible = viewModel::toggleGridVisible,
                onToggleSkyBackgroundOpaque = viewModel::toggleSkyBackgroundOpaque,
                onToggleRedVisionMode = viewModel::toggleRedVisionMode,
                onToggleSensorTracking = viewModel::toggleSensorTracking,
                onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                onToggleDebugFocusRegion = viewModel::toggleDebugFocusRegion,
                alignAzimuthOffsetDeg = alignAzimuthOffset,
                alignElevationOffsetDeg = alignElevationOffset,
                onNudgeAlignment = viewModel::nudgeCalibration,
                onResetAlignment = viewModel::resumeSensorTracking,
                nightVisionTheme = uiState.nightVisionState.theme,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }

    // Terrain horizon: the one-off network question, only shown when enabling.
    if (showConsentDialog) {
        HorizonConsentDialog(
            initialWifiOnly = horizonSettings.wifiOnly,
            onAllow = { wifiOnly -> viewModel.answerNetworkConsent(granted = true, wifiOnly = wifiOnly) },
            onNever = { viewModel.answerNetworkConsent(granted = false) },
        )
    }

    if (showDsoLegendInAr) {
        DsoLegendDialog(
            onDismiss = { showDsoLegendInAr = false },
            redVisionMode = uiState.redVisionMode,
            nightVisionTheme = uiState.nightVisionState.theme,
        )
    }
}

/**
 * Two orientations are the same to the HUD when they show the same whole degrees of azimuth and
 * elevation, which is all [NightSkyTopBar] prints of them.
 */
internal object WholeDegreesPolicy : SnapshotMutationPolicy<DeviceOrientation?> {
    override fun equivalent(a: DeviceOrientation?, b: DeviceOrientation?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.cameraAzimuthDegrees.toInt() == b.cameraAzimuthDegrees.toInt() &&
            a.cameraElevationDegrees.toInt() == b.cameraElevationDegrees.toInt()
    }
}

@Composable
internal fun NightSkyTopBar(
    hasGpsFix: Boolean,
    locationText: String,
    orientation: DeviceOrientation?,
    cameraFovHorizontal: Float,
    isDynamicStreamCalculated: Boolean = true,
    zoomRatio: Float,
    isCalibrated: Boolean,
    redVisionMode: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenLayers: () -> Unit,
    onResetZoom: () -> Unit,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
    isVirtualTime: Boolean = false,
    horizonState: com.dazix.caelis.data.horizon.HorizonState = com.dazix.caelis.data.horizon.HorizonState.Idle,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val containerBg = if (redVisionMode) Color(0xFF000000) else Color(0xDD1A1A1A)
    val containerBorder = if (redVisionMode) palette.primary.copy(alpha = 0.3f) else Color(0x33FFFFFF)
    val accentColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
    val textColor = if (redVisionMode) palette.primary else Color.White
    val subTextColor = if (redVisionMode) palette.secondary else Color(0xCCFFFFFF)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(containerBg)
            .border(1.dp, containerBorder, CircleShape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = AppIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                tint = textColor,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Row 1: Status Dot + Location Text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (redVisionMode) {
                                palette.primary
                            } else {
                                if (hasGpsFix) Color(0xFF66BB6A) else Color(0xFFFFCA28)
                            },
                            CircleShape,
                        )
                )
                Text(
                    text = if (hasGpsFix) locationText else stringResource(R.string.sun_ar_location_default),
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (horizonState is com.dazix.caelis.data.horizon.HorizonState.Locating) {
                    Text(
                        text = "• 🛰 GPS…",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                // Download and compute progress is TerrainStatusChip's, under the bar.
            }

            // Row 2: Azimuth, Elevation, FOV Data
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val az = orientation?.cameraAzimuthDegrees?.toInt() ?: 0
                val el = orientation?.cameraElevationDegrees?.toInt() ?: 0
                val cardinalRes = when {
                    az < 23 || az >= 338 -> R.string.sun_ar_cardinal_n
                    az < 68 -> R.string.sun_ar_cardinal_ne
                    az < 113 -> R.string.sun_ar_cardinal_e
                    az < 158 -> R.string.sun_ar_cardinal_se
                    az < 203 -> R.string.sun_ar_cardinal_s
                    az < 248 -> R.string.sun_ar_cardinal_sw
                    az < 293 -> R.string.sun_ar_cardinal_w
                    else -> R.string.sun_ar_cardinal_nw
                }
                val cardinalName = stringResource(cardinalRes).substringBefore(" ")
                Text(
                    text = "🧭 $az° $cardinalName",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = subTextColor,
                )
                Text(
                    text = String.format(java.util.Locale.US, "%+d°", el),
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = subTextColor,
                )
                val isZoomed = kotlin.math.abs(zoomRatio - 1.0f) > 0.05f
                val fovLabel = if (isZoomed) {
                    String.format(java.util.Locale.US, "%.1fx · FOV %.0f°", zoomRatio, cameraFovHorizontal)
                } else {
                    stringResource(R.string.sun_ar_fov_label, cameraFovHorizontal)
                }
                val fovColor = when {
                    isZoomed -> accentColor
                    redVisionMode -> if (isDynamicStreamCalculated) palette.primary else Color(0xFFFF5252)
                    isDynamicStreamCalculated -> Color(0xFF81C784)
                    else -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = "📐 $fovLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = fovColor,
                    fontWeight = if (isZoomed) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = isZoomed, onClick = onResetZoom),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onOpenSearch,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = stringResource(R.string.search_celestial_placeholder),
                    tint = if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                )
            }

            Box {
                IconButton(
                    onClick = onOpenLayers,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.MoreVert,
                        contentDescription = stringResource(R.string.night_sky_layers_title),
                        tint = if (redVisionMode) palette.primary else Color.White,
                    )
                }
                if (isVirtualTime) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(7.dp)
                            .background(
                                if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                                CircleShape,
                            )
                    )
                }
            }
        }
    }
}

@Composable
internal fun NightSkyBottomHud(
    lstFormatted: String,
    visibleStarCount: Int,
    timeState: com.dazix.caelis.domain.nightsky.TimeState = com.dazix.caelis.domain.nightsky.TimeState(),
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val containerBg = if (redVisionMode) Color(0xFF000000) else Color(0xDD1A1A1A)
    val containerBorder = if (redVisionMode) palette.primary.copy(alpha = 0.3f) else Color(0x33FFFFFF)
    val contentColor = if (redVisionMode) palette.primary else Color(0xFFE0E0E0)

    Surface(
        shape = CircleShape,
        color = containerBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, containerBorder),
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Local Sidereal Time (LST)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LST: ",
                    color = contentColor.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                )
                Text(
                    text = lstFormatted,
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Visible Stars Count
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.night_sky_stars_label),
                    color = contentColor.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = visibleStarCount.toString(),
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Virtual Time Readout tag (only displayed when virtual time is active)
            if (timeState.isVirtual && timeState.virtualTime != null) {
                val zdt = remember(timeState.virtualTime) {
                    timeState.virtualTime.atZone(java.time.ZoneId.systemDefault())
                }
                val timeStr = remember(zdt) {
                    String.format(java.util.Locale.US, "%02d:%02d", zdt.hour, zdt.minute)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🕒 ",
                        fontSize = 10.sp,
                    )
                    Text(
                        text = timeStr,
                        color = if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * Offers the camera permission while the camera picture is wanted (planetarium mode, Milky Way off)
 * and was not granted. A chip, not a screen: the sky works without the camera, and a full-screen
 * prompt would also hide the layer switches that take the wish back.
 */
@Composable
private fun CameraPermissionChip(
    onRequestPermission: () -> Unit,
    nightVisionColor: Color?,
) {
    val text = stringResource(R.string.night_sky_camera_permission_chip)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(
                onClickLabel = stringResource(R.string.sun_ar_camera_grant),
                role = Role.Button,
                onClick = onRequestPermission,
            )
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, (nightVisionColor ?: Color.White).copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.Permissions,
            contentDescription = null,
            tint = nightVisionColor ?: MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}


@Composable
private fun FocusRegionDebugOverlay(
    radiusMultiplier: Float,
    displayThreshold: Float,
    tapRadiusDp: Int,
    showTapRadiusIndicator: Boolean,
    redVisionMode: Boolean,
    onRadiusMultiplierChange: (Float) -> Unit,
    onDisplayThresholdChange: (Float) -> Unit,
    onTapRadiusDpChange: (Int) -> Unit,
    onShowTapRadiusIndicatorChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val containerBg = if (redVisionMode) Color(0xFF000000) else Color(0xEE121824)
    val borderColor = if (redVisionMode) palette.primary.copy(alpha = 0.3f) else Color(0x444DD0E1)
    val textColor = if (redVisionMode) palette.primary else Color(0xFFE2E8F0)
    val labelColor = if (redVisionMode) palette.secondary else Color(0xFF94A3B8)
    val sliderColors = if (redVisionMode) {
        SliderDefaults.colors(
            thumbColor = palette.primary,
            activeTrackColor = palette.primary,
            inactiveTrackColor = Color(0xFF3B0000),
        )
    } else {
        SliderDefaults.colors(
            thumbColor = Color(0xFF4DD0E1),
            activeTrackColor = Color(0xFF00ACC1),
            inactiveTrackColor = Color(0xFF1E293B),
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.widthIn(max = 300.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.FocusArea,
                        contentDescription = null,
                        tint = if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.night_sky_debug_focus_region),
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.ResetCalibration,
                            contentDescription = stringResource(R.string.night_sky_debug_reset),
                            tint = labelColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Clear,
                            contentDescription = stringResource(R.string.celestial_close),
                            tint = labelColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Radius Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_focus_radius, radiusMultiplier * 100f),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = radiusMultiplier,
                    onValueChange = onRadiusMultiplierChange,
                    valueRange = 0.10f..1.20f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Display Threshold Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_focus_threshold, displayThreshold * 100f),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = displayThreshold,
                    onValueChange = onDisplayThresholdChange,
                    valueRange = 0.0f..1.0f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Tap Hit Radius Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_tap_radius, tapRadiusDp),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = tapRadiusDp.toFloat(),
                    onValueChange = { onTapRadiusDpChange(it.toInt()) },
                    valueRange = com.dazix.caelis.data.DebugPreferences.MIN_STAR_TAP_RADIUS_DP.toFloat()..com.dazix.caelis.data.DebugPreferences.MAX_STAR_TAP_RADIUS_DP.toFloat(),
                    steps = (com.dazix.caelis.data.DebugPreferences.MAX_STAR_TAP_RADIUS_DP - com.dazix.caelis.data.DebugPreferences.MIN_STAR_TAP_RADIUS_DP) / com.dazix.caelis.data.DebugPreferences.STEP_STAR_TAP_RADIUS_DP - 1,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Visual Tap Indicator Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.night_sky_debug_show_tap_indicator),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Switch(
                    checked = showTapRadiusIndicator,
                    onCheckedChange = onShowTapRadiusIndicatorChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                        checkedTrackColor = if (redVisionMode) palette.primary.copy(alpha = 0.5f) else Color(0xFF00ACC1),
                        uncheckedThumbColor = labelColor,
                        uncheckedTrackColor = Color(0xFF1E293B),
                    ),
                    modifier = Modifier.scale(0.8f),
                )
            }
        }
    }
}

/**
 * Positions floating disambiguation popup near tap coordinates clamped within window bounds.
 */
private class DisambiguationPopupPositionProvider(
    private val tapX: Float,
    private val tapY: Float,
    private val marginPx: Int = 16,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        var x = tapX.roundToInt()
        var y = tapY.roundToInt() + 16

        // If popup overflows bottom edge, place above the tap point
        if (y + popupContentSize.height > windowSize.height - marginPx) {
            y = (tapY.roundToInt() - popupContentSize.height - 16).coerceAtLeast(marginPx)
        }
        // If popup overflows right edge, shift left
        if (x + popupContentSize.width > windowSize.width - marginPx) {
            x = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        }
        if (x < marginPx) {
            x = marginPx
        }
        return IntOffset(x, y)
    }
}

/**
 * Floating contextual disambiguation menu anchored near tap coordinates.
 */
@Composable
private fun CelestialDisambiguationMenu(
    target: NightSkyRenderer.HitTarget.MultipleHits,
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme,
    onSelectCandidate: (NightSkyRenderer.CelestialCandidate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val containerBg = if (redVisionMode) Color(0xFF140000) else MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = if (redVisionMode) palette.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (redVisionMode) palette.primary else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (redVisionMode) palette.secondary else MaterialTheme.colorScheme.onSurfaceVariant

    val positionProvider = remember(target.tapX, target.tapY) {
        DisambiguationPopupPositionProvider(target.tapX, target.tapY)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = containerBg,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, borderColor),
            modifier = modifier
                .widthIn(min = 180.dp, max = 260.dp)
                .wrapContentHeight(),
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .animateContentSize(),
            ) {
                Text(
                    text = stringResource(R.string.celestial_disambiguation_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = subtitleColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                HorizontalDivider(
                    color = borderColor.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )

                target.candidates.forEach { candidate ->
                    val icon = when (candidate) {
                        is NightSkyRenderer.CelestialCandidate.Planet -> AppIcons.Planet
                        is NightSkyRenderer.CelestialCandidate.Star -> AppIcons.Star
                        is NightSkyRenderer.CelestialCandidate.Constellation -> AppIcons.Constellation
                        is NightSkyRenderer.CelestialCandidate.Dso -> AppIcons.DeepSky
                        is NightSkyRenderer.CelestialCandidate.MeteorShower -> AppIcons.MeteorShower
                    }
                    val iconTint = when (candidate) {
                        is NightSkyRenderer.CelestialCandidate.Planet -> if (redVisionMode) palette.primary else Color(0xFFFFB74D)
                        is NightSkyRenderer.CelestialCandidate.Star -> if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
                        is NightSkyRenderer.CelestialCandidate.Constellation -> if (redVisionMode) palette.secondary else Color(0xFF81C784)
                        is NightSkyRenderer.CelestialCandidate.Dso -> if (redVisionMode) palette.primary else Color(0xFFBA68C8)
                        is NightSkyRenderer.CelestialCandidate.MeteorShower -> if (redVisionMode) palette.primary else Color(0xFF00E676)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectCandidate(candidate)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = candidate.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitleText = candidate.subtitle ?: when (candidate) {
                                is NightSkyRenderer.CelestialCandidate.Planet -> stringResource(R.string.celestial_planet_subtitle)
                                is NightSkyRenderer.CelestialCandidate.Star -> String.format(java.util.Locale.US, "mag %.1f", candidate.magnitude)
                                is NightSkyRenderer.CelestialCandidate.Constellation -> stringResource(R.string.celestial_constellation_subtitle)
                                is NightSkyRenderer.CelestialCandidate.Dso -> stringResource(R.string.dso_type_generic)
                                is NightSkyRenderer.CelestialCandidate.MeteorShower -> stringResource(R.string.celestial_meteor_shower_subtitle)
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unified, touch-slop aware gesture detector for the Night Sky AR viewport.
 * Dispatches Tap, Double Tap, Pan (Drag), and Pinch Zoom without event conflicts, drops, or stutter.
 * Zero GC allocations during event processing.
 *
 * Slop protection:
 * - Natural finger roll or minor micro-movement within [viewConfiguration.touchSlop] is preserved as a Tap.
 * - Intentional movement exceeding [touchSlop] engages Pan mode.
 * - Multi-touch (2+ pointers) immediately engages Pinch Zoom and Centroid Pan via standard Compose gestures.
 */
internal suspend fun PointerInputScope.detectArSkyGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: () -> Unit,
    onPan: (deltaX: Float, deltaY: Float) -> Unit,
    onZoom: (zoomRatio: Float) -> Unit,
    bottomGestureSlopPx: () -> Float = { 0f },
) {
    val touchSlop = viewConfiguration.touchSlop
    val touchSlopSq = touchSlop * touchSlop
    val edgeSlop = 28.dp.toPx()
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val doubleTapMinTime = viewConfiguration.doubleTapMinTimeMillis
    var lastTapTime = 0L
    var lastTapX = 0f
    var lastTapY = 0f

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val downX = down.position.x
        val downY = down.position.y
        // A one-finger drag from the system's zones is back or home, never a pan (SystemGestureZone).
        val isSystemGestureStart = SystemGestureZone.contains(
            downX, downY, size.width.toFloat(), size.height.toFloat(),
            sideSlopPx = edgeSlop, bottomSlopPx = bottomGestureSlopPx(),
        )
        var isPanning = false
        var isTransforming = false
        var pastTouchSlop = false

        while (true) {
            val event = awaitPointerEvent()
            val changes = event.changes
            var pressedCount = 0
            var anyConsumed = false
            for (i in changes.indices) {
                if (changes[i].pressed) pressedCount++
                if (changes[i].isConsumed) anyConsumed = true
            }

            if (pressedCount == 0) {
                // All pointers lifted -> check for Tap / DoubleTap
                val upChange = changes.firstOrNull()
                val isCancelledOrConsumed = upChange == null || upChange.isConsumed || anyConsumed
                if (!isCancelledOrConsumed && !pastTouchSlop && !isTransforming) {
                    val upX = upChange.position.x
                    val upY = upChange.position.y
                    val distSq = (upX - downX) * (upX - downX) + (upY - downY) * (upY - downY)
                    if (distSq <= touchSlopSq) {
                        val currentTime = System.currentTimeMillis()
                        val dt = currentTime - lastTapTime
                        val doubleDistSq = (downX - lastTapX) * (downX - lastTapX) + (downY - lastTapY) * (downY - lastTapY)
                        if (dt in doubleTapMinTime..doubleTapTimeout && doubleDistSq <= touchSlopSq) {
                            onDoubleTap()
                            lastTapTime = 0L
                        } else {
                            onTap(Offset(downX, downY))
                            lastTapTime = currentTime
                            lastTapX = downX
                            lastTapY = downY
                        }
                        upChange.consume()
                    }
                }
                break
            }

            if (anyConsumed && !isTransforming && !isPanning) {
                // System or parent intercepted the gesture
                break
            }

            if (pressedCount >= 2) {
                // Multi-touch: 2+ pointers -> pinch zoom and two-finger pan
                isTransforming = true
                pastTouchSlop = true

                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (zoomChange != 1.0f) {
                    onZoom(zoomChange)
                }

                if (panChange.x != 0f || panChange.y != 0f) {
                    onPan(panChange.x, panChange.y)
                }

                for (i in changes.indices) {
                    if (changes[i].pressed && changes[i].positionChanged()) {
                        changes[i].consume()
                    }
                }
            } else if (pressedCount == 1 && !isTransforming) {
                // Single pointer: check touch slop before engaging pan
                val change = changes.firstOrNull { it.pressed } ?: break
                if (change.isConsumed) break

                val totalDistSq = (change.position.x - downX) * (change.position.x - downX) +
                    (change.position.y - downY) * (change.position.y - downY)

                if (!pastTouchSlop && totalDistSq > touchSlopSq) {
                    pastTouchSlop = true
                    // Past the slop it is no longer a tap; from a system zone it is no pan either,
                    // and the movement is left unconsumed for the system.
                    isPanning = !isSystemGestureStart
                }

                if (isPanning) {
                    val deltaX = change.position.x - change.previousPosition.x
                    val deltaY = change.position.y - change.previousPosition.y
                    if (deltaX != 0f || deltaY != 0f) {
                        onPan(deltaX, deltaY)
                    }
                    if (change.positionChanged()) change.consume()
                }
            }
        }
    }
}


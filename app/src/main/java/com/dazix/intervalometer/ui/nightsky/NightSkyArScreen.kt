package com.dazix.intervalometer.ui.nightsky

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.dazix.intervalometer.ui.AppToastHost
import com.dazix.intervalometer.ui.AppToastHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.CameraPreview
import com.dazix.intervalometer.ar.DeviceOrientation
import com.dazix.intervalometer.ar.RedPrimary
import com.dazix.intervalometer.ar.RedSecondary
import com.dazix.intervalometer.ar.paletteForTheme
import com.dazix.intervalometer.domain.nightsky.CelestialBodyDetails
import com.dazix.intervalometer.domain.nightsky.NightVisionState
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import com.dazix.intervalometer.domain.nightsky.StarCatalog
import com.dazix.intervalometer.service.DisplayGeometry
import com.dazix.intervalometer.ui.AppIcons
import com.dazix.intervalometer.ui.Permissions

import android.graphics.BitmapFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightSkyArScreen(
    onBack: () -> Unit,
    viewModel: NightSkyArViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        confirmValueChange = { true },
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    var isLayerSheetOpen by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val renderer = remember { NightSkyRenderer() }
    val maxTouchRadiusPx = with(density) { 48.dp.toPx() }

    // Synchronize ViewModel State with UI State
    LaunchedEffect(uiState.selectedBody, uiState.selectedStarIndex, uiState.selectedConstellationIndex) {
        renderer.selectedStarIndex = uiState.selectedStarIndex
        renderer.selectedConstellationIndex = uiState.selectedConstellationIndex
        if (uiState.selectedBody != null) {
            sheetState.partialExpand()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshLocation()
    }

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

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasFine) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val toastHostState = remember(coroutineScope) { AppToastHostState(coroutineScope) }

    val displayRotation = DisplayGeometry.currentRotation(context)
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 ||
        displayRotation == android.view.Surface.ROTATION_180

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
                redVisionMode = uiState.redVisionMode,
                onHeaderMeasured = { height ->
                    if (height > 0 && height != headerHeightPx) {
                        headerHeightPx = height
                    }
                },
                onFullHeightMeasured = { height ->
                    if (height > 0 && height != fullHeightPx) {
                        fullHeightPx = height
                    }
                },
                nightVisionTheme = uiState.nightVisionState.theme,
            )
        },
        containerColor = Color.Transparent,
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .pointerInput(Unit) {
                    detectTransformGestures(
                        panZoomLock = false,
                        onGesture = { _, pan, zoom, _ ->
                            val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                            val hasPan = pan.x != 0f || pan.y != 0f
                            if (hasPan) {
                                viewModel.onPanGesture(
                                    deltaX = pan.x,
                                    deltaY = pan.y,
                                    viewWidth = viewWidth,
                                    fovDeg = uiState.cameraFovHorizontal,
                                )
                            }
                            if (zoom != 1.0f) {
                                viewModel.adjustZoomBy(zoom)
                            }
                        }
                    )
                }
                .pointerInput(catalog, uiState.planetStates, maxTouchRadiusPx, density) {
                    detectTapGestures(
                        onTap = { offset ->
                            val hitTarget = renderer.findHitAt(
                                tapX = offset.x,
                                tapY = offset.y,
                                maxStarRadiusPx = maxTouchRadiusPx,
                                density = density.density,
                                catalog = catalog,
                                planets = uiState.planetStates,
                            )
                            when (hitTarget) {
                                is NightSkyRenderer.HitTarget.ConstellationHit -> {
                                    renderer.selectedConstellationIndex = hitTarget.index
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectConstellationByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.StarHit -> {
                                    renderer.selectedStarIndex = hitTarget.index
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.selectStarByIndex(hitTarget.index)
                                }
                                is NightSkyRenderer.HitTarget.PlanetHit -> {
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedPlanetId = hitTarget.planetId
                                    viewModel.selectPlanetById(hitTarget.planetId)
                                }
                                is NightSkyRenderer.HitTarget.Miss -> {
                                    renderer.selectedConstellationIndex = -1
                                    renderer.selectedStarIndex = -1
                                    renderer.selectedPlanetId = null
                                    viewModel.clearSelectedBody()
                                }
                            }
                        },
                        onDoubleTap = {
                            viewModel.resetZoom()
                        }
                    )
                }
        ) {
            if (hasCameraPermission) {
                // Live Camera Viewport (rendered underneath AR overlay)
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    lifecycleOwner = lifecycleOwner,
                    isPortrait = isPortrait,
                    onFovCalculated = viewModel::setCameraFov,
                )

                // 3D Night Sky AR Overlay Canvas with AGSL Milky Way, Atmosphere, Landscape, and Opaque Sky Dome layers
                NightSkyOverlayCanvas(
                    renderer = renderer,
                    orientation = orientation,
                    displayRotation = displayRotation,
                    fovHorizontalDegrees = uiState.cameraFovHorizontal,
                    zoomRatio = uiState.zoomRatio,
                    catalog = catalog,
                    lstDegrees = uiState.lstDegrees,
                    latitudeDegrees = uiState.location.latitude,
                    nightVisionState = uiState.nightVisionState,
                    isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                    isMilkyWayVisible = uiState.isMilkyWayVisible,
                    isAtmosphereVisible = uiState.isAtmosphereVisible,
                    isLandscapeVisible = uiState.isLandscapeVisible,
                    isGridVisible = uiState.isGridVisible,
                    isConstellationsVisible = uiState.isConstellationsVisible,
                    isConstellationArtVisible = uiState.isConstellationArtVisible,
                    selectedBody = uiState.selectedBody,
                    selectedPlanetId = uiState.selectedPlanetId,
                    planets = uiState.planetStates,
                    debugArtOffsetX = uiState.debugArtOffsetX,
                    debugArtOffsetY = uiState.debugArtOffsetY,
                    debugArtScaleMultiplier = uiState.debugArtScaleMultiplier,
                    debugArtRotationOffset = uiState.debugArtRotationOffset,
                    isDebugFocusRegionVisible = uiState.isDebugFocusRegionVisible,
                    debugFocusRadiusMultiplier = uiState.debugFocusRadiusMultiplier,
                    debugFocusDisplayThreshold = uiState.debugFocusDisplayThreshold,
                    onVisibleStarsUpdated = viewModel::updateVisibleStarCount,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Camera Permission Request Surface
                NightSkyPermissionPrompt(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onBack = onBack,
                    redVisionMode = uiState.redVisionMode,
                    nightVisionTheme = uiState.nightVisionState.theme,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // In-App Calibration Debugger Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isDebugOptionsEnabled && uiState.isDebugCalibrationVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, top = 80.dp, bottom = 80.dp),
            ) {
                CalibrationDebugOverlay(
                    offsetX = uiState.debugArtOffsetX,
                    offsetY = uiState.debugArtOffsetY,
                    scaleMultiplier = uiState.debugArtScaleMultiplier,
                    rotationOffset = uiState.debugArtRotationOffset,
                    redVisionMode = uiState.redVisionMode,
                    onOffsetXChange = viewModel::setDebugArtOffsetX,
                    onOffsetYChange = viewModel::setDebugArtOffsetY,
                    onScaleMultiplierChange = viewModel::setDebugArtScaleMultiplier,
                    onRotationOffsetChange = viewModel::setDebugArtRotationOffset,
                    onReset = viewModel::resetDebugCalibration,
                    onClose = { viewModel.setDebugCalibrationVisible(false) },
                    nightVisionTheme = uiState.nightVisionState.theme,
                )
            }

            // In-App Focus Area Debugger Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isDebugOptionsEnabled && uiState.isDebugFocusRegionVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, top = 80.dp, bottom = 80.dp),
            ) {
                FocusRegionDebugOverlay(
                    radiusMultiplier = uiState.debugFocusRadiusMultiplier,
                    displayThreshold = uiState.debugFocusDisplayThreshold,
                    redVisionMode = uiState.redVisionMode,
                    onRadiusMultiplierChange = viewModel::setDebugFocusRadiusMultiplier,
                    onDisplayThresholdChange = viewModel::setDebugFocusDisplayThreshold,
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
                    active = uiState.isSearchActive,
                    onQueryChange = viewModel::setSearchQuery,
                    onActiveChange = viewModel::setSearchActive,
                    onSelectItem = { item -> viewModel.selectCelestialBody(item) },
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
                    orientation = orientation,
                    cameraFovHorizontal = uiState.cameraFovHorizontal,
                    zoomRatio = uiState.zoomRatio,
                    isCalibrated = uiState.isCalibrated,
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
                isAtmosphereVisible = uiState.isAtmosphereVisible,
                isMilkyWayVisible = uiState.isMilkyWayVisible,
                isConstellationsVisible = uiState.isConstellationsVisible,
                isConstellationArtVisible = uiState.isConstellationArtVisible,
                isGridVisible = uiState.isGridVisible,
                isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                redVisionMode = uiState.redVisionMode,
                isSensorTrackingEnabled = uiState.isSensorTrackingEnabled,
                isKeepScreenOn = uiState.keepScreenOn,
                isDebugCalibrationVisible = uiState.isDebugCalibrationVisible,
                isDebugFocusRegionVisible = uiState.isDebugFocusRegionVisible,
                isDebugOptionsEnabled = uiState.isDebugOptionsEnabled,
                onToggleLandscapeVisible = viewModel::toggleLandscapeVisible,
                onToggleAtmosphereVisible = viewModel::toggleAtmosphereVisible,
                onToggleMilkyWayVisible = viewModel::toggleMilkyWayVisible,
                onToggleConstellationsVisible = viewModel::toggleConstellationsVisible,
                onToggleConstellationArtVisible = viewModel::toggleConstellationArtVisible,
                onToggleGridVisible = viewModel::toggleGridVisible,
                onToggleSkyBackgroundOpaque = viewModel::toggleSkyBackgroundOpaque,
                onToggleRedVisionMode = viewModel::toggleRedVisionMode,
                onToggleSensorTracking = viewModel::toggleSensorTracking,
                onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                onToggleDebugCalibration = viewModel::toggleDebugCalibration,
                onToggleDebugFocusRegion = viewModel::toggleDebugFocusRegion,
                nightVisionTheme = uiState.nightVisionState.theme,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun NightSkyOverlayCanvas(
    renderer: NightSkyRenderer,
    orientation: DeviceOrientation?,
    displayRotation: Int,
    fovHorizontalDegrees: Float,
    zoomRatio: Float,
    catalog: com.dazix.intervalometer.data.nightsky.NightSkyCatalog?,
    lstDegrees: Double,
    latitudeDegrees: Double,
    nightVisionState: NightVisionState,
    isSkyBackgroundOpaque: Boolean,
    isMilkyWayVisible: Boolean,
    isAtmosphereVisible: Boolean,
    isLandscapeVisible: Boolean,
    isGridVisible: Boolean,
    isConstellationsVisible: Boolean,
    isConstellationArtVisible: Boolean,
    selectedBody: CelestialBodyDetails? = null,
    selectedPlanetId: String? = null,
    planets: List<com.dazix.intervalometer.domain.nightsky.PlanetState> = emptyList(),
    debugArtOffsetX: Float = 0f,
    debugArtOffsetY: Float = 0f,
    debugArtScaleMultiplier: Float = 1.0f,
    debugArtRotationOffset: Float = 0f,
    isDebugFocusRegionVisible: Boolean = false,
    debugFocusRadiusMultiplier: Float = 0.48f,
    debugFocusDisplayThreshold: Float = 0.0f,
    onVisibleStarsUpdated: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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

    val milkyWayBitmap = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, R.drawable.milky_way_starless)
        } catch (_: Exception) {
            null
        }
    }

    val landscapeBitmap = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, R.drawable.landscape_photorealistic)
        } catch (_: Exception) {
            null
        }
    }

    renderer.skyDomeRenderer.updateLabels(zenithLabel, nadirLabel, cardinalLabels)
    renderer.applyTheme(nightVisionState)
    renderer.selectedPlanetId = selectedPlanetId

    LaunchedEffect(milkyWayBitmap, landscapeBitmap) {
        milkyWayBitmap?.let { renderer.setMilkyWayBitmap(it) }
        landscapeBitmap?.let { renderer.setLandscapeBitmap(it) }
    }

    Canvas(modifier = modifier) {
        if (orientation == null) return@Canvas

        val w = size.width
        val h = size.height
        val matrix = orientation.rotationMatrix

        // Configure optical star halo/core stroke widths scaling by screen density, zoom, and artwork layer highlight
        renderer.updateOpticalPaints(
            zoomFactor = zoomRatio,
            density = density,
            redVisionMode = nightVisionState.isActive,
            constellationPx = 1.8.dp.toPx(),
            boostStarAndLines = isConstellationArtVisible,
        )

        // Layer 0 & Layer 1: Opaque Sky Dome background and hardware-accelerated AGSL Milky Way & Atmosphere
        renderer.drawSkyBackground(
            drawScope = this,
            matrix = matrix,
            lstDegrees = lstDegrees,
            latitudeDegrees = latitudeDegrees,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
            w = w,
            h = h,
            isSkyBackgroundOpaque = isSkyBackgroundOpaque,
            isMilkyWayVisible = isMilkyWayVisible,
            isAtmosphereVisible = isAtmosphereVisible,
            isRedVision = nightVisionState.isActive,
            theme = nightVisionState.theme,
        )

        // Layer 2: Dual-pass batch render visible stars (halo + core), constellation lines, and artwork illustrations
        val visibleCount = renderer.drawNightSky(
            drawScope = this,
            catalog = catalog,
            lstDegrees = lstDegrees,
            latitudeDegrees = latitudeDegrees,
            matrix = matrix,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
            w = w,
            h = h,
            zoomFactor = zoomRatio,
            showConstellations = isConstellationsVisible,
            showConstellationArt = isConstellationArtVisible,
            isAtmosphereVisible = isAtmosphereVisible,
            context = context,
            debugOffsetX = debugArtOffsetX,
            debugOffsetY = debugArtOffsetY,
            debugScaleMultiplier = debugArtScaleMultiplier,
            debugRotationOffset = debugArtRotationOffset,
            isDebugFocusRegionVisible = isDebugFocusRegionVisible,
            debugFocusRadiusMultiplier = debugFocusRadiusMultiplier,
            debugFocusDisplayThreshold = debugFocusDisplayThreshold,
            planets = planets,
        )

        // Layer 3: Hardware-accelerated AGSL Photorealistic Landscape ground & tree line (occludes stars below horizon)
        renderer.drawLandscape(
            drawScope = this,
            matrix = matrix,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
            w = w,
            h = h,
            isLandscapeVisible = isLandscapeVisible,
            isRedVision = nightVisionState.isActive,
            theme = nightVisionState.theme,
        )

        // Layer 4: 3D Celestial Dome Coordinate Grid (Horizon, Meridians, Altitude Rings, Cardinals)
        if (isGridVisible) {
            renderer.skyDomeRenderer.drawSkyDomeGrid(
                drawScope = this,
                matrix = matrix,
                displayRotation = displayRotation,
                fovH = fovHorizontalDegrees,
                redVisionMode = nightVisionState.isActive,
                w = w,
                h = h,
                theme = nightVisionState.theme,
            )
        }

        // Layer 5: 3D Celestial Selection Reticle (Broken Crosshair & Star Name Text)
        renderer.drawSelectionReticle(
            drawScope = this,
            selectedBody = selectedBody,
            matrix = matrix,
            lstDegrees = lstDegrees,
            latitudeDegrees = latitudeDegrees,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
            w = w,
            h = h,
            density = density,
        )

        onVisibleStarsUpdated(visibleCount)
    }
}

@Composable
private fun NightSkyTopBar(
    hasGpsFix: Boolean,
    locationText: String,
    orientation: DeviceOrientation?,
    cameraFovHorizontal: Float,
    zoomRatio: Float,
    isCalibrated: Boolean,
    redVisionMode: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenLayers: () -> Unit,
    onResetZoom: () -> Unit,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
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
                Text(
                    text = "📐 $fovLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isZoomed) accentColor else subTextColor,
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

            IconButton(
                onClick = onOpenLayers,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Layers,
                    contentDescription = stringResource(R.string.night_sky_layers_title),
                    tint = if (redVisionMode) palette.primary else Color.White,
                )
            }
        }
    }
}

@Composable
private fun NightSkyBottomHud(
    lstFormatted: String,
    visibleStarCount: Int,
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
        }
    }
}

@Composable
private fun NightSkyPermissionPrompt(
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val textColor = if (redVisionMode) palette.primary else Color.White
    val containerColor = if (redVisionMode) Color(0xFF000000) else Color(0xFF10141C)
    val btnColor = if (redVisionMode) palette.primary else Color(0xFF00ACC1)

    Box(
        modifier = modifier
            .background(containerColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.night_sky_title),
                color = textColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.sun_ar_camera_permission_desc),
                color = textColor.copy(alpha = 0.8f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRequestPermission,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = btnColor,
                    contentColor = if (redVisionMode) Color.Black else Color.White,
                ),
            ) {
                Text(stringResource(R.string.sun_ar_camera_grant))
            }
            Button(
                onClick = onBack,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = textColor,
                ),
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}


@Composable
private fun CalibrationDebugOverlay(
    offsetX: Float,
    offsetY: Float,
    scaleMultiplier: Float,
    rotationOffset: Float,
    redVisionMode: Boolean,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onScaleMultiplierChange: (Float) -> Unit,
    onRotationOffsetChange: (Float) -> Unit,
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
                        imageVector = AppIcons.Setup,
                        contentDescription = null,
                        tint = if (redVisionMode) palette.primary else Color(0xFF4DD0E1),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.night_sky_debug_calibration),
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

            // X Offset Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_offset_x, offsetX),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = offsetX,
                    onValueChange = onOffsetXChange,
                    valueRange = -500f..500f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Y Offset Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_offset_y, offsetY),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = offsetY,
                    onValueChange = onOffsetYChange,
                    valueRange = -500f..500f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Scale Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_scale, scaleMultiplier),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = scaleMultiplier,
                    onValueChange = onScaleMultiplierChange,
                    valueRange = 0.5f..2.5f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }

            // Rotation Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.night_sky_debug_rotation, rotationOffset),
                    color = labelColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = rotationOffset,
                    onValueChange = onRotationOffsetChange,
                    valueRange = -90f..90f,
                    colors = sliderColors,
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}

@Composable
private fun FocusRegionDebugOverlay(
    radiusMultiplier: Float,
    displayThreshold: Float,
    redVisionMode: Boolean,
    onRadiusMultiplierChange: (Float) -> Unit,
    onDisplayThresholdChange: (Float) -> Unit,
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
        }
    }
}


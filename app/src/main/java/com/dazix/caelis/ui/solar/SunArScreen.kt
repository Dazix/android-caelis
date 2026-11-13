package com.dazix.caelis.ui.solar

import android.Manifest
import android.content.Context
import android.hardware.SensorManager
import android.text.format.DateFormat
import android.hardware.camera2.CameraCharacteristics
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.HorizontalDivider
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.ui.HeightAboveTerrainRow
import com.dazix.caelis.ui.LayerGroupCard
import com.dazix.caelis.ui.LayerToggleRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.dazix.caelis.ui.AppToastHost
import com.dazix.caelis.ui.AppToastHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.caelis.R
import com.dazix.caelis.ar.CameraFovCalculator
import com.dazix.caelis.ar.CameraFrameSource
import com.dazix.caelis.ar.CameraPreview
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.PreviewFrameClock
import com.dazix.caelis.domain.horizon.SkylineRefusal
import com.dazix.caelis.domain.solar.BubbleInterval
import com.dazix.caelis.domain.solar.CardinalDirection
import com.dazix.caelis.domain.solar.LunarCoordinates
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.SolarTrajectory
import com.dazix.caelis.service.DisplayGeometry
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.CenteredBar
import com.dazix.caelis.ui.cardinalName
import com.dazix.caelis.ui.ImmersiveModeEffect
import com.dazix.caelis.ui.Permissions
import com.dazix.caelis.ui.SegmentedChoice
import com.dazix.caelis.ui.formatOffsetDegrees
import com.dazix.caelis.ui.nameRes
import com.dazix.caelis.ui.rememberBottomGestureSlopPx
import com.dazix.caelis.ui.rememberSkyPoseRig
import com.dazix.caelis.domain.arframe.SystemGestureZone
import androidx.compose.runtime.rememberUpdatedState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dazix.caelis.ar.stillBackCameraFov
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.ui.location.PlacesButton
import com.dazix.caelis.ui.location.PlacesSheet
import com.dazix.caelis.ui.location.PlacesSheetColors
import com.dazix.caelis.ui.location.placeChipLabel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

// High-Contrast Solar Color Palette
private val NormalGoldSolar = Color(0xFFFFB300)
private val NormalSunrise = Color(0xFFFF7043)
private val NormalSunset = Color(0xFFE64A19)

/** Mirrors [com.dazix.caelis.ar.scene.NormalTerrain]; the screen keeps its own copy of the palette. */
private val NormalTerrain = Color(0xFF8BC34A)

// Cool Lunar Color Palette
private val MoonSilverCyan = Color(0xFF80DEEA)
private val MoonRiseColor = Color(0xFF4FC3F7)
private val MoonSetColor = Color(0xFF26A69A)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SunArScreen(
    onBack: () -> Unit,
    onOpenTerrainSettings: () -> Unit = {},
    viewModel: SunArViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isApproximateLocation by viewModel.isApproximateLocation.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val alignAzimuthOffset by viewModel.panAzimuthOffset.collectAsStateWithLifecycle()
    val alignElevationOffset by viewModel.panPitchOffset.collectAsStateWithLifecycle()
    val autoAlignResult by viewModel.autoAlign.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val toastHostState = remember(coroutineScope) { AppToastHostState(coroutineScope) }

    var hasCameraPermission by remember {
        mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA))
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    val heightAboveTerrainCm by viewModel.heightAboveTerrainCm.collectAsStateWithLifecycle()
    // The height being stepped in the open sheet. Committed when the sheet closes, not per tap: each
    // value is a profile computation (and maybe a download), and the steps in between are not wanted.
    var sheetHeightCm by remember { mutableStateOf<Int?>(null) }
    val openInfoSheet = {
        sheetHeightCm = heightAboveTerrainCm
        showInfoSheet = true
    }
    val closeInfoSheet = {
        sheetHeightCm?.let { if (it != heightAboveTerrainCm) viewModel.setHeightAboveTerrain(it) }
        sheetHeightCm = null
        showInfoSheet = false
    }
    var showPlacesSheet by remember { mutableStateOf(false) }
    // A virtual place: no camera, no alignment, the place's own skyline on a neutral backdrop.
    val isVirtual = uiState.observer.isVirtual
    val previewFrameClock = remember { PreviewFrameClock() }
    // The camera picture is drawn by the sky's own Filament frame; see CameraFrameSource.
    val cameraFrameSource = remember(previewFrameClock) { CameraFrameSource(previewFrameClock) }
    DisposableEffect(cameraFrameSource) {
        onDispose { cameraFrameSource.release() }
    }
    // Who owns the camera (CameraX, or ARCore if the user agreed) and whose pose draws the sky.
    val poseRig = rememberSkyPoseRig(
        sensorPose = viewModel.sensorPose,
        cameraSource = cameraFrameSource,
        cameraPermission = hasCameraPermission,
        onFov = viewModel::setCameraFov,
    )
    val cameraXEnabled by poseRig.cameraXEnabled.collectAsStateWithLifecycle()
    val poseIndicator by poseRig.indicator.collectAsStateWithLifecycle()
    val arCoreConsented by poseRig.consentedFlow.collectAsStateWithLifecycle()
    // Automatic alignment reads its frame from whichever camera is live.
    val frameGrabber by poseRig.frameGrabber.collectAsStateWithLifecycle()
    LaunchedEffect(frameGrabber) { viewModel.attachFrameGrabber(frameGrabber) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshLocation()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        val hasLocation = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocation) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    BackHandler {
        when {
            showPlacesSheet -> showPlacesSheet = false
            showDatePicker -> showDatePicker = false
            showInfoSheet -> closeInfoSheet()
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

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            val hasLocation = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!hasLocation) {
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    val displayRotation = DisplayGeometry.currentRotation(context)
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 ||
        displayRotation == android.view.Surface.ROTATION_180

    // Report what the automatic alignment did, including when it refused. A silent no-op reads as a
    // broken button; a silent success reads as a broken compass.
    LaunchedEffect(autoAlignResult) {
        val result = autoAlignResult
        val messageId = when (result) {
            null, AutoAlignResult.Working -> null
            AutoAlignResult.NoProfile -> R.string.ar_align_auto_no_profile
            AutoAlignResult.NoCamera -> R.string.ar_align_auto_no_camera
            is AutoAlignResult.NoSkyline -> when (result.reason) {
                SkylineRefusal.NOT_TERRAIN_SHAPED,
                SkylineRefusal.NO_DISTINCT_MINIMUM,
                SkylineRefusal.RESIDUAL_TOO_HIGH,
                SkylineRefusal.TOO_FEW_SUPPORTING,
                -> R.string.ar_align_auto_no_match
                SkylineRefusal.AMBIGUOUS_MINIMUM -> R.string.ar_align_auto_ambiguous
                SkylineRefusal.OUT_OF_RANGE -> R.string.ar_align_auto_out_of_range
                SkylineRefusal.PROFILE_UNUSABLE -> R.string.ar_align_auto_no_profile
                SkylineRefusal.NO_GEOMETRY,
                SkylineRefusal.TOO_FEW_COLUMNS,
                null,
                -> R.string.ar_align_auto_no_skyline
            }
            is AutoAlignResult.Aligned -> if (result.azimuthDetermined) {
                R.string.ar_align_auto_done
            } else {
                R.string.ar_align_auto_vertical_only
            }
        }
        if (messageId != null) {
            toastHostState.showToast(resId = messageId, icon = AppIcons.Compass)
            viewModel.consumeAutoAlignResult()
        }
    }

    // With the camera off under a virtual place, a field of view never measured is predicted from the
    // lens, as Night Sky does without its camera; a measured one stays.
    LaunchedEffect(isVirtual, isPortrait) {
        if (!isVirtual || uiState.isDynamicStreamCalculated) return@LaunchedEffect
        val bounds = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val fov = withContext(Dispatchers.Default) {
            stillBackCameraFov(context, bounds.width().toFloat(), bounds.height().toFloat(), isPortrait)
        }
        fov?.let(viewModel::setCameraFov)
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // The automatic alignment fits in the viewport the overlay is drawn in, on the sensor path only.
    LaunchedEffect(viewportSize, displayRotation, poseIndicator) {
        viewModel.setAutoAlignEnvironment(
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
            displayRotation = displayRotation,
            sensorsOwnPose = poseIndicator == com.dazix.caelis.ar.pose.PoseIndicator.SENSOR,
        )
    }
    val isSearchingForSkyline by viewModel.isSearchingForSkyline.collectAsStateWithLifecycle()
    val bottomGestureSlop = rememberUpdatedState(rememberBottomGestureSlopPx())

    Box(
        modifier = Modifier
            .onSizeChanged { viewportSize = it }
            .fillMaxSize()
            // Dragging aligns the overlay with what the camera sees; it is not a pan of a frozen
            // sky. The sensors keep tracking throughout, so the correction the finger applies is the
            // compass error and nothing else. Under a virtual place there is no camera to align
            // with, and the same drag pans the sky instead (Observer.dragPans), ended by the compass
            // button in the top bar. There is deliberately no zoom here: it would scale the image and
            // the overlay together and only add a variable to the alignment.
            .pointerInput(Unit) {
                val edgeSlop = 28.dp.toPx()
                val touchSlop = viewConfiguration.touchSlop
                val touchSlopSq = touchSlop * touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downX = down.position.x
                    val downY = down.position.y
                    // A swipe home or back is the system's, never a calibration (SystemGestureZone).
                    if (SystemGestureZone.contains(
                            downX, downY, size.width.toFloat(), size.height.toFloat(),
                            sideSlopPx = edgeSlop, bottomSlopPx = bottomGestureSlop.value,
                        )
                    ) {
                        return@awaitEachGesture
                    }
                    var isPanning = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        if (changes.any { it.isConsumed }) break
                        val change = changes.firstOrNull { it.pressed } ?: break

                        val deltaX = change.position.x - change.previousPosition.x
                        val deltaY = change.position.y - change.previousPosition.y

                        if (!isPanning) {
                            val totalDistSq = (change.position.x - downX) * (change.position.x - downX) +
                                (change.position.y - downY) * (change.position.y - downY)
                            if (totalDistSq > touchSlopSq) {
                                isPanning = true
                            }
                        }

                        if (isPanning) {
                            if (deltaX != 0f || deltaY != 0f) {
                                viewModel.onPanGesture(
                                    deltaX = deltaX,
                                    deltaY = deltaY,
                                    viewWidth = size.width.toFloat().coerceAtLeast(1f),
                                    fovDeg = uiState.cameraFovHorizontal,
                                )
                            }
                            if (change.positionChanged()) change.consume()
                        }
                    }
                }
            }
    ) {
        if (hasCameraPermission || isVirtual) {
            // Camera stream with dynamic FOV extraction; the picture itself is drawn by SunArGpuSky below.
            // Under a virtual place the camera is unbound: it would show this street under that skyline.
            if (hasCameraPermission) CameraPreview(
                modifier = Modifier.fillMaxSize(),
                lifecycleOwner = lifecycleOwner,
                isPortrait = isPortrait,
                // The rig forwards CameraX's field of view only while CameraX is the camera.
                onFovCalculated = poseRig::onCameraXFov,
                analyzeFrames = true,
                onFrameGrabber = poseRig::onCameraXGrabber,
                frameClock = previewFrameClock,
                frameSource = cameraFrameSource,
                displayRotation = displayRotation,
                cameraEnabled = cameraXEnabled && uiState.observer.cameraWanted,
                onCameraReleased = poseRig::onCameraXReleased,
            )

            // Grid, terrain silhouette and day arcs on the GPU; labels, bubbles and pointers above.
            SunArGpuSky(
                orientation = orientation,
                onGetRenderMatrix = viewModel::getRenderMatrix,
                rig = poseRig,
                displayRotation = displayRotation,
                fovHorizontalDegrees = uiState.cameraFovHorizontal,
                solarTrajectory = uiState.trajectory,
                lunarTrajectory = uiState.lunarTrajectory,
                currentSunPos = uiState.currentSunPosition,
                currentMoonPos = uiState.currentMoonPosition,
                horizonProfile = uiState.horizonProfile.takeIf { uiState.horizonSettings.enabled },
                shadeOccludedTrajectory = uiState.horizonSettings.shadeOccludedTrajectory,
                terrainTimes = uiState.terrainTimes,
                showPeakNames = uiState.horizonSettings.peakNamesInSunAr,
                modifier = Modifier.fillMaxSize(),
                virtualPlace = isVirtual,
            )
        } else {
            // Camera Permission Needed Surface
            CameraPermissionPrompt(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top App Bar Controls with HUD. Everything anchored to the top moves by one shift, so the bar
        // clears the camera cutout and the chips under it move with it.
        val hudTopShift = com.dazix.caelis.ui.rememberHudTopShift()
        SunArTopBar(
            hasGpsFix = uiState.hasGpsFix,
            locationText = uiState.location.formatShort(approximate = isApproximateLocation),
            virtualPlaceLabel = uiState.observer.place?.let { placeChipLabel(it) },
            calibrationEnabled = !isVirtual,
            orientation = orientation,
            cameraFovHorizontal = uiState.cameraFovHorizontal,
            isDynamicStreamCalculated = uiState.isDynamicStreamCalculated,
            isCalibrated = uiState.isCalibrated,
            isPanned = uiState.isPanned,
            onEndPan = {
                toastHostState.showToast(
                    resId = R.string.calib_reset_toast,
                    icon = AppIcons.Compass,
                )
                viewModel.endPan()
            },
            horizonState = uiState.horizonState,
            onBack = onBack,
            onToggleInfo = { if (showInfoSheet) closeInfoSheet() else openInfoSheet() },
            onResetCalibration = {
                toastHostState.showToast(
                    resId = R.string.calib_reset_toast,
                    icon = AppIcons.Compass,
                )
                viewModel.resetCalibration()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 36.dp + hudTopShift, start = 12.dp, end = 12.dp),
        )
        val headingState by viewModel.headingState.collectAsStateWithLifecycle()
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp + hudTopShift, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.dazix.caelis.ui.TerrainStatusChip(
                state = uiState.horizonState,
                layerOn = uiState.horizonSettings.enabled,
                onOpenSettings = onOpenTerrainSettings,
            )
            val offer = uiState.horizonState as? com.dazix.caelis.data.horizon.HorizonState.AwaitingConfirmation
            // Closed for this offer only: a different run (another spot, another size) offers again.
            var offerDismissed by remember(offer) { mutableStateOf(false) }
            if (offer != null && uiState.horizonSettings.enabled && !offerDismissed) {
                com.dazix.caelis.ui.horizon.TerrainDownloadOfferChip(
                    state = offer,
                    onDownload = viewModel::confirmTerrainDownload,
                    onDismiss = { offerDismissed = true },
                    accentColor = NormalTerrain,
                )
            }
            com.dazix.caelis.ui.HeightAboveTerrainChip(
                heightCm = heightAboveTerrainCm,
                onClick = openInfoSheet,
            )
            if (isSearchingForSkyline) SkylineSearchChip()
            com.dazix.caelis.ui.HeadingStatusChip(state = headingState)
            com.dazix.caelis.ui.PoseSourceChip(indicator = poseIndicator, arCoreConsented = arCoreConsented)
        }

        // Diagnostic HUD for terrain horizon metrics (Auto-Range & DEM tiles)
        if (uiState.debugPreferences.showTerrainMetrics) {
            com.dazix.caelis.ui.horizon.TerrainDebugOverlay(
                effectiveRangeKm = uiState.effectiveRangeKm,
                activeTileCount = uiState.activeTileCount,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 92.dp + hudTopShift, start = 12.dp),
            )
        }

        // Bottom Controls Layer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Date Navigator Bar
            DateNavigatorBar(
                selectedDate = uiState.selectedDate,
                isToday = uiState.isToday,
                onPrevDay = viewModel::prevDay,
                onNextDay = viewModel::nextDay,
                onToday = viewModel::setToday,
                onOpenDatePicker = { showDatePicker = true },
                place = uiState.observer.place,
                onOpenPlaces = { showPlacesSheet = true },
            )

            // Interval Selector Pills
            SegmentedChoice(
                options = BubbleInterval.entries,
                selected = uiState.bubbleInterval,
                onSelect = viewModel::setBubbleInterval,
                modifier = Modifier.fillMaxWidth(),
            ) { interval ->
                interval.formatLabel()
            }
        }

        if (showInfoSheet) {
            ModalBottomSheet(
                onDismissRequest = closeInfoSheet,
                containerColor = Color(0xF2181818),
                contentColor = Color.White,
                scrimColor = Color.Black.copy(alpha = 0.5f),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF718096)) },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                SunArSettingsSheet(
                    solarTrajectory = uiState.trajectory,
                    lunarTrajectory = uiState.lunarTrajectory,
                    keepScreenOn = uiState.keepScreenOn,
                    isCalibrated = uiState.isCalibrated,
                    onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                    onResetCalibration = {
                        toastHostState.showToast(
                            resId = R.string.calib_reset_toast,
                            icon = AppIcons.Compass,
                        )
                        viewModel.resetCalibration()
                    },
                    terrainTimes = uiState.terrainTimes,
                    horizonEnabled = uiState.horizonSettings.enabled,
                    onToggleHorizon = viewModel::requestToggleHorizon,
                    peakNamesOn = uiState.horizonSettings.peakNamesInSunAr,
                    onTogglePeakNames = viewModel::togglePeakNames,
                    heightAboveTerrainCm = sheetHeightCm ?: heightAboveTerrainCm,
                    onHeightAboveTerrainChange = { sheetHeightCm = it },
                    alignAzimuthOffsetDeg = alignAzimuthOffset,
                    alignElevationOffsetDeg = alignElevationOffset,
                    compassAccuracy = orientation?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
                    onNudgeAlignment = viewModel::nudgeCalibration,
                    isAutoAligning = autoAlignResult is AutoAlignResult.Working,
                    onAutoAlign = {
                        viewModel.autoAlign(
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                            displayRotation = displayRotation,
                        )
                    },
                    alignmentAvailable = !isVirtual,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }

        if (showPlacesSheet) {
            PlacesSheet(
                places = uiState.places,
                selectedId = uiState.observer.place?.id,
                syncEnabled = uiState.placeSyncEnabled,
                gpsLocation = uiState.location.takeIf { uiState.hasGpsFix },
                colors = PlacesSheetColors(
                    container = Color(0xF2181818),
                    content = Color.White,
                    accent = NormalGoldSolar,
                ),
                onSelect = viewModel::selectPlace,
                onSave = viewModel::savePlace,
                onDelete = viewModel::deletePlace,
                onSyncChange = viewModel::setPlaceSync,
                onDismiss = { showPlacesSheet = false },
            )
        }

        // Terrain horizon: the one-off network question, only shown when enabling.
        if (uiState.showConsentDialog) {
            HorizonConsentDialog(
                initialWifiOnly = uiState.horizonSettings.wifiOnly,
                onAllow = { wifiOnly -> viewModel.answerNetworkConsent(granted = true, wifiOnly = wifiOnly) },
                onNever = { viewModel.answerNetworkConsent(granted = false) },
            )
        }

        // Theme-Aware Floating Toast
        AppToastHost(
            hostState = toastHostState,
            containerColor = Color(0xEE1E1E1E),
            contentColor = NormalGoldSolar,
            borderColor = NormalGoldSolar.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
        )

        // Date Picker Dialog
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = uiState.selectedDate
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { epochMs ->
                            val localDate = Instant.ofEpochMilli(epochMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.setDate(localDate)
                        }
                        showDatePicker = false
                    }) {
                        Text(stringResource(R.string.sun_ar_select_date))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                },
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}
@Composable
internal fun SunArTopBar(
    hasGpsFix: Boolean,
    locationText: String,
    orientation: DeviceOrientation?,
    cameraFovHorizontal: Float,
    isDynamicStreamCalculated: Boolean = true,
    isCalibrated: Boolean,
    onBack: () -> Unit,
    onToggleInfo: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
    horizonState: com.dazix.caelis.data.horizon.HorizonState = com.dazix.caelis.data.horizon.HorizonState.Idle,
    /** The virtual place shown instead of GPS ([placeChipLabel]), or null. */
    virtualPlaceLabel: String? = null,
    /** False under a virtual place: the calibration reset is disabled with the rest of the alignment. */
    calibrationEnabled: Boolean = true,
    /** A pan holds the sensors (virtual place only); the compass button then ends it. */
    isPanned: Boolean = false,
    onEndPan: () -> Unit = {},
) {
    val containerBg = Color(0xDD1A1A1A)
    val containerBorder = Color(0x33FFFFFF)
    val accentColor = NormalGoldSolar
    val textColor = Color.White
    val subTextColor = Color(0xCCFFFFFF)

    CenteredBar(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(containerBg)
            .border(1.dp, containerBorder, CircleShape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        start = {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = textColor,
                )
            }
        },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPanned) {
                    // Ends the pan only; the stored calibration is untouched (SunArViewModel.endPan).
                    IconButton(
                        onClick = onEndPan,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Compass,
                            contentDescription = stringResource(R.string.sun_ar_back_to_live),
                            tint = accentColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else if (isCalibrated) {
                    IconButton(
                        onClick = onResetCalibration,
                        enabled = calibrationEnabled,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Compass,
                            contentDescription = stringResource(R.string.sun_ar_calib_reset),
                            tint = if (calibrationEnabled) accentColor else accentColor.copy(alpha = 0.38f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onToggleInfo,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.MoreVert,
                        contentDescription = stringResource(R.string.sun_ar_title),
                        tint = accentColor,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (virtualPlaceLabel != null) {
                    Icon(
                        imageVector = AppIcons.Place,
                        contentDescription = stringResource(R.string.places_virtual_marker),
                        tint = accentColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = virtualPlaceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (hasGpsFix) Color(0xFF66BB6A) else Color(0xFFFFCA28),
                                CircleShape
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
                if (horizonState is com.dazix.caelis.data.horizon.HorizonState.Locating) {
                    Text(
                        text = if (horizonState.satellites) "• 🛰 GPS…" else "• 📍…",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                // Download and compute progress is TerrainStatusChip's, under the bar.
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val az = orientation?.cameraAzimuthDegrees?.toInt() ?: 0
                val cardinal = cardinalName(CardinalDirection.of(az.toDouble()))
                Text(
                    text = "🧭 $az° $cardinal",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = subTextColor,
                )
                val fovColor = if (isDynamicStreamCalculated) {
                    Color(0xFF81C784)
                } else {
                    MaterialTheme.colorScheme.error
                }
                Text(
                    text = "📐 " + stringResource(R.string.sun_ar_fov_label, cameraFovHorizontal),
                    style = MaterialTheme.typography.labelSmall,
                    color = fovColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
internal fun DateNavigatorBar(
    selectedDate: LocalDate,
    isToday: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onOpenDatePicker: () -> Unit,
    place: SavedPlace? = null,
    onOpenPlaces: (() -> Unit)? = null,
) {
    // The locale's own order and punctuation: a fixed "d. MMM" read "czw. 5. lis" in Polish.
    val formatter = remember {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    }

    val containerBg = Color(0xDD1A1A1A)
    val containerBorder = Color(0x33FFFFFF)
    val accentColor = NormalGoldSolar
    val textColor = Color.White

    // The places button stands beside the date pill as a circle of its own, the pill's height: it
    // changes where, not when, and inside the pill it read as one more date control.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(containerBg)
                .border(1.dp, containerBorder, CircleShape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrevDay, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.sun_ar_prev_day),
                    tint = textColor,
                )
            }

            Row(
                modifier = Modifier.clickable { onOpenDatePicker() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Calendar,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = selectedDate.format(formatter),
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (!isToday) {
                FilledTonalButton(
                    onClick = onToday,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(stringResource(R.string.sun_ar_today), style = MaterialTheme.typography.labelSmall)
                }
            }

            IconButton(
                onClick = onNextDay,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Forward,
                    contentDescription = stringResource(R.string.sun_ar_next_day),
                    tint = textColor,
                )
            }
        }

        if (onOpenPlaces != null) {
            PlacesButton(
                place = place,
                onClick = onOpenPlaces,
                accent = accentColor,
                idleTint = accentColor,
                // The pill: a 38dp button plus 4dp padding above and below.
                size = 46.dp,
                containerColor = containerBg,
                borderColor = containerBorder,
            )
        }
    }
}

/**
 * Sun AR's settings, the content of its bottom sheet: the day's times, the terrain layer with the
 * height above terrain, the alignment and the display. Built from the same parts as Night Sky's sheet
 * ([LayerGroupCard] and its rows), so the two screens' menus look and work alike. The sheet scrolls;
 * a card anchored to the bottom of the screen used to grow past its top.
 */
@Composable
internal fun SunArSettingsSheet(
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    keepScreenOn: Boolean,
    isCalibrated: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
    terrainTimes: TerrainTimes = TerrainTimes(),
    horizonEnabled: Boolean = false,
    onToggleHorizon: () -> Unit = {},
    peakNamesOn: Boolean = true,
    onTogglePeakNames: () -> Unit = {},
    /** The height the sheet shows; committed by the screen when the sheet closes. */
    heightAboveTerrainCm: Int = HorizonSettings.DEFAULT_EYE_HEIGHT_CM,
    onHeightAboveTerrainChange: (Int) -> Unit = {},
    alignAzimuthOffsetDeg: Float = 0f,
    alignElevationOffsetDeg: Float = 0f,
    compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    onNudgeAlignment: (Float, Float) -> Unit = { _, _ -> },
    isAutoAligning: Boolean = false,
    onAutoAlign: () -> Unit = {},
    /** False under a virtual place: no real skyline in view, so every alignment control is disabled. */
    alignmentAvailable: Boolean = true,
) {
    val dayTimes = solarTrajectory.dayTimes
    val lTimes = lunarTrajectory.dayTimes
    val titleColor = Color.White
    val subColor = Color.White.copy(alpha = 0.6f)
    val groupTitleColor = NormalGoldSolar
    val cardContainerColor = Color(0x14FFFFFF)
    val cardBorderColor = Color(0x22FFFFFF)
    val dividerColor = Color(0x1AFFFFFF)
    val switchColors = SwitchDefaults.colors()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.sun_ar_title),
            color = titleColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // 1. Sun
        LayerGroupCard(
            title = "☀️ ${stringResource(R.string.sun_ar_section_sun)}",
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    InfoItem(
                        label = stringResource(R.string.sun_ar_sunrise),
                        value = solarTrajectory.sunriseMarker?.formattedTime ?: "—",
                        sub = solarTrajectory.sunriseMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${cardinalName(it.coordinates.cardinalDirection())}" } ?: "",
                        color = NormalSunrise,
                        subColor = subColor,
                    )
                    InfoItem(
                        label = stringResource(R.string.sun_ar_solar_noon),
                        value = solarTrajectory.solarNoonMarker.formattedTime,
                        sub = "${dayTimes.maxElevationDegrees.toInt()}° max",
                        color = NormalGoldSolar,
                        subColor = subColor,
                    )
                    InfoItem(
                        label = stringResource(R.string.sun_ar_sunset),
                        value = solarTrajectory.sunsetMarker?.formattedTime ?: "—",
                        sub = solarTrajectory.sunsetMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${cardinalName(it.coordinates.cardinalDirection())}" } ?: "",
                        color = NormalSunset,
                        subColor = subColor,
                    )
                }
                val hours = dayTimes.dayLengthSeconds / 3600
                val minutes = (dayTimes.dayLengthSeconds % 3600) / 60
                Text(
                    text = "${stringResource(R.string.sun_ar_day_length)}: $hours h $minutes min",
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 2. Moon
        LayerGroupCard(
            title = "🌙 ${stringResource(R.string.sun_ar_section_moon)} (${lTimes.phaseGlyph} ${lTimes.illuminationPercent}% ${stringResource(lTimes.phase.nameRes())})",
            titleColor = MoonSilverCyan,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                InfoItem(
                    label = stringResource(R.string.moon_ar_moonrise),
                    value = lunarTrajectory.moonriseMarker?.formattedTime ?: "—",
                    sub = lunarTrajectory.moonriseMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${cardinalName(it.coordinates.cardinalDirection())}" } ?: "",
                    color = MoonRiseColor,
                    subColor = subColor,
                )
                InfoItem(
                    label = stringResource(R.string.moon_ar_transit),
                    value = lunarTrajectory.moonTransitMarker?.formattedTime ?: "—",
                    sub = "${lTimes.maxElevationDegrees.toInt()}° max",
                    color = MoonSilverCyan,
                    subColor = subColor,
                )
                InfoItem(
                    label = stringResource(R.string.moon_ar_moonset),
                    value = lunarTrajectory.moonsetMarker?.formattedTime ?: "—",
                    sub = lunarTrajectory.moonsetMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${cardinalName(it.coordinates.cardinalDirection())}" } ?: "",
                    color = MoonSetColor,
                    subColor = subColor,
                )
            }
        }

        // 3. Terrain horizon. The times stand beside the almanac's, never instead of them: both are
        // true, and which one matters depends on whether you are shooting the sky or the ridge.
        // The search radius and the download are shared with Night Sky and live in Settings; the
        // height above terrain is here because it describes where the user is standing right now.
        LayerGroupCard(
            title = "⛰ ${stringResource(R.string.horizon_section_title)}",
            titleColor = NormalTerrain,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            if (terrainTimes.hasAny) {
                TerrainTimesRow(
                    terrainTimes = terrainTimes,
                    subColor = subColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            }
            LayerToggleRow(
                label = stringResource(R.string.horizon_enabled),
                supportingText = stringResource(R.string.horizon_enabled_desc),
                icon = AppIcons.TerrainHorizon,
                isChecked = horizonEnabled,
                headlineColor = titleColor,
                iconColor = NormalTerrain,
                switchColors = switchColors,
                onCheckedChange = { onToggleHorizon() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            // Peak names stand on the terrain skyline, so the switch works only while that is drawn.
            LayerToggleRow(
                label = stringResource(R.string.horizon_peak_names),
                supportingText = stringResource(R.string.horizon_peak_names_desc),
                icon = AppIcons.Landscape,
                isChecked = peakNamesOn && horizonEnabled,
                headlineColor = titleColor,
                iconColor = NormalTerrain,
                switchColors = switchColors,
                enabled = horizonEnabled,
                onCheckedChange = { onTogglePeakNames() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            HeightAboveTerrainRow(
                heightCm = heightAboveTerrainCm,
                onChange = onHeightAboveTerrainChange,
                headlineColor = titleColor,
                iconColor = NormalTerrain,
            )
        }

        // 4. Alignment
        LayerGroupCard(
            title = stringResource(R.string.ar_align_title),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                HorizonAlignRow(
                    azimuthOffsetDeg = alignAzimuthOffsetDeg,
                    elevationOffsetDeg = alignElevationOffsetDeg,
                    compassAccuracy = compassAccuracy,
                    isCalibrated = isCalibrated,
                    isAutoAligning = isAutoAligning,
                    onNudge = onNudgeAlignment,
                    onAutoAlign = onAutoAlign,
                    onReset = onResetCalibration,
                    titleColor = titleColor,
                    subColor = subColor,
                    enabled = alignmentAvailable,
                )
            }
        }

        // 5. Device
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_tracking),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            LayerToggleRow(
                label = stringResource(R.string.sun_ar_keep_screen_on),
                supportingText = stringResource(R.string.sun_ar_keep_screen_on_desc),
                icon = AppIcons.ScreenAwake,
                isChecked = keepScreenOn,
                headlineColor = titleColor,
                iconColor = groupTitleColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleKeepScreenOn() },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Walks the whole overlay onto the real skyline and keeps the correction.
 *
 * A phone compass is routinely a few degrees out — near a building or a car, more — and the screenshot
 * that prompted this was 2.9° off with the silhouette sitting 1.3° low. No model removes that, so the
 * user gets to nudge it by eye: line the green terrain line up with the ridge in the viewfinder and
 * the offset is remembered for next time. The sensors keep tracking throughout.
 */
@Composable
private fun HorizonAlignRow(
    azimuthOffsetDeg: Float,
    elevationOffsetDeg: Float,
    compassAccuracy: Int,
    isCalibrated: Boolean,
    isAutoAligning: Boolean,
    onNudge: (Float, Float) -> Unit,
    onAutoAlign: () -> Unit,
    onReset: () -> Unit,
    titleColor: Color,
    subColor: Color,
    enabled: Boolean = true,
) {
    // Show the azimuth the short way round: +359° is a tenth of a degree left, not almost a full turn.
    val signedAzimuth = if (azimuthOffsetDeg > 180f) azimuthOffsetDeg - 360f else azimuthOffsetDeg
    val coarse = SunArViewModel.COARSE_NUDGE_DEGREES
    val fine = SunArViewModel.FINE_NUDGE_DEGREES

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ar_align_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.ar_align_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
                if (!enabled) {
                    // Disabled, not hidden: the stored correction stays visible, and the reason is said.
                    Text(
                        text = stringResource(R.string.ar_align_disabled_virtual),
                        style = MaterialTheme.typography.bodySmall,
                        color = NormalGoldSolar,
                    )
                }
            }
            if (isCalibrated) {
                TextButton(onClick = onReset, enabled = enabled) {
                    Text(
                        text = stringResource(R.string.sun_ar_calib_reset),
                        color = if (enabled) NormalGoldSolar else NormalGoldSolar.copy(alpha = 0.38f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        AlignNudgeRow(
            label = stringResource(R.string.ar_align_azimuth),
            valueText = formatOffsetDegrees(signedAzimuth),
            decreaseDescription = stringResource(R.string.ar_align_azimuth_left),
            increaseDescription = stringResource(R.string.ar_align_azimuth_right),
            onCoarseDecrease = { onNudge(-coarse, 0f) },
            onFineDecrease = { onNudge(-fine, 0f) },
            onFineIncrease = { onNudge(fine, 0f) },
            onCoarseIncrease = { onNudge(coarse, 0f) },
            subColor = subColor,
            enabled = enabled,
        )

        AlignNudgeRow(
            label = stringResource(R.string.ar_align_elevation),
            valueText = formatOffsetDegrees(elevationOffsetDeg),
            decreaseDescription = stringResource(R.string.ar_align_elevation_down),
            increaseDescription = stringResource(R.string.ar_align_elevation_up),
            onCoarseDecrease = { onNudge(0f, -coarse) },
            onFineDecrease = { onNudge(0f, -fine) },
            onFineIncrease = { onNudge(0f, fine) },
            onCoarseIncrease = { onNudge(0f, coarse) },
            subColor = subColor,
            enabled = enabled,
        )

        // One press does what the nudges do by hand: find the sky/terrain boundary in the camera
        // image and put the overlay on it. It refuses rather than guesses, and says which.
        Button(
            onClick = onAutoAlign,
            enabled = enabled && !isAutoAligning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isAutoAligning) {
                    stringResource(R.string.ar_align_auto_working)
                } else {
                    stringResource(R.string.ar_align_auto)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // The compass knowing it is lost is worth saying: it explains the misalignment and the fix
        // (a figure of eight) is not something a user would guess.
        if (compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            Text(
                text = stringResource(R.string.ar_align_compass_low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One labelled ±row of the alignment control: coarse and fine steps either side of the readout. */
@Composable
private fun AlignNudgeRow(
    label: String,
    valueText: String,
    decreaseDescription: String,
    increaseDescription: String,
    onCoarseDecrease: () -> Unit,
    onFineDecrease: () -> Unit,
    onFineIncrease: () -> Unit,
    onCoarseIncrease: () -> Unit,
    subColor: Color,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = subColor,
            modifier = Modifier.weight(1f),
        )
        NudgeButton("⏪", decreaseDescription, onCoarseDecrease, enabled)
        NudgeButton("◀", decreaseDescription, onFineDecrease, enabled)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = NormalGoldSolar,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        NudgeButton("▶", increaseDescription, onFineIncrease, enabled)
        NudgeButton("⏩", increaseDescription, onCoarseIncrease, enabled)
    }
}

@Composable
private fun NudgeButton(glyph: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) NormalGoldSolar else NormalGoldSolar.copy(alpha = 0.38f),
        )
    }
}

/**
 * When the Sun really clears and really loses the terrain, next to the almanac times above.
 *
 * The sub-line is the difference, because that is the number a person actually plans around: not
 * "18:42" on its own, but "25 minutes earlier than the app used to tell you".
 */
@Composable
private fun TerrainTimesRow(terrainTimes: TerrainTimes, subColor: Color, modifier: Modifier = Modifier) {
    val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (terrainTimes.hiddenAllDay) {
            Text(
                text = stringResource(R.string.horizon_hidden_all_day),
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            InfoItem(
                label = stringResource(R.string.horizon_terrain_sunrise),
                value = terrainTimes.sunrise?.localTime?.format(formatter) ?: "—",
                sub = terrainTimes.sunrise?.let { formatTerrainDelta(it) } ?: "",
                color = NormalTerrain,
                subColor = subColor,
            )
            InfoItem(
                label = stringResource(R.string.horizon_terrain_sunset),
                value = terrainTimes.sunset?.localTime?.format(formatter) ?: "—",
                sub = terrainTimes.sunset?.let { formatTerrainDelta(it) } ?: "",
                color = NormalTerrain,
                subColor = subColor,
            )
        }
    }
}

/** "25 min earlier" / "12 min later", or blank when there is no almanac time to compare against. */
@Composable
private fun formatTerrainDelta(event: com.dazix.caelis.domain.solar.TerrainEvent): String {
    val delta = event.delayFromAstronomical ?: return ""
    val minutes = delta.toMinutes()
    return when {
        minutes < 0L -> stringResource(R.string.horizon_terrain_earlier, -minutes)
        minutes > 0L -> stringResource(R.string.horizon_terrain_later, minutes)
        else -> ""
    }
}

@Composable
private fun InfoItem(label: String, value: String, sub: String, color: Color, subColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = subColor)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) {
            Text(text = sub, style = MaterialTheme.typography.labelSmall, color = subColor)
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = AppIcons.SunAndMoon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.sun_ar_camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.Unspecified,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sun_ar_camera_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(stringResource(R.string.sun_ar_camera_grant))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

/**
 * Shown while Sun AR is still aligning itself to the skyline. It says what helps (a ridge in view,
 * the phone held still) in words, not only by being there.
 */
@Composable
private fun SkylineSearchChip(modifier: Modifier = Modifier) {
    val text = stringResource(R.string.ar_align_auto_searching)
    Row(
        modifier = modifier
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.TerrainHorizon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

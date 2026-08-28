package com.dazix.caelis.ui.solar

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.caelis.R
import com.dazix.caelis.ar.CameraFovCalculator
import com.dazix.caelis.ar.CameraPreview
import com.dazix.caelis.ar.CameraProjection
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.ProjectedPoint
import com.dazix.caelis.ar.SkyDomeRenderer
import com.dazix.caelis.domain.solar.BubbleInterval
import com.dazix.caelis.domain.solar.LunarCoordinates
import com.dazix.caelis.domain.solar.LunarMarkerPoint
import com.dazix.caelis.domain.solar.LunarTimeBubble
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.SolarMarkerPoint
import com.dazix.caelis.domain.solar.SolarTimeBubble
import com.dazix.caelis.domain.solar.SolarTrajectory
import com.dazix.caelis.service.DisplayGeometry
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.Permissions
import com.dazix.caelis.ui.SegmentedChoice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// High-Contrast Solar Color Palette
private val NormalGoldSolar = Color(0xFFFFB300)
private val NormalSunrise = Color(0xFFFF7043)
private val NormalSunset = Color(0xFFE64A19)
private val NormalHorizon = Color(0xDD4DD0E1)
private val NormalGrid = Color(0x664DD0E1)
private val NormalNightArc = Color(0x44FFB300)

// Cool Lunar Color Palette
private val MoonSilverCyan = Color(0xFF80DEEA)
private val MoonNightArc = Color(0x3380DEEA)
private val MoonRiseColor = Color(0xFF4FC3F7)
private val MoonSetColor = Color(0xFF26A69A)
private val MoonDiskColor = Color(0xFFE0F7FA)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SunArScreen(
    onBack: () -> Unit,
    viewModel: SunArViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val toastHostState = remember(coroutineScope) { AppToastHostState(coroutineScope) }

    var hasCameraPermission by remember {
        mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA))
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

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

    val displayRotation = DisplayGeometry.currentRotation(context)
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 ||
        displayRotation == android.view.Surface.ROTATION_180

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures(
                    panZoomLock = false,
                    onGesture = { _, pan, zoom, _ ->
                        val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                        if (pan.x != 0f || pan.y != 0f) {
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        viewModel.resetZoom()
                    }
                )
            }
    ) {
        if (hasCameraPermission) {
            // Camera Live Viewport with Dynamic FOV extraction
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                lifecycleOwner = lifecycleOwner,
                isPortrait = isPortrait,
                onFovCalculated = viewModel::setCameraFov,
            )

            // 3D Sky Dome AR Overlay Canvas
            SkyDomeOverlayCanvas(
                orientation = orientation,
                displayRotation = displayRotation,
                fovHorizontalDegrees = uiState.cameraFovHorizontal,
                solarTrajectory = uiState.trajectory,
                lunarTrajectory = uiState.lunarTrajectory,
                currentSunPos = uiState.currentSunPosition,
                currentMoonPos = uiState.currentMoonPosition,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Camera Permission Needed Surface
            CameraPermissionPrompt(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top App Bar Controls with HUD
        SunArTopBar(
            hasGpsFix = uiState.hasGpsFix,
            locationText = uiState.location.formatShort(),
            orientation = orientation,
            cameraFovHorizontal = uiState.cameraFovHorizontal,
            zoomRatio = uiState.zoomRatio,
            isCalibrated = uiState.isCalibrated,
            onBack = onBack,
            onToggleInfo = { showInfoSheet = !showInfoSheet },
            onResetCalibration = {
                toastHostState.showToast(
                    resId = R.string.calib_reset_toast,
                    icon = AppIcons.Compass,
                )
                viewModel.resetCalibration()
            },
            onResetZoom = {
                viewModel.resetZoom()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 36.dp, start = 12.dp, end = 12.dp),
        )

        // Bottom Controls Layer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Expandable Day Summary Info Card
            AnimatedVisibility(
                visible = showInfoSheet,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                SunMoonTimesInfoCard(
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
                    onClose = { showInfoSheet = false },
                )
            }

            // Date Navigator Bar
            DateNavigatorBar(
                selectedDate = uiState.selectedDate,
                isToday = uiState.isToday,
                onPrevDay = viewModel::prevDay,
                onNextDay = viewModel::nextDay,
                onToday = viewModel::setToday,
                onOpenDatePicker = { showDatePicker = true },
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
private fun SkyDomeOverlayCanvas(
    orientation: DeviceOrientation?,
    displayRotation: Int,
    fovHorizontalDegrees: Float,
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    currentSunPos: com.dazix.caelis.domain.solar.SolarCoordinates?,
    currentMoonPos: com.dazix.caelis.domain.solar.LunarCoordinates?,
    modifier: Modifier = Modifier,
) {
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

    val renderer = remember { SkyDomeRenderer() }
    renderer.updateLabels(zenithLabel, nadirLabel, cardinalLabels)
    renderer.applyTheme(redVisionMode = false)

    Canvas(modifier = modifier) {
        if (orientation == null) return@Canvas

        val w = size.width
        val h = size.height
        val matrix = orientation.rotationMatrix

        renderer.horizonTextPaint.textSize = 5.3.sp.toPx()
        renderer.gridTextPaint.textSize = 4.5.sp.toPx()
        renderer.bubbleTextPaint.textSize = 11.sp.toPx()
        renderer.bubbleBorderPaint.strokeWidth = 1.2.dp.toPx()
        renderer.markerTextPaint.textSize = 12.sp.toPx()
        renderer.markerBorderPaint.strokeWidth = 1.5.dp.toPx()
        renderer.pointerTextPaint.textSize = 11.sp.toPx()
        renderer.pointerBorderPaint.strokeWidth = 1.2.dp.toPx()

        // 1. Draw Horizon Ring (0° elevation), Cardinal Points, Altitude Grid (+30°, +60°), Zenith and Nadir
        renderer.drawSkyDomeGrid(this, matrix, displayRotation, fovHorizontalDegrees, redVisionMode = false, w, h)

        // 2. Draw Solar Trajectory Path & Time Bubbles (Warm Gold)
        drawSolarTrajectoryArc(renderer, matrix, displayRotation, fovHorizontalDegrees, solarTrajectory, w, h)
        drawSolarTimeBubbles(renderer, solarTrajectory.timeBubbles, matrix, displayRotation, fovHorizontalDegrees, w, h)

        // 3. Draw Lunar Trajectory Path & Time Bubbles (Cool Silver-Cyan)
        drawLunarTrajectoryArc(renderer, matrix, displayRotation, fovHorizontalDegrees, lunarTrajectory, w, h)
        drawLunarTimeBubbles(renderer, lunarTrajectory.timeBubbles, matrix, displayRotation, fovHorizontalDegrees, w, h)

        // 4. Draw Sunrise and Sunset Horizon Badges
        solarTrajectory.sunriseMarker?.let { drawMarker(renderer, it, NormalSunrise, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌅") }
        solarTrajectory.sunsetMarker?.let { drawMarker(renderer, it, NormalSunset, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌇") }

        // 5. Draw Moonrise and Moonset Horizon Badges
        lunarTrajectory.moonriseMarker?.let { drawLunarMarker(renderer, it, MoonRiseColor, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌙") }
        lunarTrajectory.moonsetMarker?.let { drawLunarMarker(renderer, it, MoonSetColor, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌘") }

        // 6. Draw Live Sun or Off-Screen Sun Icon with Pointer Arrow
        if (currentSunPos != null) {
            val sunInFront = CameraProjection.projectFast(
                azimuthDegrees = currentSunPos.azimuthDegrees,
                elevationDegrees = currentSunPos.apparentElevationDegrees,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovHorizontalDegrees,
                outBuffer = renderer.tempPoint,
            )
            val sx = renderer.tempPoint[0]
            val sy = renderer.tempPoint[1]
            val isVisible = sunInFront && (sx in 0f..w) && (sy in 0f..h)

            if (isVisible) {
                drawMinimalistLiveSun(sx, sy)
            } else {
                drawOffscreenSunPointer(
                    renderer = renderer,
                    sunAzimuth = currentSunPos.azimuthDegrees,
                    sunElevation = currentSunPos.apparentElevationDegrees,
                    rawElevation = currentSunPos.elevationDegrees,
                    matrix = matrix,
                    displayRotation = displayRotation,
                    w = w,
                    h = h,
                )
            }
        }

        // 7. Draw Live Moon or Off-Screen Moon Icon with Pointer Arrow
        if (currentMoonPos != null) {
            val moonInFront = CameraProjection.projectFast(
                azimuthDegrees = currentMoonPos.azimuthDegrees,
                elevationDegrees = currentMoonPos.apparentElevationDegrees,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovHorizontalDegrees,
                outBuffer = renderer.tempPoint,
            )
            val mx = renderer.tempPoint[0]
            val my = renderer.tempPoint[1]
            val isVisible = moonInFront && (mx in 0f..w) && (my in 0f..h)

            if (isVisible) {
                drawMinimalistLiveMoon(mx, my, currentMoonPos.phaseGlyph)
            } else {
                drawOffscreenMoonPointer(
                    renderer = renderer,
                    moonAzimuth = currentMoonPos.azimuthDegrees,
                    moonElevation = currentMoonPos.apparentElevationDegrees,
                    rawElevation = currentMoonPos.elevationDegrees,
                    phaseGlyph = currentMoonPos.phaseGlyph,
                    matrix = matrix,
                    displayRotation = displayRotation,
                    w = w,
                    h = h,
                )
            }
        }
    }
}

private fun DrawScope.drawSolarTrajectoryArc(
    renderer: SkyDomeRenderer,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    trajectory: SolarTrajectory,
    w: Float,
    h: Float,
) {
    val solarColor = NormalGoldSolar
    val pts = trajectory.pathPoints
    val count = minOf(pts.size, renderer.pathBuffer.size / 3)

    for (i in 0 until count) {
        val pt = pts[i]
        CameraProjection.projectFast(
            azimuthDegrees = pt.coordinates.azimuthDegrees,
            elevationDegrees = pt.coordinates.apparentElevationDegrees,
            rotationMatrix = matrix,
            viewportWidth = w,
            viewportHeight = h,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovH,
            outBuffer = renderer.pathBuffer,
            offset = i * 3,
        )
    }

    for (i in 0 until count - 1) {
        val off1 = i * 3
        val off2 = (i + 1) * 3
        val z1 = renderer.pathBuffer[off1 + 2]
        val z2 = renderer.pathBuffer[off2 + 2]

        if (z1 > 0.001f && z2 > 0.001f) {
            val x1 = renderer.pathBuffer[off1]
            val y1 = renderer.pathBuffer[off1 + 1]
            val x2 = renderer.pathBuffer[off2]
            val y2 = renderer.pathBuffer[off2 + 1]

            val pt1 = pts[i]
            val pt2 = pts[i + 1]

            val isVisibleArc = pt1.coordinates.apparentElevationDegrees >= -15.0 ||
                pt2.coordinates.apparentElevationDegrees >= -15.0

            if (isVisibleArc) {
                drawLine(
                    color = Color(0x99000000),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.2.dp.toPx(),
                )
                drawLine(
                    color = solarColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2.0.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawSolarTimeBubbles(
    renderer: SkyDomeRenderer,
    bubbles: List<SolarTimeBubble>,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    w: Float,
    h: Float,
) {
    bubbles.forEach { bubble ->
        if (bubble.coordinates.apparentElevationDegrees >= -15.0) {
            val inFront = CameraProjection.projectFast(
                azimuthDegrees = bubble.coordinates.azimuthDegrees,
                elevationDegrees = bubble.coordinates.apparentElevationDegrees,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovH,
                outBuffer = renderer.tempPoint,
            )

            val px = renderer.tempPoint[0]
            val py = renderer.tempPoint[1]
            val margin = 60f
            val isVisible = inFront && (px in -margin..(w + margin)) && (py in -margin..(h + margin))

            if (isVisible) {
                val pw = 46.dp.toPx()
                val ph = 21.dp.toPx()
                val radius = ph * 0.5f

                val left = px - pw * 0.5f
                val top = py - ph * 0.5f
                renderer.tempRect.set(left, top, left + pw, top + ph)

                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBgPaint
                )
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBorderPaint
                )
                val textY = py + (renderer.bubbleTextPaint.textSize * 0.35f)
                drawContext.canvas.nativeCanvas.drawText(
                    bubble.formattedTime, px, textY, renderer.bubbleTextPaint
                )
            }
        }
    }
}

private fun DrawScope.drawLunarTrajectoryArc(
    renderer: SkyDomeRenderer,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    trajectory: LunarTrajectory,
    w: Float,
    h: Float,
) {
    val lunarColor = MoonSilverCyan
    val pts = trajectory.pathPoints
    val count = minOf(pts.size, renderer.pathBuffer.size / 3)

    for (i in 0 until count) {
        val pt = pts[i]
        CameraProjection.projectFast(
            azimuthDegrees = pt.coordinates.azimuthDegrees,
            elevationDegrees = pt.coordinates.apparentElevationDegrees,
            rotationMatrix = matrix,
            viewportWidth = w,
            viewportHeight = h,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovH,
            outBuffer = renderer.pathBuffer,
            offset = i * 3,
        )
    }

    for (i in 0 until count - 1) {
        val off1 = i * 3
        val off2 = (i + 1) * 3
        val z1 = renderer.pathBuffer[off1 + 2]
        val z2 = renderer.pathBuffer[off2 + 2]

        if (z1 > 0.001f && z2 > 0.001f) {
            val x1 = renderer.pathBuffer[off1]
            val y1 = renderer.pathBuffer[off1 + 1]
            val x2 = renderer.pathBuffer[off2]
            val y2 = renderer.pathBuffer[off2 + 1]

            val pt1 = pts[i]
            val pt2 = pts[i + 1]

            val isVisibleArc = pt1.coordinates.apparentElevationDegrees >= -15.0 ||
                pt2.coordinates.apparentElevationDegrees >= -15.0

            if (isVisibleArc) {
                drawLine(
                    color = Color(0x99000000),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.2.dp.toPx(),
                )
                drawLine(
                    color = lunarColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2.0.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawLunarTimeBubbles(
    renderer: SkyDomeRenderer,
    bubbles: List<LunarTimeBubble>,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    w: Float,
    h: Float,
) {
    bubbles.forEach { bubble ->
        if (bubble.coordinates.apparentElevationDegrees >= -15.0) {
            val inFront = CameraProjection.projectFast(
                azimuthDegrees = bubble.coordinates.azimuthDegrees,
                elevationDegrees = bubble.coordinates.apparentElevationDegrees,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovH,
                outBuffer = renderer.tempPoint,
            )

            val px = renderer.tempPoint[0]
            val py = renderer.tempPoint[1]
            val margin = 60f
            val isVisible = inFront && (px in -margin..(w + margin)) && (py in -margin..(h + margin))

            if (isVisible) {
                val pw = 46.dp.toPx()
                val ph = 21.dp.toPx()
                val radius = ph * 0.5f

                val left = px - pw * 0.5f
                val top = py - ph * 0.5f
                renderer.tempRect.set(left, top, left + pw, top + ph)

                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBgPaint
                )
                val origColor = renderer.bubbleBorderPaint.color
                renderer.bubbleBorderPaint.color = android.graphics.Color.argb(180, 128, 222, 234)
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBorderPaint
                )
                renderer.bubbleBorderPaint.color = origColor

                val textY = py + (renderer.bubbleTextPaint.textSize * 0.35f)
                drawContext.canvas.nativeCanvas.drawText(
                    bubble.formattedTime, px, textY, renderer.bubbleTextPaint
                )
            }
        }
    }
}

private fun DrawScope.drawMarker(
    renderer: SkyDomeRenderer,
    marker: SolarMarkerPoint,
    accentColor: Color,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    w: Float,
    h: Float,
    emoji: String,
) {
    val inFront = CameraProjection.projectFast(
        azimuthDegrees = marker.coordinates.azimuthDegrees,
        elevationDegrees = marker.coordinates.apparentElevationDegrees,
        rotationMatrix = matrix,
        viewportWidth = w,
        viewportHeight = h,
        displayRotation = displayRotation,
        fovHorizontalDegrees = fovH,
        outBuffer = renderer.tempPoint,
    )

    val px = renderer.tempPoint[0]
    val py = renderer.tempPoint[1]
    val margin = 80f
    val isVisible = inFront && (px in -margin..(w + margin)) && (py in -margin..(h + margin))

    if (isVisible) {
        val label = "$emoji ${marker.formattedTime} (${marker.coordinates.azimuthDegrees.toInt()}°)"
        val textW = renderer.markerTextPaint.measureText(label) + 16.dp.toPx()
        val ph = 26.dp.toPx()
        val radius = ph * 0.5f

        val left = px - textW * 0.5f
        val top = py - ph * 0.5f - 16.dp.toPx()
        renderer.tempRect.set(left, top, left + textW, top + ph)

        renderer.markerBorderPaint.color = android.graphics.Color.argb(
            (accentColor.alpha * 255).toInt(),
            (accentColor.red * 255).toInt(),
            (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(),
        )

        drawCircle(
            color = accentColor,
            radius = 4.dp.toPx(),
            center = Offset(px, py),
        )

        drawContext.canvas.nativeCanvas.drawRoundRect(
            renderer.tempRect, radius, radius, renderer.markerBgPaint
        )
        drawContext.canvas.nativeCanvas.drawRoundRect(
            renderer.tempRect, radius, radius, renderer.markerBorderPaint
        )

        val textY = top + ph * 0.5f + (renderer.markerTextPaint.textSize * 0.35f)
        drawContext.canvas.nativeCanvas.drawText(label, px, textY, renderer.markerTextPaint)
    }
}

private fun DrawScope.drawLunarMarker(
    renderer: SkyDomeRenderer,
    marker: LunarMarkerPoint,
    accentColor: Color,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    w: Float,
    h: Float,
    emoji: String,
) {
    val inFront = CameraProjection.projectFast(
        azimuthDegrees = marker.coordinates.azimuthDegrees,
        elevationDegrees = marker.coordinates.apparentElevationDegrees,
        rotationMatrix = matrix,
        viewportWidth = w,
        viewportHeight = h,
        displayRotation = displayRotation,
        fovHorizontalDegrees = fovH,
        outBuffer = renderer.tempPoint,
    )

    val px = renderer.tempPoint[0]
    val py = renderer.tempPoint[1]
    val margin = 80f
    val isVisible = inFront && (px in -margin..(w + margin)) && (py in -margin..(h + margin))

    if (isVisible) {
        val label = "$emoji ${marker.formattedTime} (${marker.coordinates.azimuthDegrees.toInt()}°)"
        val textW = renderer.markerTextPaint.measureText(label) + 16.dp.toPx()
        val ph = 26.dp.toPx()
        val radius = ph * 0.5f

        val left = px - textW * 0.5f
        val top = py - ph * 0.5f - 16.dp.toPx()
        renderer.tempRect.set(left, top, left + textW, top + ph)

        renderer.markerBorderPaint.color = android.graphics.Color.argb(
            (accentColor.alpha * 255).toInt(),
            (accentColor.red * 255).toInt(),
            (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(),
        )

        drawCircle(
            color = accentColor,
            radius = 4.dp.toPx(),
            center = Offset(px, py),
        )

        drawContext.canvas.nativeCanvas.drawRoundRect(
            renderer.tempRect, radius, radius, renderer.markerBgPaint
        )
        drawContext.canvas.nativeCanvas.drawRoundRect(
            renderer.tempRect, radius, radius, renderer.markerBorderPaint
        )

        val textY = top + ph * 0.5f + (renderer.markerTextPaint.textSize * 0.35f)
        drawContext.canvas.nativeCanvas.drawText(label, px, textY, renderer.markerTextPaint)
    }
}

private fun DrawScope.drawMinimalistLiveSun(x: Float, y: Float) {
    val sunColor = NormalGoldSolar
    val coreColor = Color.White

    drawCircle(
        color = sunColor,
        radius = 11.dp.toPx(),
        center = Offset(x, y),
    )
    drawCircle(
        color = coreColor,
        radius = 4.dp.toPx(),
        center = Offset(x, y),
    )
    drawCircle(
        color = Color(0x99000000),
        radius = 11.5.dp.toPx(),
        center = Offset(x, y),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
    )
}

private fun DrawScope.drawMinimalistLiveMoon(x: Float, y: Float, phaseGlyph: String) {
    val moonBorder = MoonSilverCyan
    val moonSurface = MoonDiskColor
    val coreColor = Color.White

    drawCircle(
        color = moonBorder,
        radius = 10.5.dp.toPx(),
        center = Offset(x, y),
    )
    drawCircle(
        color = moonSurface,
        radius = 7.5.dp.toPx(),
        center = Offset(x, y),
    )
    drawCircle(
        color = coreColor,
        radius = 3.5.dp.toPx(),
        center = Offset(x, y),
    )
    drawCircle(
        color = Color(0x99000000),
        radius = 11.0.dp.toPx(),
        center = Offset(x, y),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
    )
}

private fun DrawScope.drawOffscreenSunPointer(
    renderer: SkyDomeRenderer,
    sunAzimuth: Double,
    sunElevation: Double,
    rawElevation: Double,
    matrix: FloatArray,
    displayRotation: Int,
    w: Float,
    h: Float,
) {
    val azRad = Math.toRadians(sunAzimuth)
    val elRad = Math.toRadians(sunElevation)
    val ux = (sin(azRad) * cos(elRad)).toFloat()
    val uy = (cos(azRad) * cos(elRad)).toFloat()
    val uz = sin(elRad).toFloat()

    val xDev = matrix[0] * ux + matrix[4] * uy + matrix[8] * uz
    val yDev = matrix[1] * ux + matrix[5] * uy + matrix[9] * uz
    val zDev = matrix[2] * ux + matrix[6] * uy + matrix[10] * uz

    val xs: Float
    val ys: Float
    val zs: Float
    when (displayRotation) {
        android.view.Surface.ROTATION_90 -> { xs = yDev; ys = -xDev; zs = -zDev }
        android.view.Surface.ROTATION_180 -> { xs = -xDev; ys = -yDev; zs = -zDev }
        android.view.Surface.ROTATION_270 -> { xs = -yDev; ys = xDev; zs = -zDev }
        else -> { xs = xDev; ys = yDev; zs = -zDev }
    }

    var dx = xs
    var dy = -ys
    if (zs < 0f && abs(dx) < 0.001f && abs(dy) < 0.001f) {
        dy = 1f
    }

    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val dirX = if (len > 0.001f) dx / len else 0f
    val dirY = if (len > 0.001f) dy / len else -1f

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

    val sunColor = NormalGoldSolar
    val coreColor = Color.White

    val sunRadius = 6.5.dp.toPx()
    val rayInner = sunRadius + 2.0.dp.toPx()
    val rayOuter = rayInner + 3.0.dp.toPx()

    drawCircle(
        color = sunColor,
        radius = sunRadius,
        center = Offset(edgeX, edgeY),
    )
    drawCircle(
        color = coreColor,
        radius = 2.5.dp.toPx(),
        center = Offset(edgeX, edgeY),
    )
    drawCircle(
        color = Color(0x99000000),
        radius = sunRadius + 0.5.dp.toPx(),
        center = Offset(edgeX, edgeY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.0.dp.toPx()),
    )

    for (i in 0 until 8) {
        val angle = (i * Math.PI / 4.0).toFloat()
        val cosA = cos(angle)
        val sinA = sin(angle)
        drawLine(
            color = sunColor,
            start = Offset(edgeX + cosA * rayInner, edgeY + sinA * rayInner),
            end = Offset(edgeX + cosA * rayOuter, edgeY + sinA * rayOuter),
            strokeWidth = 1.4.dp.toPx(),
        )
    }

    val arrowDist = 18.dp.toPx()
    val arrowLen = 10.dp.toPx()
    val arrowSpread = 6.dp.toPx()

    val tipX = edgeX + dirX * arrowDist
    val tipY = edgeY + dirY * arrowDist
    val perpX = -dirY
    val perpY = dirX

    val baseLeftX = tipX - dirX * arrowLen + perpX * arrowSpread
    val baseLeftY = tipY - dirY * arrowLen + perpY * arrowSpread
    val baseRightX = tipX - dirX * arrowLen - perpX * arrowSpread
    val baseRightY = tipY - dirY * arrowLen - perpY * arrowSpread

    drawLine(
        color = Color(0x99000000),
        start = Offset(tipX, tipY),
        end = Offset(baseLeftX, baseLeftY),
        strokeWidth = 3.2.dp.toPx(),
    )
    drawLine(
        color = Color(0x99000000),
        start = Offset(tipX, tipY),
        end = Offset(baseRightX, baseRightY),
        strokeWidth = 3.2.dp.toPx(),
    )

    drawLine(
        color = sunColor,
        start = Offset(tipX, tipY),
        end = Offset(baseLeftX, baseLeftY),
        strokeWidth = 2.0.dp.toPx(),
    )
    drawLine(
        color = sunColor,
        start = Offset(tipX, tipY),
        end = Offset(baseRightX, baseRightY),
        strokeWidth = 2.0.dp.toPx(),
    )

    val sign = if (rawElevation >= 0) "+" else ""
    val elevText = "$sign${rawElevation.toInt()}°"
    val textW = renderer.pointerTextPaint.measureText(elevText) + 12.dp.toPx()
    val ph = 18.dp.toPx()
    val radius = ph * 0.5f

    val tagDist = 20.dp.toPx()
    val tagCenterX = edgeX - dirX * tagDist
    val tagCenterY = edgeY - dirY * tagDist

    val left = tagCenterX - textW * 0.5f
    val top = tagCenterY - ph * 0.5f
    renderer.tempRect.set(left, top, left + textW, top + ph)

    drawContext.canvas.nativeCanvas.drawRoundRect(renderer.tempRect, radius, radius, renderer.pointerBgPaint)
    drawContext.canvas.nativeCanvas.drawRoundRect(renderer.tempRect, radius, radius, renderer.pointerBorderPaint)

    val textY = top + ph * 0.5f + (renderer.pointerTextPaint.textSize * 0.35f)
    drawContext.canvas.nativeCanvas.drawText(elevText, tagCenterX, textY, renderer.pointerTextPaint)
}

private fun DrawScope.drawOffscreenMoonPointer(
    renderer: SkyDomeRenderer,
    moonAzimuth: Double,
    moonElevation: Double,
    rawElevation: Double,
    phaseGlyph: String,
    matrix: FloatArray,
    displayRotation: Int,
    w: Float,
    h: Float,
) {
    val azRad = Math.toRadians(moonAzimuth)
    val elRad = Math.toRadians(moonElevation)
    val ux = (sin(azRad) * cos(elRad)).toFloat()
    val uy = (cos(azRad) * cos(elRad)).toFloat()
    val uz = sin(elRad).toFloat()

    val xDev = matrix[0] * ux + matrix[4] * uy + matrix[8] * uz
    val yDev = matrix[1] * ux + matrix[5] * uy + matrix[9] * uz
    val zDev = matrix[2] * ux + matrix[6] * uy + matrix[10] * uz

    val xs: Float
    val ys: Float
    val zs: Float
    when (displayRotation) {
        android.view.Surface.ROTATION_90 -> { xs = yDev; ys = -xDev; zs = -zDev }
        android.view.Surface.ROTATION_180 -> { xs = -xDev; ys = -yDev; zs = -zDev }
        android.view.Surface.ROTATION_270 -> { xs = -yDev; ys = xDev; zs = -zDev }
        else -> { xs = xDev; ys = yDev; zs = -zDev }
    }

    var dx = xs
    var dy = -ys
    if (zs < 0f && abs(dx) < 0.001f && abs(dy) < 0.001f) {
        dy = 1f
    }

    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val dirX = if (len > 0.001f) dx / len else 0f
    val dirY = if (len > 0.001f) dy / len else -1f

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

    val moonColor = MoonSilverCyan
    val moonSurface = MoonDiskColor

    val moonRadius = 6.5.dp.toPx()

    drawCircle(
        color = moonColor,
        radius = moonRadius,
        center = Offset(edgeX, edgeY),
    )
    drawCircle(
        color = moonSurface,
        radius = moonRadius - 1.5.dp.toPx(),
        center = Offset(edgeX, edgeY),
    )
    drawCircle(
        color = Color(0x99000000),
        radius = moonRadius + 0.5.dp.toPx(),
        center = Offset(edgeX, edgeY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.0.dp.toPx()),
    )

    val arrowDist = 18.dp.toPx()
    val arrowLen = 10.dp.toPx()
    val arrowSpread = 6.dp.toPx()

    val tipX = edgeX + dirX * arrowDist
    val tipY = edgeY + dirY * arrowDist
    val perpX = -dirY
    val perpY = dirX

    val baseLeftX = tipX - dirX * arrowLen + perpX * arrowSpread
    val baseLeftY = tipY - dirY * arrowLen + perpY * arrowSpread
    val baseRightX = tipX - dirX * arrowLen - perpX * arrowSpread
    val baseRightY = tipY - dirY * arrowLen - perpY * arrowSpread

    drawLine(
        color = Color(0x99000000),
        start = Offset(tipX, tipY),
        end = Offset(baseLeftX, baseLeftY),
        strokeWidth = 3.2.dp.toPx(),
    )
    drawLine(
        color = Color(0x99000000),
        start = Offset(tipX, tipY),
        end = Offset(baseRightX, baseRightY),
        strokeWidth = 3.2.dp.toPx(),
    )

    drawLine(
        color = moonColor,
        start = Offset(tipX, tipY),
        end = Offset(baseLeftX, baseLeftY),
        strokeWidth = 2.0.dp.toPx(),
    )
    drawLine(
        color = moonColor,
        start = Offset(tipX, tipY),
        end = Offset(baseRightX, baseRightY),
        strokeWidth = 2.0.dp.toPx(),
    )

    val sign = if (rawElevation >= 0) "+" else ""
    val elevText = "$phaseGlyph $sign${rawElevation.toInt()}°"
    val textW = renderer.pointerTextPaint.measureText(elevText) + 12.dp.toPx()
    val ph = 18.dp.toPx()
    val radius = ph * 0.5f

    val tagDist = 20.dp.toPx()
    val tagCenterX = edgeX - dirX * tagDist
    val tagCenterY = edgeY - dirY * tagDist

    val left = tagCenterX - textW * 0.5f
    val top = tagCenterY - ph * 0.5f
    renderer.tempRect.set(left, top, left + textW, top + ph)

    drawContext.canvas.nativeCanvas.drawRoundRect(renderer.tempRect, radius, radius, renderer.pointerBgPaint)
    val origBorder = renderer.pointerBorderPaint.color
    renderer.pointerBorderPaint.color = android.graphics.Color.argb(180, 128, 222, 234)
    drawContext.canvas.nativeCanvas.drawRoundRect(renderer.tempRect, radius, radius, renderer.pointerBorderPaint)
    renderer.pointerBorderPaint.color = origBorder

    val textY = top + ph * 0.5f + (renderer.pointerTextPaint.textSize * 0.35f)
    drawContext.canvas.nativeCanvas.drawText(elevText, tagCenterX, textY, renderer.pointerTextPaint)
}

@Composable
private fun SunArTopBar(
    hasGpsFix: Boolean,
    locationText: String,
    orientation: DeviceOrientation?,
    cameraFovHorizontal: Float,
    zoomRatio: Float,
    isCalibrated: Boolean,
    onBack: () -> Unit,
    onToggleInfo: () -> Unit,
    onResetCalibration: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerBg = Color(0xDD1A1A1A)
    val containerBorder = Color(0x33FFFFFF)
    val accentColor = NormalGoldSolar
    val textColor = Color.White
    val subTextColor = Color(0xCCFFFFFF)

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val az = orientation?.cameraAzimuthDegrees?.toInt() ?: 0
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
                val isZoomed = abs(zoomRatio - 1.0f) > 0.05f
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

        if (isCalibrated) {
            IconButton(
                onClick = onResetCalibration,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Compass,
                    contentDescription = stringResource(R.string.sun_ar_calib_reset),
                    tint = accentColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        IconButton(
            onClick = onToggleInfo,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = AppIcons.Advanced,
                contentDescription = stringResource(R.string.sun_ar_title),
                tint = accentColor,
            )
        }
    }
}

@Composable
private fun DateNavigatorBar(
    selectedDate: LocalDate,
    isToday: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onOpenDatePicker: () -> Unit,
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("EEE d. MMM", Locale.getDefault())
    }

    val containerBg = Color(0xDD1A1A1A)
    val containerBorder = Color(0x33FFFFFF)
    val accentColor = NormalGoldSolar
    val textColor = Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
}

@Composable
private fun SunMoonTimesInfoCard(
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    keepScreenOn: Boolean,
    isCalibrated: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onResetCalibration: () -> Unit,
    onClose: () -> Unit,
) {
    val dayTimes = solarTrajectory.dayTimes
    val lTimes = lunarTrajectory.dayTimes
    val containerBg = Color(0xF2181818)
    val cardBorder = Color(0x33FFFFFF)
    val titleColor = Color.White
    val subColor = Color.White.copy(alpha = 0.6f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sun_ar_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = AppIcons.Clear,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = subColor,
                    )
                }
            }

            // 1. SOLAR SECTION
            Text(
                text = "☀️ Slunce",
                style = MaterialTheme.typography.labelMedium,
                color = NormalGoldSolar,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                InfoItem(
                    label = stringResource(R.string.sun_ar_sunrise),
                    value = solarTrajectory.sunriseMarker?.formattedTime ?: "—",
                    sub = solarTrajectory.sunriseMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
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
                    sub = solarTrajectory.sunsetMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
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

            Spacer(Modifier.height(2.dp))

            // 2. LUNAR SECTION
            Text(
                text = "🌙 Měsíc (${lTimes.phaseGlyph} ${lTimes.illuminationPercent}% ${lTimes.phaseName})",
                style = MaterialTheme.typography.labelMedium,
                color = MoonSilverCyan,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                InfoItem(
                    label = stringResource(R.string.moon_ar_moonrise),
                    value = lunarTrajectory.moonriseMarker?.formattedTime ?: "—",
                    sub = lunarTrajectory.moonriseMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
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
                    sub = lunarTrajectory.moonsetMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
                    color = MoonSetColor,
                    subColor = subColor,
                )
            }

            Spacer(Modifier.height(2.dp))

            // 3. SETTINGS & CONTROLS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleKeepScreenOn() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sun_ar_keep_screen_on),
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.sun_ar_keep_screen_on_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = { onToggleKeepScreenOn() },
                    colors = SwitchDefaults.colors(),
                )
            }

            // Manual 3D rotation calibration row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCalibrated) stringResource(R.string.sun_ar_calib_active) else stringResource(R.string.sun_ar_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.sun_ar_calib_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
                if (isCalibrated) {
                    TextButton(onClick = onResetCalibration) {
                        Text(
                            text = stringResource(R.string.sun_ar_calib_reset),
                            color = NormalGoldSolar,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
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

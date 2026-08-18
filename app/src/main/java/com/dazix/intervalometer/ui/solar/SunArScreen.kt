package com.dazix.intervalometer.ui.solar

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
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
import androidx.compose.runtime.setValue
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
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.CameraFovCalculator
import com.dazix.intervalometer.ar.CameraPreview
import com.dazix.intervalometer.ar.CameraProjection
import com.dazix.intervalometer.ar.DeviceOrientation
import com.dazix.intervalometer.ar.ProjectedPoint
import com.dazix.intervalometer.ar.SkyDomeRenderer
import com.dazix.intervalometer.domain.solar.BubbleInterval
import com.dazix.intervalometer.domain.solar.SolarMarkerPoint
import com.dazix.intervalometer.domain.solar.SolarTimeBubble
import com.dazix.intervalometer.domain.solar.SolarTrajectory
import com.dazix.intervalometer.service.DisplayGeometry
import com.dazix.intervalometer.ui.AppIcons
import com.dazix.intervalometer.ui.Permissions
import com.dazix.intervalometer.ui.SegmentedChoice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Normal Color Palette
private val NormalGoldSolar = Color(0xFFFFB300)
private val NormalSunrise = Color(0xFFFF7043)
private val NormalSunset = Color(0xFFE64A19)
private val NormalHorizon = Color(0xDD4DD0E1)
private val NormalGrid = Color(0x664DD0E1)
private val NormalNightArc = Color(0x44FFB300)

// Red Vision (Astrophotography Night Mode) Palette
private val RedPrimary = Color(0xFFFF1744)
private val RedSecondary = Color(0xFFB71C1C)
private val RedGrid = Color(0x66B71C1C)
private val RedNightArc = Color(0x33FF1744)

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
            .pointerInput(uiState.cameraFovHorizontal, isPortrait) {
                detectHorizontalDragGestures { _, dragAmount ->
                    // Horizontal drag gesture adjusts azimuth calibration offset
                    val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                    val deltaDeg = (dragAmount / viewWidth) * uiState.cameraFovHorizontal
                    viewModel.adjustAzimuthOffset(-deltaDeg)
                }
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
                trajectory = uiState.trajectory,
                currentSunPos = uiState.currentSunPosition,
                redVisionMode = uiState.redVisionMode,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Camera Permission Needed Surface
            CameraPermissionPrompt(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
                redVisionMode = uiState.redVisionMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top App Bar Controls with HUD
        SunArTopBar(
            hasGpsFix = uiState.hasGpsFix,
            locationText = uiState.location.formatShort(),
            orientation = orientation,
            cameraFovHorizontal = uiState.cameraFovHorizontal,
            azimuthOffset = uiState.azimuthOffsetDegrees,
            keepScreenOn = uiState.keepScreenOn,
            redVisionMode = uiState.redVisionMode,
            onBack = onBack,
            onToggleInfo = { showInfoSheet = !showInfoSheet },
            onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
            onToggleRedVision = viewModel::toggleRedVisionMode,
            onResetCalibration = viewModel::resetAzimuthOffset,
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
                SunTimesInfoCard(
                    trajectory = uiState.trajectory,
                    keepScreenOn = uiState.keepScreenOn,
                    redVisionMode = uiState.redVisionMode,
                    azimuthOffset = uiState.azimuthOffsetDegrees,
                    onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                    onToggleRedVision = viewModel::toggleRedVisionMode,
                    onResetCalibration = viewModel::resetAzimuthOffset,
                    onClose = { showInfoSheet = false },
                )
            }

            // Date Navigator Bar
            DateNavigatorBar(
                selectedDate = uiState.selectedDate,
                isToday = uiState.isToday,
                redVisionMode = uiState.redVisionMode,
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
    trajectory: SolarTrajectory,
    currentSunPos: com.dazix.intervalometer.domain.solar.SolarCoordinates?,
    redVisionMode: Boolean,
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
    renderer.applyTheme(redVisionMode)

    Canvas(modifier = modifier) {
        if (orientation == null) return@Canvas

        val w = size.width
        val h = size.height
        val matrix = orientation.rotationMatrix

        renderer.horizonTextPaint.textSize = 12.sp.toPx()
        renderer.gridTextPaint.textSize = 10.sp.toPx()
        renderer.bubbleTextPaint.textSize = 11.sp.toPx()
        renderer.bubbleBorderPaint.strokeWidth = 1.2.dp.toPx()
        renderer.markerTextPaint.textSize = 12.sp.toPx()
        renderer.markerBorderPaint.strokeWidth = 1.5.dp.toPx()
        renderer.pointerTextPaint.textSize = 11.sp.toPx()
        renderer.pointerBorderPaint.strokeWidth = 1.2.dp.toPx()

        // 1. Draw Horizon Ring (0° elevation), Cardinal Points, Altitude Grid (+30°, +60°), Zenith and Nadir
        renderer.drawSkyDomeGrid(this, matrix, displayRotation, fovHorizontalDegrees, redVisionMode, w, h)

        // 2. Draw Solar Trajectory Path across the Dome (Solid down to -15° for real terrain visibility)
        drawSolarTrajectoryArc(renderer, matrix, displayRotation, fovHorizontalDegrees, trajectory, redVisionMode, w, h)

        // 3. Draw Time Bubbles along the Trajectory (Down to -15° for sunset behind mountains)
        drawTimeBubbles(renderer, trajectory.timeBubbles, matrix, displayRotation, fovHorizontalDegrees, w, h)

        // 4. Draw Sunrise and Sunset Horizon Badges
        val sunriseColor = if (redVisionMode) RedPrimary else NormalSunrise
        val sunsetColor = if (redVisionMode) RedPrimary else NormalSunset
        trajectory.sunriseMarker?.let { drawMarker(renderer, it, sunriseColor, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌅") }
        trajectory.sunsetMarker?.let { drawMarker(renderer, it, sunsetColor, matrix, displayRotation, fovHorizontalDegrees, w, h, "🌇") }

        // 5. Draw Live Sun or Off-Screen Sun Icon with Pointer Arrow
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
                drawMinimalistLiveSun(sx, sy, redVisionMode)
            } else {
                // Sun is not in view: draw small Sun Icon with directional Pointer Arrow to its position
                drawOffscreenSunPointer(
                    renderer = renderer,
                    sunAzimuth = currentSunPos.azimuthDegrees,
                    sunElevation = currentSunPos.apparentElevationDegrees,
                    rawElevation = currentSunPos.elevationDegrees,
                    matrix = matrix,
                    displayRotation = displayRotation,
                    redVisionMode = redVisionMode,
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
    redVisionMode: Boolean,
    w: Float,
    h: Float,
) {
    val solarColor = if (redVisionMode) RedPrimary else NormalGoldSolar
    val nightArcColor = if (redVisionMode) RedNightArc else NormalNightArc
    val pts = trajectory.pathPoints
    val count = minOf(pts.size, renderer.pathBuffer.size / 3)

    // Pre-project all path points in a single pass into pre-allocated buffer
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

    // Draw lines between adjacent points when both are in front of camera
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

            // Real terrain continuation: solid curve continues down to -15°
            val isVisibleArc = pt1.coordinates.apparentElevationDegrees >= -15.0 ||
                pt2.coordinates.apparentElevationDegrees >= -15.0

            if (isVisibleArc) {
                // High contrast dark outline
                drawLine(
                    color = Color(0x99000000),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.2.dp.toPx(),
                )
                // Solid trajectory line
                drawLine(
                    color = solarColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1.8.dp.toPx(),
                )
            } else {
                // Subtle deep night trajectory
                drawLine(
                    color = nightArcColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1.0.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawTimeBubbles(
    renderer: SkyDomeRenderer,
    bubbles: List<SolarTimeBubble>,
    matrix: FloatArray,
    displayRotation: Int,
    fovH: Float,
    w: Float,
    h: Float,
) {
    bubbles.forEach { bubble ->
        // Continue time bubbles down to -15° for terrain/mountain sunset planning
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

                // Background pill
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBgPaint
                )
                // Border
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    renderer.tempRect, radius, radius, renderer.bubbleBorderPaint
                )
                // Formatted Time text
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

        // Marker dot on horizon
        drawCircle(
            color = accentColor,
            radius = 4.dp.toPx(),
            center = Offset(px, py),
        )

        // Pill badge
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

private fun DrawScope.drawMinimalistLiveSun(x: Float, y: Float, redVisionMode: Boolean) {
    val sunColor = if (redVisionMode) RedPrimary else NormalGoldSolar
    val coreColor = if (redVisionMode) Color(0xFFFF8A80) else Color.White

    // Strict minimalist disk: clean circle with border, no glowing corona
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

private fun DrawScope.drawOffscreenSunPointer(
    renderer: SkyDomeRenderer,
    sunAzimuth: Double,
    sunElevation: Double,
    rawElevation: Double,
    matrix: FloatArray,
    displayRotation: Int,
    redVisionMode: Boolean,
    w: Float,
    h: Float,
) {
    // 1. Calculate direction vector of Sun in camera screen coordinate space
    val azRad = Math.toRadians(sunAzimuth)
    val elRad = Math.toRadians(sunElevation)
    val ux = (sin(azRad) * cos(elRad)).toFloat()
    val uy = (cos(azRad) * cos(elRad)).toFloat()
    val uz = sin(elRad).toFloat()

    // Transform by rotation matrix (R^T) to get coordinates in phone camera space
    val xDev = matrix[0] * ux + matrix[4] * uy + matrix[8] * uz
    val yDev = matrix[1] * ux + matrix[5] * uy + matrix[9] * uz
    val zDev = matrix[2] * ux + matrix[6] * uy + matrix[10] * uz

    val xs: Float
    val ys: Float
    val zs: Float
    when (displayRotation) {
        android.view.Surface.ROTATION_90 -> {
            xs = yDev
            ys = -xDev
            zs = -zDev
        }
        android.view.Surface.ROTATION_180 -> {
            xs = -xDev
            ys = -yDev
            zs = -zDev
        }
        android.view.Surface.ROTATION_270 -> {
            xs = -yDev
            ys = xDev
            zs = -zDev
        }
        else -> {
            xs = xDev
            ys = yDev
            zs = -zDev
        }
    }

    // Direction on screen: screen right is +xs, screen up is +ys (so dy on screen is -ys)
    var dx = xs
    var dy = -ys
    if (zs < 0f && abs(dx) < 0.001f && abs(dy) < 0.001f) {
        dy = 1f // If directly behind, default pointing down/turn around
    }

    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val dirX = if (len > 0.001f) dx / len else 0f
    val dirY = if (len > 0.001f) dy / len else -1f

    // 2. Compute screen edge clamp position with clean margins
    val marginX = 46.dp.toPx()
    val marginTop = 92.dp.toPx() // Below top bar HUD
    val marginBottom = 150.dp.toPx() // Above bottom date/interval controls

    val cx = w * 0.5f
    val cy = h * 0.5f

    val halfW = cx - marginX
    val halfH = if (dirY < 0f) cy - marginTop else cy - marginBottom

    val tx = if (abs(dirX) > 0.0001f) halfW / abs(dirX) else Float.MAX_VALUE
    val ty = if (abs(dirY) > 0.0001f) halfH / abs(dirY) else Float.MAX_VALUE
    val t = minOf(tx, ty)

    val edgeX = (cx + dirX * t).coerceIn(marginX, w - marginX)
    val edgeY = (cy + dirY * t).coerceIn(marginTop, h - marginBottom)

    val sunColor = if (redVisionMode) RedPrimary else NormalGoldSolar
    val coreColor = if (redVisionMode) Color(0xFFFF8A80) else Color.White

    // 3. Draw Small Sun Icon (Central circle + 8 radiating vector rays)
    val sunRadius = 6.5.dp.toPx()
    val rayInner = sunRadius + 2.0.dp.toPx()
    val rayOuter = rayInner + 3.0.dp.toPx()

    // Sun center circle
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

    // 8 Sun rays
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

    // 4. Draw Directional Pointer Arrow pointing outwards towards the Sun
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

    // Arrow drop shadow outline
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

    // Sharp directional arrow
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

    // 5. Draw Elevation Tag Pill (e.g. +24° or -4°)
    val elevText = "${if (rawElevation >= 0) "+" else ""}${rawElevation.toInt()}°"
    val textW = renderer.pointerTextPaint.measureText(elevText) + 12.dp.toPx()
    val ph = 18.dp.toPx()
    val radius = ph * 0.5f

    // Position tag on inner side opposite to the arrow
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

@Composable
private fun SunArTopBar(
    hasGpsFix: Boolean,
    locationText: String,
    orientation: DeviceOrientation?,
    cameraFovHorizontal: Float,
    azimuthOffset: Float,
    keepScreenOn: Boolean,
    redVisionMode: Boolean,
    onBack: () -> Unit,
    onToggleInfo: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleRedVision: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerBg = if (redVisionMode) Color(0xF0120000) else Color(0xDD1A1A1A)
    val containerBorder = if (redVisionMode) Color(0x66FF1744) else Color(0x33FFFFFF)
    val accentColor = if (redVisionMode) RedPrimary else NormalGoldSolar
    val textColor = if (redVisionMode) RedPrimary else Color.White
    val subTextColor = if (redVisionMode) Color(0xCCFF5252) else Color(0xCCFFFFFF)

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
            // 1. řádek: GPS / Poloha
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (redVisionMode) RedPrimary
                            else if (hasGpsFix) Color(0xFF66BB6A)
                            else Color(0xFFFFCA28),
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

            // 2. řádek: Azimut a FOV
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
                Text(
                    text = "📐 ${stringResource(R.string.sun_ar_fov_label, cameraFovHorizontal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = subTextColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Calibration Offset Badge & Reset Button if calibrated
        if (abs(azimuthOffset) > 0.2f) {
            IconButton(
                onClick = onResetCalibration,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = AppIcons.ResetCalibration,
                    contentDescription = stringResource(R.string.sun_ar_calib_reset),
                    tint = accentColor,
                )
            }
        }

        // Red Vision Mode Toggle
        IconButton(
            onClick = onToggleRedVision,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = AppIcons.RedVision,
                contentDescription = stringResource(R.string.sun_ar_red_vision),
                tint = if (redVisionMode) RedPrimary else Color.White.copy(alpha = 0.4f),
            )
        }

        // Keep Screen On Toggle
        IconButton(
            onClick = onToggleKeepScreenOn,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = AppIcons.ScreenAwake,
                contentDescription = stringResource(R.string.sun_ar_keep_screen_on),
                tint = if (keepScreenOn) accentColor else Color.White.copy(alpha = 0.4f),
            )
        }

        // Info Toggle
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
    redVisionMode: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onOpenDatePicker: () -> Unit,
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("EEE d. MMM", Locale.getDefault())
    }

    val containerBg = if (redVisionMode) Color(0xF0120000) else Color(0xDD1A1A1A)
    val containerBorder = if (redVisionMode) Color(0x66FF1744) else Color(0x33FFFFFF)
    val accentColor = if (redVisionMode) RedPrimary else NormalGoldSolar
    val textColor = if (redVisionMode) RedPrimary else Color.White

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
                colors = if (redVisionMode) {
                    ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF330000), contentColor = RedPrimary)
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
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
private fun SunTimesInfoCard(
    trajectory: SolarTrajectory,
    keepScreenOn: Boolean,
    redVisionMode: Boolean,
    azimuthOffset: Float,
    onToggleKeepScreenOn: () -> Unit,
    onToggleRedVision: () -> Unit,
    onResetCalibration: () -> Unit,
    onClose: () -> Unit,
) {
    val dayTimes = trajectory.dayTimes
    val containerBg = if (redVisionMode) Color(0xF8150000) else Color(0xF2181818)
    val cardBorder = if (redVisionMode) Color(0x66FF1744) else Color(0x33FFFFFF)
    val titleColor = if (redVisionMode) RedPrimary else Color.White
    val subColor = if (redVisionMode) Color(0xCCFF5252) else Color.White.copy(alpha = 0.6f)
    val sunriseColor = if (redVisionMode) RedPrimary else NormalSunrise
    val sunsetColor = if (redVisionMode) RedPrimary else NormalSunset
    val noonColor = if (redVisionMode) RedPrimary else NormalGoldSolar

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Sunrise
                InfoItem(
                    label = stringResource(R.string.sun_ar_sunrise),
                    value = trajectory.sunriseMarker?.formattedTime ?: "—",
                    sub = trajectory.sunriseMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
                    color = sunriseColor,
                    subColor = subColor,
                )

                // Solar Noon
                InfoItem(
                    label = stringResource(R.string.sun_ar_solar_noon),
                    value = trajectory.solarNoonMarker.formattedTime,
                    sub = "${dayTimes.maxElevationDegrees.toInt()}° max",
                    color = noonColor,
                    subColor = subColor,
                )

                // Sunset
                InfoItem(
                    label = stringResource(R.string.sun_ar_sunset),
                    value = trajectory.sunsetMarker?.formattedTime ?: "—",
                    sub = trajectory.sunsetMarker?.let { "${it.coordinates.azimuthDegrees.toInt()}° ${it.coordinates.cardinalDirection()}" } ?: "",
                    color = sunsetColor,
                    subColor = subColor,
                )
            }

            // Day length
            val hours = dayTimes.dayLengthSeconds / 3600
            val minutes = (dayTimes.dayLengthSeconds % 3600) / 60
            Text(
                text = "${stringResource(R.string.sun_ar_day_length)}: $hours h $minutes min",
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            // Red Vision Mode switch row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleRedVision() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sun_ar_red_vision),
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.sun_ar_red_vision_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
                Switch(
                    checked = redVisionMode,
                    onCheckedChange = { onToggleRedVision() },
                    colors = if (redVisionMode) {
                        SwitchDefaults.colors(
                            checkedThumbColor = RedPrimary,
                            checkedTrackColor = Color(0xFF550000),
                        )
                    } else {
                        SwitchDefaults.colors()
                    }
                )
            }

            // Keep screen on switch row
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
                    colors = if (redVisionMode) {
                        SwitchDefaults.colors(
                            checkedThumbColor = RedPrimary,
                            checkedTrackColor = Color(0xFF550000),
                        )
                    } else {
                        SwitchDefaults.colors()
                    }
                )
            }

            // Azimuth calibration row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sun_ar_calib_offset, azimuthOffset),
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
                if (abs(azimuthOffset) > 0.2f) {
                    TextButton(onClick = onResetCalibration) {
                        Text(
                            text = stringResource(R.string.sun_ar_calib_reset),
                            color = if (redVisionMode) RedPrimary else NormalGoldSolar,
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
    redVisionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (redVisionMode) Color(0xFF100000) else MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = AppIcons.Sun,
                contentDescription = null,
                tint = if (redVisionMode) RedPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.sun_ar_camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = if (redVisionMode) RedPrimary else Color.Unspecified,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sun_ar_camera_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = if (redVisionMode) Color(0xCCFF5252) else LocalContentColor.current.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                shape = CircleShape,
                colors = if (redVisionMode) ButtonDefaults.buttonColors(containerColor = RedPrimary) else ButtonDefaults.buttonColors()
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

package com.dazix.intervalometer.ui.nightsky

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.dazix.intervalometer.domain.nightsky.StarCatalog
import com.dazix.intervalometer.service.DisplayGeometry
import com.dazix.intervalometer.ui.AppIcons
import com.dazix.intervalometer.ui.Permissions

import android.graphics.BitmapFactory

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

    val displayRotation = DisplayGeometry.currentRotation(context)
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 ||
        displayRotation == android.view.Surface.ROTATION_180

    val density = LocalDensity.current
    val renderer = remember { NightSkyRenderer() }
    val maxTouchRadiusPx = with(density) { 48.dp.toPx() }

    val bgColor = if (uiState.redVisionMode) Color(0xFF000000) else Color(0xFF0A0E14)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTransformGestures(
                    panZoomLock = false,
                    onGesture = { _, pan, zoom, _ ->
                        val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                        if (pan.x != 0f || pan.y != 0f) {
                            val fovRad = Math.toRadians(uiState.cameraFovHorizontal.toDouble()).toFloat()
                            val pitchRad = (pan.y / viewWidth) * fovRad
                            val yawRad = (pan.x / viewWidth) * fovRad
                            viewModel.rotatePan(pitchRad = pitchRad, yawRad = yawRad)
                        }
                        if (zoom != 1.0f) {
                            viewModel.adjustZoomBy(zoom)
                        }
                    }
                )
            }
            .pointerInput(catalog, maxTouchRadiusPx) {
                detectTapGestures(
                    onTap = { offset ->
                        val totalStars = catalog?.starsLength() ?: 0
                        val hitIndex = renderer.findClosestStarAt(
                            tapX = offset.x,
                            tapY = offset.y,
                            maxRadiusPx = maxTouchRadiusPx,
                            totalStars = totalStars,
                        )
                        if (hitIndex >= 0) {
                            viewModel.selectStarByIndex(hitIndex)
                        } else {
                            viewModel.clearSelectedBody()
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
                redVisionMode = uiState.redVisionMode,
                isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                isMilkyWayVisible = uiState.isMilkyWayVisible,
                isAtmosphereVisible = uiState.isAtmosphereVisible,
                isLandscapeVisible = uiState.isLandscapeVisible,
                isGridVisible = uiState.isGridVisible,
                isConstellationsVisible = uiState.isConstellationsVisible,
                onVisibleStarsUpdated = viewModel::updateVisibleStarCount,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Camera Permission Request Surface
            NightSkyPermissionPrompt(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
                redVisionMode = uiState.redVisionMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top Controls & HUD Bar
        NightSkyTopBar(
            orientation = orientation,
            lstFormatted = uiState.lstFormatted,
            visibleStarCount = uiState.visibleStarCount,
            totalStarCount = uiState.totalStarCount,
            cameraFovHorizontal = uiState.cameraFovHorizontal,
            zoomRatio = uiState.zoomRatio,
            isCalibrated = uiState.isCalibrated,
            hasGpsFix = uiState.hasGpsFix,
            locationText = uiState.location.formatShort(),
            keepScreenOn = uiState.keepScreenOn,
            redVisionMode = uiState.redVisionMode,
            onBack = onBack,
            onToggleKeepScreenOn = {
                val nextState = !uiState.keepScreenOn
                showToggleToast(context, R.string.sun_ar_keep_screen_on, nextState)
                viewModel.toggleKeepScreenOn()
            },
            onToggleRedVision = {
                val nextState = !uiState.redVisionMode
                showToggleToast(context, R.string.sun_ar_red_vision, nextState)
                viewModel.toggleRedVisionMode()
            },
            onResetCalibration = {
                showResetToast(context)
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

        // Bottom Controls: Action Dock & HUD Indicator Chips (visible when no object selected)
        AnimatedVisibility(
            visible = uiState.selectedBody == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Expressive Action Dock (Planetarium, Milky Way, Atmosphere, Landscape, Grid, Constellations, Reset)
                NightSkyActionDock(
                    isSkyBackgroundOpaque = uiState.isSkyBackgroundOpaque,
                    isMilkyWayVisible = uiState.isMilkyWayVisible,
                    isAtmosphereVisible = uiState.isAtmosphereVisible,
                    isLandscapeVisible = uiState.isLandscapeVisible,
                    isGridVisible = uiState.isGridVisible,
                    isConstellationsVisible = uiState.isConstellationsVisible,
                    redVisionMode = uiState.redVisionMode,
                    isCalibrated = uiState.isCalibrated,
                    onToggleSkyBackgroundOpaque = {
                        val nextState = !uiState.isSkyBackgroundOpaque
                        showToggleToast(context, R.string.night_sky_opaque_bg, nextState)
                        viewModel.toggleSkyBackgroundOpaque()
                    },
                    onToggleMilkyWayVisible = {
                        val nextState = !uiState.isMilkyWayVisible
                        showToggleToast(context, R.string.night_sky_milky_way, nextState)
                        viewModel.toggleMilkyWayVisible()
                    },
                    onToggleAtmosphereVisible = {
                        val nextState = !uiState.isAtmosphereVisible
                        showToggleToast(context, R.string.night_sky_atmosphere, nextState)
                        viewModel.toggleAtmosphereVisible()
                    },
                    onToggleLandscapeVisible = {
                        val nextState = !uiState.isLandscapeVisible
                        showToggleToast(context, R.string.night_sky_landscape, nextState)
                        viewModel.toggleLandscapeVisible()
                    },
                    onToggleGridVisible = {
                        val nextState = !uiState.isGridVisible
                        showToggleToast(context, R.string.night_sky_grid, nextState)
                        viewModel.toggleGridVisible()
                    },
                    onToggleConstellationsVisible = {
                        val nextState = !uiState.isConstellationsVisible
                        showToggleToast(context, R.string.night_sky_constellations, nextState)
                        viewModel.toggleConstellationsVisible()
                    },
                    onResetCalibration = {
                        showResetToast(context)
                        viewModel.resetCalibration()
                    },
                )

                // Bottom HUD (LST & Visible Stars)
                NightSkyBottomHud(
                    lstFormatted = uiState.lstFormatted,
                    visibleStarCount = uiState.visibleStarCount,
                    totalStarCount = uiState.totalStarCount,
                    redVisionMode = uiState.redVisionMode,
                )
            }
        }

        // Celestial Object Detail Bottom Sheet
        CelestialDetailBottomSheet(
            selectedBody = uiState.selectedBody,
            redVisionMode = uiState.redVisionMode,
            onDismiss = { viewModel.clearSelectedBody() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
    redVisionMode: Boolean,
    isSkyBackgroundOpaque: Boolean,
    isMilkyWayVisible: Boolean,
    isAtmosphereVisible: Boolean,
    isLandscapeVisible: Boolean,
    isGridVisible: Boolean,
    isConstellationsVisible: Boolean,
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
    renderer.applyTheme(redVisionMode)

    LaunchedEffect(milkyWayBitmap, landscapeBitmap) {
        milkyWayBitmap?.let { renderer.setMilkyWayBitmap(it) }
        landscapeBitmap?.let { renderer.setLandscapeBitmap(it) }
    }

    Canvas(modifier = modifier) {
        if (orientation == null) return@Canvas

        val w = size.width
        val h = size.height
        val matrix = orientation.rotationMatrix

        // Configure optical star halo/core stroke widths scaling by screen density and zoom
        renderer.updateOpticalPaints(
            zoomFactor = zoomRatio,
            density = density,
            redVisionMode = redVisionMode,
            constellationPx = 1.8.dp.toPx(),
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
            isRedVision = redVisionMode,
        )

        // Layer 2: Dual-pass batch render visible stars (halo + core) and constellation lines
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
            isAtmosphereVisible = isAtmosphereVisible,
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
            isRedVision = redVisionMode,
        )

        // Layer 4: 3D Celestial Dome Coordinate Grid (Horizon, Meridians, Altitude Rings, Cardinals)
        if (isGridVisible) {
            renderer.skyDomeRenderer.drawSkyDomeGrid(
                drawScope = this,
                matrix = matrix,
                displayRotation = displayRotation,
                fovH = fovHorizontalDegrees,
                redVisionMode = redVisionMode,
                w = w,
                h = h,
            )
        }

        onVisibleStarsUpdated(visibleCount)
    }
}

@Composable
private fun NightSkyTopBar(
    orientation: DeviceOrientation?,
    lstFormatted: String,
    visibleStarCount: Int,
    totalStarCount: Int,
    cameraFovHorizontal: Float,
    zoomRatio: Float,
    isCalibrated: Boolean,
    hasGpsFix: Boolean,
    locationText: String,
    keepScreenOn: Boolean,
    redVisionMode: Boolean,
    onBack: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleRedVision: () -> Unit,
    onResetCalibration: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (redVisionMode) Color(0xF0180000) else Color(0xF0151820)
    val contentColor = if (redVisionMode) RedPrimary else Color(0xFFE0E0E0)
    val borderColor = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back Button (Material 3 Expressive Perfect Circle)
        Surface(
            shape = CircleShape,
            color = containerColor,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor),
            modifier = Modifier.size(48.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Center HUD Pill (Expressive Round)
        Surface(
            shape = CircleShape,
            color = containerColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Heading / Elevation
                if (orientation != null) {
                    val az = orientation.cameraAzimuthDegrees.toInt()
                    val el = orientation.cameraElevationDegrees.toInt()
                    Text(
                        text = String.format(java.util.Locale.US, "%03d° · %+d°", az, el),
                        color = contentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // FOV & Zoom indicator
                val isZoomed = kotlin.math.abs(zoomRatio - 1.0f) > 0.05f
                val fovText = if (isZoomed) {
                    String.format(java.util.Locale.US, "%.1fx · FOV %.0f°", zoomRatio, cameraFovHorizontal)
                } else {
                    String.format(java.util.Locale.US, "FOV %.0f°", cameraFovHorizontal)
                }
                Text(
                    text = fovText,
                    color = if (isZoomed) {
                        if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)
                    } else {
                        contentColor.copy(alpha = 0.8f)
                    },
                    fontSize = 11.sp,
                    fontWeight = if (isZoomed) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(enabled = isZoomed, onClick = onResetZoom),
                )

                // Calibration Offset Badge (if user has rotated manually)
                if (isCalibrated) {
                    Text(
                        text = "MANUAL",
                        color = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // Action Buttons Row (Material 3 Expressive Round)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Reset Calibration Button (if rotated)
            if (isCalibrated) {
                Surface(
                    shape = CircleShape,
                    color = containerColor,
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor),
                    modifier = Modifier.size(48.dp),
                ) {
                    IconButton(
                        onClick = onResetCalibration,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = AppIcons.ResetCalibration,
                            contentDescription = stringResource(R.string.sun_ar_calib_reset),
                            tint = contentColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Keep Screen Awake Button
            Surface(
                shape = CircleShape,
                color = if (keepScreenOn) (if (redVisionMode) RedPrimary else Color(0xFF00ACC1)) else containerColor,
                border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor),
                modifier = Modifier.size(48.dp),
            ) {
                IconButton(
                    onClick = onToggleKeepScreenOn,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = AppIcons.ScreenAwake,
                        contentDescription = stringResource(R.string.sun_ar_keep_screen_on),
                        tint = if (keepScreenOn) (if (redVisionMode) Color.Black else Color.White) else contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Red Vision Mode Toggle Button
            Surface(
                shape = CircleShape,
                color = if (redVisionMode) RedPrimary else containerColor,
                border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor),
                modifier = Modifier.size(48.dp),
            ) {
                IconButton(
                    onClick = onToggleRedVision,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = AppIcons.RedVision,
                        contentDescription = stringResource(R.string.sun_ar_red_vision),
                        tint = if (redVisionMode) Color.Black else contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NightSkyBottomHud(
    lstFormatted: String,
    visibleStarCount: Int,
    totalStarCount: Int,
    redVisionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (redVisionMode) Color(0xF0180000) else Color(0xF0151820)
    val contentColor = if (redVisionMode) RedPrimary else Color(0xFFE0E0E0)
    val borderColor = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)

    Surface(
        shape = CircleShape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor.copy(alpha = 0.8f)),
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
                    text = String.format(java.util.Locale.US, "%d / %d", visibleStarCount, totalStarCount),
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
    modifier: Modifier = Modifier,
) {
    val textColor = if (redVisionMode) RedPrimary else Color.White
    val containerColor = if (redVisionMode) Color(0xFF000000) else Color(0xFF10141C)
    val btnColor = if (redVisionMode) RedPrimary else Color(0xFF00ACC1)

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

private fun showToggleToast(context: Context, @androidx.annotation.StringRes actionLabelRes: Int, nextState: Boolean) {
    val actionLabel = context.getString(actionLabelRes)
    val stateText = context.getString(if (nextState) R.string.state_on else R.string.state_off)
    Toast.makeText(context, "$actionLabel: $stateText", Toast.LENGTH_SHORT).show()
}

private fun showResetToast(context: Context) {
    Toast.makeText(context, context.getString(R.string.calib_reset_toast), Toast.LENGTH_SHORT).show()
}


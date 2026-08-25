package com.dazix.intervalometer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.intervalometer.R
import com.dazix.intervalometer.data.AdvancedSettings
import com.dazix.intervalometer.domain.SessionSnapshot
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.overlay.CalibrationOverlay
import com.dazix.intervalometer.service.CameraProfile
import com.dazix.intervalometer.service.CameraProfiles

/**
 * The whole UI: one activity, one view model, plain state hoisting instead of a navigation library.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every screen is a Scaffold whose bars consume the system insets themselves, so the app
        // draws behind the status and navigation bars instead of being letterboxed by them.
        enableEdgeToEdge()
        setContent {
            IntervalometerTheme {
                IntervalometerApp()
            }
        }
    }
}

@Composable
private fun IntervalometerApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val advanced by viewModel.advanced.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val blockers by viewModel.blockers.collectAsStateWithLifecycle()
    val calibration by viewModel.calibrationTarget.collectAsStateWithLifecycle()
    val floorMs by viewModel.intervalFloorMs.collectAsStateWithLifecycle()
    val serviceUnavailable by viewModel.serviceUnavailable.collectAsStateWithLifecycle()
    val apps by viewModel.visibleApps.collectAsStateWithLifecycle()
    val appsLoading by viewModel.appsLoading.collectAsStateWithLifecycle()
    val query by viewModel.appQuery.collectAsStateWithLifecycle()
    val nightVisionTheme by viewModel.nightVisionTheme.collectAsStateWithLifecycle()
    val nightVisionActivationMode by viewModel.nightVisionActivationMode.collectAsStateWithLifecycle()
    val customStartTime by viewModel.customStartTime.collectAsStateWithLifecycle()
    val customEndTime by viewModel.customEndTime.collectAsStateWithLifecycle()

    // Every one of these is granted outside the app, so the state is re-read on each resume.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSystemState()
        onPauseOrDispose { }
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshSystemState() }

    // The answer is only re-read, never acted on: on this target SDK the dialog always offers
    // "Select photos", which grants partial access and leaves READ_MEDIA_IMAGES denied. Switching
    // the toggle off here would silently undo the choice the user just made, so the toggle stays on
    // and session setup explains what full photo access is still needed for.
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshSystemState() }

    val coroutineScope = rememberCoroutineScope()
    val toastHostState = remember(coroutineScope) { AppToastHostState(coroutineScope) }

    // Calibration is not an activity result any more: the capture surface is an overlay window put up
    // over the *real* camera app, so it outlives this activity going to the background and reports
    // back through CalibrationOverlay.lastResult, which the view model collects. Starting it from this
    // (foreground) context is what keeps the camera launch from being a background activity start.
    val overlayMissingMessage = stringResource(R.string.home_block_overlay)
    val startCalibration: () -> Unit = {
        if (!CalibrationOverlay.start(context, setup.targetPackage)) {
            if (permissions.canDrawOverlays) {
                // Permission is there, so the surface itself refused to come up.
                toastHostState.showToast(R.string.ovl_calib_unavailable)
            } else {
                toastHostState.showToast(overlayMissingMessage)
                context.launchSafely(Permissions.overlaySettings(context), toastHostState)
            }
        }
    }

    // The profile the *session* will run with, not the one the package alone suggests: a periodic tap
    // runs CameraProfiles.passthrough whatever app it names, so gating the Advanced extras on
    // forPackage offered "launch in Night Sight" and the adaptive long-exposure wait as supported for
    // a periodic tap on Google Camera, where both silently do nothing.
    val profile = remember(setup.mode, setup.targetPackage) {
        CameraProfiles.forTrigger(setup.triggerMode())
    }

    BackHandler(enabled = screen != Screen.HOME) { viewModel.back() }

    val homeScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val saveableStateHolder = rememberSaveableStateHolder()

    androidx.compose.runtime.CompositionLocalProvider(LocalToastHostState provides toastHostState) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            AppNavigationShell(
                current = screen,
                onNavigate = viewModel::navigate,
                // Top-level navigation bar is shown on home and settings; sub-screens and standalone tools leave by Back.
                enabled = screen == Screen.HOME || screen == Screen.SETTINGS,
            ) {
                // Screens arrive on the expressive springs: a small scale-up under a cross-fade, which is the
                // expressive "container transform without a shared element" and reads as one surface being
                // replaced rather than two sliding past each other.
                // Read off the theme here rather than inside `transitionSpec`, which is not a composable scope.
                val spatial = ExpressiveMotion.spatial<Float>()
                val effects = ExpressiveMotion.effects<Float>()
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.94f, animationSpec = spatial) +
                            fadeIn(animationSpec = effects))
                            .togetherWith(
                                scaleOut(targetScale = 0.94f, animationSpec = spatial) +
                                    fadeOut(animationSpec = effects)
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "screen",
                ) { visible ->
                    saveableStateHolder.SaveableStateProvider(key = visible) {
                        IntervalometerScreen(
                            screen = visible,
                            viewModel = viewModel,
                            setup = setup,
                            advanced = advanced,
                            permissions = permissions,
                            snapshot = snapshot,
                            blockers = blockers,
                            calibration = calibration,
                            floorMs = floorMs,
                            serviceUnavailable = serviceUnavailable,
                            apps = apps,
                            appsLoading = appsLoading,
                            query = query,
                            profile = profile,
                            nightVisionTheme = nightVisionTheme,
                            nightVisionActivationMode = nightVisionActivationMode,
                            customStartTime = customStartTime,
                            customEndTime = customEndTime,
                            homeScrollState = homeScrollState,
                            startCalibration = startCalibration,
                            onRequestNotifications = {
                                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onRequestMediaPermission = {
                                mediaLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                            },
                        )
                    }
                }
            }

            AppToastHost(
                hostState = toastHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

/**
 * One screen, chosen by [screen]. Split out of [IntervalometerApp] only so the animated container
 * above it has a single child to swap; every argument is still the hoisted state the view model owns.
 */
@Composable
private fun IntervalometerScreen(
    screen: Screen,
    viewModel: MainViewModel,
    setup: SetupUiState,
    advanced: AdvancedSettings,
    permissions: PermissionState,
    snapshot: SessionSnapshot,
    blockers: List<StartBlocker>,
    calibration: TapTarget?,
    floorMs: Long,
    serviceUnavailable: Boolean,
    apps: List<AppEntry>,
    appsLoading: Boolean,
    query: String,
    profile: CameraProfile,
    nightVisionTheme: com.dazix.intervalometer.domain.nightsky.NightVisionTheme,
    nightVisionActivationMode: com.dazix.intervalometer.domain.nightsky.NightVisionActivationMode,
    customStartTime: java.time.LocalTime,
    customEndTime: java.time.LocalTime,
    homeScrollState: ScrollState,
    startCalibration: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestMediaPermission: () -> Unit,
) {
    val context = LocalContext.current
    when (screen) {
        Screen.HOME -> HomeScreen(
            snapshot = snapshot,
            setup = setup,
            calibrated = calibration != null,
            blockers = blockers,
            serviceUnavailable = serviceUnavailable,
            scrollState = homeScrollState,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onNavigate = viewModel::navigate,
            onCalibrate = startCalibration,
            onOpenAccessibilitySettings = {
                context.launchSafely(Permissions.accessibilitySettings())
            },
            onOpenOverlaySettings = { context.launchSafely(Permissions.overlaySettings(context)) },
            onDismissServiceMessage = viewModel::dismissServiceMessage,
        )

        Screen.SETUP -> SetupScreen(
            state = setup,
            floorMs = floorMs,
            mediaPermissionGranted = permissions.readMediaImagesGranted,
            mediaPermissionPartial = permissions.readMediaPartialGranted,
            onChange = { updated -> viewModel.updateSetup { updated } },
            onJitterChange = viewModel::setTargetJitter,
            onRequestMediaPermission = onRequestMediaPermission,
            onOpenAppSettings = { context.launchSafely(Permissions.appInfo(context)) },
            onBack = viewModel::back,
        )

        Screen.RAMP -> RampScreen(
            state = setup,
            floorMs = floorMs,
            onChange = { updated -> viewModel.updateSetup { updated } },
            onBack = viewModel::back,
        )

        Screen.ADVANCED -> AdvancedScreen(
            settings = advanced,
            profile = profile,
            targetLabel = setup.targetPackage?.let { setup.targetLabel ?: it },
            overlayAllowed = permissions.canDrawOverlays,
            onChange = { updated -> viewModel.updateAdvanced { updated } },
            onBack = viewModel::back,
        )

        Screen.ONBOARDING -> OnboardingScreen(
            permissions = permissions,
            onOpenAppInfo = { context.launchSafely(Permissions.appInfo(context)) },
            onOpenAccessibilitySettings = {
                context.launchSafely(Permissions.accessibilitySettings())
            },
            onOpenOverlaySettings = { context.launchSafely(Permissions.overlaySettings(context)) },
            onRequestNotifications = onRequestNotifications,
            onRequestBatteryExemption = {
                context.launchSafely(Permissions.batteryOptimizationExemption(context))
            },
            onRefresh = viewModel::refreshSystemState,
            onBack = viewModel::back,
        )

        Screen.APP_PICKER -> AppPickerScreen(
            apps = apps,
            query = query,
            loading = appsLoading,
            selectedPackage = setup.targetPackage,
            onQueryChange = viewModel::setAppQuery,
            onSelect = viewModel::selectApp,
            onClear = viewModel::clearApp,
            onBack = viewModel::back,
        )

        Screen.SUN_AR -> com.dazix.intervalometer.ui.solar.SunArScreen(
            onBack = viewModel::back,
        )

        Screen.NIGHT_SKY_AR -> com.dazix.intervalometer.ui.nightsky.NightSkyArScreen(
            onBack = viewModel::back,
        )

        Screen.SETTINGS -> SettingsScreen(
            theme = nightVisionTheme,
            activationMode = nightVisionActivationMode,
            startTime = customStartTime,
            endTime = customEndTime,
            onThemeChange = viewModel::setNightVisionTheme,
            onActivationModeChange = viewModel::setNightVisionActivationMode,
            onStartTimeChange = viewModel::setCustomStartTime,
            onEndTimeChange = viewModel::setCustomEndTime,
            onBack = viewModel::back,
        )
    }
}

private fun Context.launchSafely(intent: Intent, toastHostState: AppToastHostState? = null) {
    val result = runCatching { startActivity(intent) }
    if (result.isFailure) {
        toastHostState?.showToast(R.string.common_unsupported)
            ?: runCatching { Toast.makeText(this, R.string.common_unsupported, Toast.LENGTH_LONG).show() }
    }
}

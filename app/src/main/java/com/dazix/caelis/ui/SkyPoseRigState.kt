package com.dazix.caelis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dazix.caelis.ar.CameraFrameSource
import com.dazix.caelis.ar.FovResult
import com.dazix.caelis.ar.pose.ControllerArCoreHost
import com.dazix.caelis.ar.pose.SensorPose
import com.dazix.caelis.ar.pose.SkyPoseRig
import com.dazix.caelis.data.ArCoreSettings
import com.dazix.caelis.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The pose rig of one AR screen, wired to the screen's lifecycle and to the user's ARCore consent.
 *
 * - Consent is read from the settings flow into the rig and starts out **off**: until the store has
 *   answered, the camera is CameraX's and nothing of ARCore exists. The rig builds its ARCore host
 *   lazily, only when the owner policy asks for ARCore, which requires consent, so a user who never
 *   said yes never has `ArCoreSessionController` created, let alone asked anything.
 * - The activity pausing pauses ARCore's session with it and resuming starts it again, because a
 *   session left running in the background holds the camera and keeps Google's component busy.
 * - A guard tick confirms consent once a second while a session runs, which nothing else does: a
 *   running session never asks again, and withdrawing consent in Settings has to stop it.
 * - Leaving the screen closes the session.
 *
 * @param cameraPermission whether the camera permission is granted; neither camera can start without it.
 * @param onFov receives the field of view of whichever camera is live, for the screen's view model.
 */
@Composable
fun rememberSkyPoseRig(
    sensorPose: SensorPose,
    cameraSource: CameraFrameSource,
    cameraPermission: Boolean,
    onFov: (FovResult) -> Unit,
): SkyPoseRig {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context.applicationContext) }
    val currentOnFov by rememberUpdatedState(onFov)
    val rig = remember(sensorPose, cameraSource) {
        SkyPoseRig(
            scope = scope,
            sensorPose = sensorPose,
            cameraX = cameraSource,
            hostFactory = { ControllerArCoreHost.fromSettings(context.applicationContext, settings) },
            onFov = { currentOnFov(it) },
        )
    }

    val arCore by settings.arCore.collectAsStateWithLifecycle(initialValue = ArCoreSettings())
    SideEffect {
        rig.consented = arCore.arCoreConsent
        rig.cameraPermission = cameraPermission
    }

    DisposableEffect(rig, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> rig.onLifecyclePause()
                Lifecycle.Event.ON_RESUME -> rig.onLifecycleResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            rig.release()
        }
    }

    LaunchedEffect(rig) {
        while (isActive) {
            delay(GUARD_TICK_MS)
            rig.guardTick()
        }
    }
    return rig
}

private const val GUARD_TICK_MS = 1_000L

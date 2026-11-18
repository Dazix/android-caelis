package com.dazix.caelis.ar

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reusable CameraX preview surface that reports the field of view actually visible in the viewport.
 *
 * The FOV is measured, not assumed. `SENSOR_INFO_PHYSICAL_SIZE` and the focal length only describe
 * the lens over the whole pixel array; what reaches the screen is a `SCALER_CROP_REGION` inside the
 * active array, at whatever resolution CameraX chose, scaled by a FILL_CENTER fit. Deriving
 * the FOV from the lens alone made the AR overlay 14 % too wide on a Pixel, which squeezes the
 * terrain silhouette towards the centre of the frame — an error no amount of panning can correct.
 *
 * @param onFovCalculated receives the [FovResult] containing the calculated FOV and calculation status.
 * @param onCameraBound receives the bound camera's [CameraControl] and supported zoom range so a
 *   pinch can reach the camera. A zoom that never arrives at the camera shrinks the overlay while
 *   the image stays put.
 * @param onFrameGrabber receives a [FrameGrabber] when frame analysis is wanted, or null when the
 *   camera could not be bound. Pass `analyzeFrames = false` (the default) to skip the use case
 *   entirely: an analysis stream that is never read still costs the camera a buffer queue.
 * @param frameClock receives the exposure instant of every preview frame, so the overlay can be
 *   drawn with the pose of the picture it sits on ([PreviewFrameClock]).
 * @param frameSource the `Preview` use case renders into it and this composable shows nothing
 *   itself: the owner draws the picture in Filament, in the same frame as the overlay
 *   ([CameraFrameSource]). There is no `PreviewView` path any more (CameraX's camera-view artifact
 *   is not a dependency). The composable still fills its layout box, which must be the same box the
 *   sky surface fills, because the FOV is computed for that viewport with the FILL_CENTER fit.
 *   Leaving the composition unbinds the use cases, since a camera nobody is drawing is a camera
 *   left running for nothing.
 * @param displayRotation `Surface.ROTATION_*` of the display: without a `PreviewView` to keep the
 *   preview's target rotation current, the picture would not turn when the phone is turned between
 *   the two landscapes. Negative leaves CameraX's default.
 * @param cameraEnabled false unbinds the use cases and keeps them unbound. ARCore cannot open a camera
 *   CameraX holds, so the pose rig turns this off to hand the camera over; the layout box and the
 *   viewport view stay, only the camera goes.
 * @param onCameraReleased called when the camera is really free after [cameraEnabled] turned false -
 *   the device reported closed, or nothing was bound, or two seconds passed - because `unbind` alone
 *   only *starts* closing the camera, and a session that starts in that gap fails to open it.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner,
    isPortrait: Boolean,
    onFovCalculated: (fovResult: FovResult) -> Unit,
    onCameraBound: (control: CameraControl?, minZoomRatio: Float, maxZoomRatio: Float) -> Unit = { _, _, _ -> },
    analyzeFrames: Boolean = false,
    onFrameGrabber: (FrameGrabber?) -> Unit = {},
    frameClock: PreviewFrameClock? = null,
    frameSource: CameraFrameSource,
    displayRotation: Int = -1,
    cameraEnabled: Boolean = true,
    onCameraReleased: () -> Unit = {},
) {
    val context = LocalContext.current
    // The view whose size is the viewport. It draws nothing — the picture goes to [frameSource] —
    // and exists only so the FOV keeps being computed from a laid-out size.
    val viewportView: View = remember { View(context) }
    val bound = remember { BoundUseCases() }
    val latestDisplayRotation by rememberUpdatedState(displayRotation)

    LaunchedEffect(displayRotation) {
        if (displayRotation >= 0) bound.preview?.targetRotation = surfaceRotation(displayRotation)
    }
    DisposableEffect(frameSource) {
        onDispose {
            bound.disposed = true
            val provider = bound.provider
            val preview = bound.preview
            val analysis = bound.analysis
            if (provider != null && preview != null) {
                if (analysis != null) provider.unbind(preview, analysis) else provider.unbind(preview)
            }
        }
    }

    val latestCameraEnabled by rememberUpdatedState(cameraEnabled)

    // An undispatched coroutine started by a DisposableEffect rather than a LaunchedEffect, and a
    // provider that is already there used at once rather than through a listener: the camera is the
    // slowest thing on an AR screen to deliver its first image, so binding it is the first thing the
    // screen does. A LaunchedEffect only started after the sky's surface and scene had been built
    // (~230 ms in) and the main-executor listener then queued behind the first sky frame (another
    // 60-80 ms, 230 ms on a cold start); the grid stood on an empty sky for the whole wait.
    val effectScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, isPortrait, cameraEnabled) {
        val job = effectScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!cameraEnabled) {
                releaseCamera(bound, viewportView)
                onFrameGrabber(null)
                onCameraBound(null, 1f, 1f)
                onCameraReleased()
                return@launch
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            fun bindCamera() {
                // The screen may be gone by the time the provider is ready; binding then would leave a
                // camera running that nobody is looking at. The same goes for a camera that was handed
                // to ARCore while the provider was still being fetched.
                if (bound.disposed || !latestCameraEnabled) return
                val cameraProvider = cameraProviderFuture.get()

                // Sensor crop region, read from the capture results rather than guessed. It moves with
                // digital zoom and with preview stabilisation, so it is re-read on every frame and the
                // FOV is recomputed only when it actually changes.
                var cropWidthPx = 0
                var cropHeightPx = 0

                val previewBuilder = Preview.Builder()
                if (latestDisplayRotation >= 0) previewBuilder.setTargetRotation(surfaceRotation(latestDisplayRotation))
                val previewExtender = Camera2Interop.Extender(previewBuilder)
                // Explicitly disable optical and digital stabilization to prevent IMU/viewfinder desynchronization
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                )
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )

                // Assigned once the camera is bound. The first capture results can arrive before that,
                // so this stays nullable rather than lateinit: an early frame simply updates the crop
                // and the binding below dispatches it.
                var dispatchFov: (() -> Unit)? = null
                // The stream resolution becomes known some frames after binding, and a crop that never
                // changes afterwards never asks again: without this the geometry-only FOV from before the
                // first frame could stay for the whole session.
                val dispatchedMeasurable = java.util.concurrent.atomic.AtomicBoolean(false)

                previewExtender.setSessionCaptureCallback(
                    object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: android.hardware.camera2.CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            frameClock?.onCaptureCompleted(result)
                            val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return
                            val w = crop.width()
                            val h = crop.height()
                            if (w != cropWidthPx || h != cropHeightPx) {
                                cropWidthPx = w
                                cropHeightPx = h
                                viewportView.post { dispatchFov?.invoke() }
                            } else if (!dispatchedMeasurable.get()) {
                                viewportView.post { dispatchFov?.invoke() }
                            }
                        }
                    },
                )

                val preview = previewBuilder.build().also {
                    it.surfaceProvider = frameSource
                }

                // Bound with no analyzer attached. One is set only for the single frame the automatic
                // alignment asks for and removed again immediately: this screen is a viewfinder, not a
                // continuous vision pipeline, and an always-running analyzer costs battery for nothing.
                val analysis = if (analyzeFrames) {
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        // Delivered the way up the screen is, so a frame row is a screen row and the
                        // skyline search can look for "sky above, ground below" without a rotation.
                        .setOutputImageRotationEnabled(true)

                    val analysisExtender = Camera2Interop.Extender(analysisBuilder)
                    analysisExtender.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                    )
                    analysisExtender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    )
                    analysisBuilder.build()
                } else {
                    null
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    bound.provider = cameraProvider
                    bound.preview = preview
                    bound.analysis = analysis
                    val camera = if (analysis != null) {
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                    } else {
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    }
                    bound.camera = camera
                    onFrameGrabber(analysis?.let { frameGrabberFor(it, context) })
                    val zoomState = camera.cameraInfo.zoomState.value
                    onCameraBound(
                        camera.cameraControl,
                        zoomState?.minZoomRatio ?: 1f,
                        zoomState?.maxZoomRatio ?: 1f,
                    )

                    // Dynamically extract physical sensor dimensions, active pixel array, and primary focal length
                    val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                    // Frame timestamps are comparable with sensor events only on the realtime clock; a
                    // camera on its own time base leaves the overlay on the live pose.
                    frameClock?.sharesSensorClock = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                    ) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                    val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val activeArraySize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val primaryFocalLength = CameraFovCalculator.primaryFocalLength(focalLengths)

                    // If physical size in mm is missing, fallback to active array pixel dimensions for aspect ratio
                    val effectiveSensorW = sensorSize?.width
                        ?: activeArraySize?.let { if (it.width() > 0) it.width().toFloat() else null }
                    val effectiveSensorH = sensorSize?.height
                        ?: activeArraySize?.let { if (it.height() > 0) it.height().toFloat() else null }

                    fun calculateAndDispatchFov(w: Float, h: Float) {
                        if (w > 10f && h > 10f) {
                            val resolution = preview.resolutionInfo?.resolution
                            val dynamicFov = CameraFovCalculator.computeStreamHorizontalFov(
                                sensorWidthMm = effectiveSensorW,
                                sensorHeightMm = effectiveSensorH,
                                focalLengthMm = primaryFocalLength,
                                activeArrayWidthPx = activeArraySize?.width() ?: 0,
                                activeArrayHeightPx = activeArraySize?.height() ?: 0,
                                cropRegionWidthPx = cropWidthPx,
                                cropRegionHeightPx = cropHeightPx,
                                streamWidthPx = resolution?.width ?: 0,
                                streamHeightPx = resolution?.height ?: 0,
                                viewportWidth = w,
                                viewportHeight = h,
                                isPortrait = isPortrait,
                            )
                            if (com.dazix.caelis.BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "CaelisFov",
                                    "active=${activeArraySize?.width()}x${activeArraySize?.height()} crop=${cropWidthPx}x$cropHeightPx" +
                                        " preview=${resolution?.width}x${resolution?.height}" +
                                        " analysis=${analysis?.resolutionInfo?.resolution}" +
                                        " viewport=${w}x$h sensor=${effectiveSensorW}x$effectiveSensorH focal=$primaryFocalLength" +
                                        " -> fov=${dynamicFov.horizontalDegrees} base=${dynamicFov.baseHorizontalDegrees}" +
                                        " measured=${dynamicFov.isDynamicStreamCalculated}",
                                )
                            }
                            if (dynamicFov.isDynamicStreamCalculated) dispatchedMeasurable.set(true)
                            onFovCalculated(dynamicFov)
                        }
                    }

                    val initialW = viewportView.width.toFloat().takeIf { it > 10f } ?: if (isPortrait) 1080f else 2340f
                    val initialH = viewportView.height.toFloat().takeIf { it > 10f } ?: if (isPortrait) 2340f else 1080f
                    dispatchFov = {
                        val w = viewportView.width.toFloat().takeIf { it > 10f } ?: initialW
                        val h = viewportView.height.toFloat().takeIf { it > 10f } ?: initialH
                        calculateAndDispatchFov(w, h)
                    }
                    calculateAndDispatchFov(initialW, initialH)

                    // Listen for real layout dimensions to guarantee exact optical crop ratio matching screen geometry
                    // One listener per binding, removed with it: a camera that is unbound and bound again
                    // (ARCore takes it and gives it back) would otherwise leave the old listeners
                    // dispatching a field of view computed from a preview that no longer exists.
                    bound.layoutListener?.let { viewportView.removeOnLayoutChangeListener(it) }
                    val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val newW = (right - left).toFloat()
                        val newH = (bottom - top).toFloat()
                        val oldW = (oldRight - oldLeft).toFloat()
                        val oldH = (oldBottom - oldTop).toFloat()
                        if (newW > 10f && newH > 10f && (newW != oldW || newH != oldH)) {
                            calculateAndDispatchFov(newW, newH)
                        }
                    }
                    bound.layoutListener = layoutListener
                    viewportView.addOnLayoutChangeListener(layoutListener)
                } catch (_: Exception) {
                    onCameraBound(null, 1f, 1f)
                    onFrameGrabber(null)
                }
            }

            if (cameraProviderFuture.isDone) {
                bindCamera()
            } else {
                cameraProviderFuture.addListener({ bindCamera() }, ContextCompat.getMainExecutor(context))
            }
        }
        onDispose { job.cancel() }
    }

    AndroidView(factory = { viewportView }, modifier = modifier)
}

/**
 * A display rotation as the `Surface.ROTATION_*` constant CameraX wants. The app passes the rotation
 * around as a plain 0..3 (the same as the constants' values), and spelling the mapping out is what
 * lets the platform's constant check see that nothing else can reach `setTargetRotation`.
 */
private fun surfaceRotation(rotation: Int): Int = when (rotation) {
    1 -> Surface.ROTATION_90
    2 -> Surface.ROTATION_180
    3 -> Surface.ROTATION_270
    else -> Surface.ROTATION_0
}

/** What this composable has bound, so that leaving the screen can unbind exactly that. Main thread only. */
private class BoundUseCases {
    var provider: ProcessCameraProvider? = null
    var preview: Preview? = null
    var analysis: ImageAnalysis? = null
    var camera: Camera? = null
    var layoutListener: View.OnLayoutChangeListener? = null
    var disposed = false
}

/**
 * Unbinds what was bound and waits until the camera device is actually closed.
 *
 * `unbind` only asks CameraX to close the camera; the device is released a moment later, and an
 * ARCore session started in between cannot open it. The wait ends on the camera state reaching
 * CLOSED (or a pending open, which is as free as a camera gets), and after [CAMERA_RELEASE_TIMEOUT_MS]
 * regardless, so a camera that never reports cannot hold the hand-over forever.
 */
private suspend fun releaseCamera(bound: BoundUseCases, viewportView: View) {
    val provider = bound.provider
    val preview = bound.preview
    val analysis = bound.analysis
    val camera = bound.camera
    bound.preview = null
    bound.analysis = null
    bound.camera = null
    bound.layoutListener?.let { viewportView.removeOnLayoutChangeListener(it) }
    bound.layoutListener = null
    if (provider == null || preview == null) return
    if (analysis != null) provider.unbind(preview, analysis) else provider.unbind(preview)
    if (camera == null) return
    withTimeoutOrNull(CAMERA_RELEASE_TIMEOUT_MS) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val liveData = camera.cameraInfo.cameraState
            val observer = object : Observer<CameraState> {
                override fun onChanged(value: CameraState) {
                    if (value.type == CameraState.Type.CLOSED || value.type == CameraState.Type.PENDING_OPEN) {
                        liveData.removeObserver(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            liveData.observeForever(observer)
            continuation.invokeOnCancellation { liveData.removeObserver(observer) }
        }
    }
}

private const val CAMERA_RELEASE_TIMEOUT_MS = 2_000L

/**
 * Hands back exactly one frame's brightness plane, then detaches.
 *
 * The Y plane is copied rather than handed on: the `ImageProxy` has to be closed before the camera
 * will produce another frame, and holding one open to avoid a copy deadlocks the analysis queue.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun frameGrabberFor(analysis: ImageAnalysis, context: android.content.Context) = FrameGrabber { onFrame ->
    analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
        try {
            val plane = image.planes.firstOrNull()
            if (plane != null) {
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                onFrame(
                    LumaFrame(
                        data = bytes,
                        width = image.width,
                        height = image.height,
                        rowStride = plane.rowStride,
                        timestampNs = image.imageInfo.timestamp,
                    ),
                )
            }
        } finally {
            image.close()
            analysis.clearAnalyzer()
        }
    }
}

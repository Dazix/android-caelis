package com.dazix.caelis.ar

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Reusable CameraX preview surface that reports the field of view actually visible in the viewport.
 *
 * The FOV is measured, not assumed. `SENSOR_INFO_PHYSICAL_SIZE` and the focal length only describe
 * the lens over the whole pixel array; what reaches the screen is a `SCALER_CROP_REGION` inside the
 * active array, at whatever resolution CameraX chose, scaled by `PreviewView` FILL_CENTER. Deriving
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
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner, isPortrait) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Sensor crop region, read from the capture results rather than guessed. It moves with
            // digital zoom and with preview stabilisation, so it is re-read on every frame and the
            // FOV is recomputed only when it actually changes.
            var cropWidthPx = 0
            var cropHeightPx = 0

            val previewBuilder = Preview.Builder()
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

            previewExtender.setSessionCaptureCallback(
                object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: android.hardware.camera2.CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return
                        val w = crop.width()
                        val h = crop.height()
                        if (w != cropWidthPx || h != cropHeightPx) {
                            cropWidthPx = w
                            cropHeightPx = h
                            previewView.post { dispatchFov?.invoke() }
                        }
                    }
                },
            )

            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
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
                val camera = if (analysis != null) {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                }
                onFrameGrabber(analysis?.let { frameGrabberFor(it, context) })
                val zoomState = camera.cameraInfo.zoomState.value
                onCameraBound(
                    camera.cameraControl,
                    zoomState?.minZoomRatio ?: 1f,
                    zoomState?.maxZoomRatio ?: 1f,
                )

                // Dynamically extract physical sensor dimensions, active pixel array, and primary focal length
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val activeArraySize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val primaryFocalLength = focalLengths?.let { list ->
                    if (list.size == 1) {
                        list[0]
                    } else {
                        // On multi-camera setups (e.g. [1.95, 6.81, 19.0]), select primary standard wide lens (3.5mm - 8.0mm)
                        list.firstOrNull { it in 3.5f..8.0f } ?: list.firstOrNull()
                    }
                }

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
                        onFovCalculated(dynamicFov)
                    }
                }

                val initialW = previewView.width.toFloat().takeIf { it > 10f } ?: if (isPortrait) 1080f else 2340f
                val initialH = previewView.height.toFloat().takeIf { it > 10f } ?: if (isPortrait) 2340f else 1080f
                dispatchFov = {
                    val w = previewView.width.toFloat().takeIf { it > 10f } ?: initialW
                    val h = previewView.height.toFloat().takeIf { it > 10f } ?: initialH
                    calculateAndDispatchFov(w, h)
                }
                calculateAndDispatchFov(initialW, initialH)

                // Listen for real layout dimensions to guarantee exact optical crop ratio matching screen geometry
                previewView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val newW = (right - left).toFloat()
                    val newH = (bottom - top).toFloat()
                    val oldW = (oldRight - oldLeft).toFloat()
                    val oldH = (oldBottom - oldTop).toFloat()
                    if (newW > 10f && newH > 10f && (newW != oldW || newH != oldH)) {
                        calculateAndDispatchFov(newW, newH)
                    }
                }
            } catch (_: Exception) {
                onCameraBound(null, 1f, 1f)
                onFrameGrabber(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

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

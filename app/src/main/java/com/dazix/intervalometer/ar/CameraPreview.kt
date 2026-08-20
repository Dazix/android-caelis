package com.dazix.intervalometer.ar

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
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
 * Reusable CameraX preview surface with dynamic optical FOV calculation from CameraCharacteristics.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner,
    isPortrait: Boolean,
    onFovCalculated: (Float) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner, isPortrait) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)

                // Dynamically extract physical sensor dimensions and primary focal length
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val primaryFocalLength = focalLengths?.let { list ->
                    if (list.size == 1) {
                        list[0]
                    } else {
                        // On multi-camera setups (e.g. [1.95, 6.81, 19.0]), select primary standard wide lens (3.5mm - 8.0mm)
                        list.firstOrNull { it in 3.5f..8.0f } ?: list.firstOrNull()
                    }
                }

                fun calculateAndDispatchFov(w: Float, h: Float) {
                    if (w > 10f && h > 10f) {
                        val dynamicFov = CameraFovCalculator.computeEffectiveHorizontalFov(
                            sensorWidthMm = sensorSize?.width,
                            sensorHeightMm = sensorSize?.height,
                            focalLengthMm = primaryFocalLength,
                            viewportWidth = w,
                            viewportHeight = h,
                            isPortrait = isPortrait,
                        )
                        onFovCalculated(dynamicFov)
                    }
                }

                val initialW = previewView.width.toFloat().takeIf { it > 10f } ?: if (isPortrait) 1080f else 2340f
                val initialH = previewView.height.toFloat().takeIf { it > 10f } ?: if (isPortrait) 2340f else 1080f
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
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

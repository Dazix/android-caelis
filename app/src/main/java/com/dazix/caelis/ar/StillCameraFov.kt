package com.dazix.caelis.ar

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * The field of view the back camera's preview would show in a [viewportWidth] x [viewportHeight]
 * viewport, read from its characteristics without opening it: for a screen that draws no camera
 * picture but should still show the sky at the scale the camera would (Night Sky under the opaque
 * Milky Way). Reading characteristics opens no device and shows no camera indicator.
 *
 * The crop is taken as the whole active array at its own aspect, which is what an unzoomed preview
 * streams; a measured FOV from a running camera ([CameraPreview]) replaces it as soon as there is one.
 * Null when the phone reports no back camera or the query fails.
 */
fun stillBackCameraFov(context: Context, viewportWidth: Float, viewportHeight: Float, isPortrait: Boolean): FovResult? = try {
    val manager = context.getSystemService(CameraManager::class.java)
    val id = manager?.cameraIdList?.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }
    id?.let {
        val characteristics = manager.getCameraCharacteristics(it)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeW = active?.width() ?: 0
        val activeH = active?.height() ?: 0
        val fov = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = sensorSize?.width,
            sensorHeightMm = sensorSize?.height,
            focalLengthMm = CameraFovCalculator.primaryFocalLength(
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
            ),
            activeArrayWidthPx = activeW,
            activeArrayHeightPx = activeH,
            cropRegionWidthPx = activeW,
            cropRegionHeightPx = activeH,
            streamWidthPx = activeW,
            streamHeightPx = activeH,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            isPortrait = isPortrait,
        )
        // Predicted, not measured: the HUD must not present it as read from a stream.
        FovResult(fov.baseHorizontalDegrees, isDynamicStreamCalculated = false, baseHorizontalDegrees = fov.baseHorizontalDegrees)
    }
} catch (_: Exception) {
    null
}

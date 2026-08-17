package com.dazix.intervalometer.ar

import kotlin.math.atan
import kotlin.math.tan

/**
 * Calculates the exact optical Field of View (FOV) of a camera lens from physical sensor dimensions,
 * focal length, and viewport crop factor (accounting for CameraX PreviewView FILL_CENTER).
 */
object CameraFovCalculator {

    private const val RAD_TO_DEG = 180.0 / Math.PI
    private const val DEG_TO_RAD = Math.PI / 180.0
    const val DEFAULT_PORTRAIT_FOV_DEGREES = 56.0f

    /**
     * Computes the raw uncropped horizontal Field of View of the camera sensor in degrees.
     */
    fun computeHorizontalFov(
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        focalLengthMm: Float?,
        isPortrait: Boolean,
        fallbackFovDegrees: Float = DEFAULT_PORTRAIT_FOV_DEGREES,
    ): Float {
        if (sensorWidthMm == null || sensorHeightMm == null || focalLengthMm == null ||
            sensorWidthMm <= 0.001f || sensorHeightMm <= 0.001f || focalLengthMm <= 0.001f
        ) {
            return if (isPortrait) fallbackFovDegrees else fallbackFovDegrees * 1.333f
        }

        val physicalDimAcrossWidth = if (isPortrait) {
            minOf(sensorWidthMm, sensorHeightMm)
        } else {
            maxOf(sensorWidthMm, sensorHeightMm)
        }

        val halfAngleRad = atan(physicalDimAcrossWidth.toDouble() / (2.0 * focalLengthMm.toDouble()))
        val fovDeg = (2.0 * halfAngleRad * RAD_TO_DEG).toFloat()

        return fovDeg.coerceIn(10.0f, 150.0f)
    }

    /**
     * Computes the effective horizontal Field of View visible in the viewport,
     * accounting for PreviewView.ScaleType.FILL_CENTER cropping on tall screens (e.g. 20:9).
     *
     * @param sensorWidthMm Physical width of camera sensor in millimeters.
     * @param sensorHeightMm Physical height of camera sensor in millimeters.
     * @param focalLengthMm Optical focal length of the active camera lens in millimeters.
     * @param viewportWidth Width of the preview surface in pixels.
     * @param viewportHeight Height of the preview surface in pixels.
     * @param isPortrait True if the device is currently in portrait orientation.
     * @param fallbackFovDegrees Default fallback if sensor info is unavailable.
     */
    fun computeEffectiveHorizontalFov(
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        focalLengthMm: Float?,
        viewportWidth: Float,
        viewportHeight: Float,
        isPortrait: Boolean,
        fallbackFovDegrees: Float = DEFAULT_PORTRAIT_FOV_DEGREES,
    ): Float {
        val rawFovH = computeHorizontalFov(
            sensorWidthMm,
            sensorHeightMm,
            focalLengthMm,
            isPortrait,
            fallbackFovDegrees,
        )

        if (viewportWidth <= 1f || viewportHeight <= 1f || sensorWidthMm == null || sensorHeightMm == null) {
            return rawFovH
        }

        val sensorW = if (isPortrait) minOf(sensorWidthMm, sensorHeightMm) else maxOf(sensorWidthMm, sensorHeightMm)
        val sensorH = if (isPortrait) maxOf(sensorWidthMm, sensorHeightMm) else minOf(sensorWidthMm, sensorHeightMm)

        val sensorAspect = sensorW / sensorH.coerceAtLeast(0.001f)
        val viewportAspect = viewportWidth / viewportHeight.coerceAtLeast(0.001f)

        // When viewport is narrower/taller than sensor aspect ratio (viewportAspect < sensorAspect),
        // PreviewView.ScaleType.FILL_CENTER fills height and crops the left/right edges of the sensor stream.
        return if (viewportAspect < sensorAspect) {
            val cropRatio = viewportAspect / sensorAspect
            val halfAngleRad = (rawFovH * 0.5 * DEG_TO_RAD)
            val effectiveHalfAngleRad = atan(tan(halfAngleRad) * cropRatio)
            (2.0 * effectiveHalfAngleRad * RAD_TO_DEG).toFloat().coerceIn(10.0f, 150.0f)
        } else {
            rawFovH
        }
    }
}

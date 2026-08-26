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
     * @param fallbackFovDegrees Default fallback uncropped FOV if sensor info is unavailable.
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

        if (viewportWidth <= 1f || viewportHeight <= 1f) {
            return rawFovH
        }

        val sensorAspect = if (sensorWidthMm != null && sensorHeightMm != null &&
            sensorWidthMm > 0.001f && sensorHeightMm > 0.001f
        ) {
            val sensorW = if (isPortrait) minOf(sensorWidthMm, sensorHeightMm) else maxOf(sensorWidthMm, sensorHeightMm)
            val sensorH = if (isPortrait) maxOf(sensorWidthMm, sensorHeightMm) else minOf(sensorWidthMm, sensorHeightMm)
            sensorW / sensorH.coerceAtLeast(0.001f)
        } else {
            // Standard camera sensor is 4:3 (in portrait: 3/4 = 0.75, in landscape: 4/3 = 1.3333)
            if (isPortrait) 3.0f / 4.0f else 4.0f / 3.0f
        }

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

    /**
     * Computes the effective focal length in pixels for perspective projection matching PreviewView FILL_CENTER.
     *
     * @param effectiveFovHorizontalDegrees Effective horizontal field of view in degrees.
     * @param viewportWidth Width of the preview viewport in pixels.
     * @return Focal length in screen pixels.
     */
    fun computeEffectiveFocalLengthPx(
        effectiveFovHorizontalDegrees: Float,
        viewportWidth: Float,
    ): Float {
        val halfAngleRad = effectiveFovHorizontalDegrees * 0.5 * DEG_TO_RAD
        return ((viewportWidth * 0.5f) / tan(halfAngleRad)).toFloat()
    }

    /**
     * Calculates the zoomed horizontal Field of View visible in the viewport given an optical/digital zoom factor.
     *
     * @param baseEffectiveFovDegrees Base effective horizontal FOV at 1.0x zoom.
     * @param zoomFactor Magnification factor (>= 1.0x).
     * @return Narrowed horizontal FOV in degrees.
     */
    fun computeZoomedFov(
        baseEffectiveFovDegrees: Float,
        zoomFactor: Float,
    ): Float {
        val safeZoom = zoomFactor.coerceAtLeast(0.01f)
        val safeBaseFov = baseEffectiveFovDegrees.coerceIn(0.1f, 160.0f)
        return (safeBaseFov / safeZoom).coerceIn(0.0005f, 150.0f)
    }

    /**
     * Computes the vertical Field of View in degrees from horizontal FOV and viewport dimensions.
     *
     * @param fovHorizontalDegrees Horizontal FOV in degrees.
     * @param viewportWidth Width of viewport in pixels.
     * @param viewportHeight Height of viewport in pixels.
     * @return Vertical FOV in degrees.
     */
    fun computeVerticalFov(
        fovHorizontalDegrees: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Float {
        if (viewportWidth <= 1f || viewportHeight <= 1f) return fovHorizontalDegrees
        val halfAngleRad = fovHorizontalDegrees * 0.5 * DEG_TO_RAD
        val halfAngleVRad = atan(tan(halfAngleRad) * (viewportHeight / viewportWidth))
        return (2.0 * halfAngleVRad * RAD_TO_DEG).toFloat().coerceIn(0.01f, 170.0f)
    }
}

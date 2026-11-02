package com.dazix.caelis.ar

import kotlin.math.atan
import kotlin.math.tan

/**
 * Result of field of view calculation containing the horizontal angle in degrees
 * and whether the calculation was dynamically derived from live Camera2 stream and crop metadata.
 */
data class FovResult(
    val horizontalDegrees: Float,
    val isDynamicStreamCalculated: Boolean,
    val baseHorizontalDegrees: Float = horizontalDegrees,
)

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
     * The lens the preview uses out of `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`: the only one, or on a
     * logical multi-camera (e.g. [1.95, 6.81, 19.0]) the standard wide lens between 3.5 and 8 mm.
     */
    fun primaryFocalLength(focalLengths: FloatArray?): Float? {
        val list = focalLengths ?: return null
        if (list.size == 1) return list[0]
        return list.firstOrNull { it in 3.5f..8.0f } ?: list.firstOrNull()
    }

    /**
     * Effective horizontal FOV computed from what the camera **actually delivers**, not from the
     * lens geometry alone.
     *
     * [computeEffectiveHorizontalFov] assumes the preview shows the whole sensor at the sensor's own
     * aspect ratio. Neither is true on a modern phone: `SENSOR_INFO_PHYSICAL_SIZE` describes the full
     * pixel array, while the stream is produced from a `SCALER_CROP_REGION` inside the active array —
     * narrowed further by preview stabilisation or a default zoom — and at whatever aspect ratio
     * CameraX picked for the `Preview` use case. On a Pixel that made the computed FOV 36.7° where a
     * fit of the terrain skyline against the camera image measured 31.7°: 14 % too wide, which
     * squeezes the whole overlay towards the centre of the frame and cannot be corrected by any
     * amount of panning.
     *
     * @param activeArrayWidthPx width of `SENSOR_INFO_ACTIVE_ARRAY_SIZE` in sensor pixels.
     * @param activeArrayHeightPx height of the same rectangle.
     * @param cropRegionWidthPx width of `CaptureResult.SCALER_CROP_REGION`.
     * @param cropRegionHeightPx height of the same rectangle.
     * @param streamWidthPx width of the buffer the `Preview` use case receives.
     * @param streamHeightPx height of that buffer.
     * @return [FovResult] with the horizontal FOV visible in the viewport and calculation status flag.
     */
    fun computeStreamHorizontalFov(
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        focalLengthMm: Float?,
        activeArrayWidthPx: Int,
        activeArrayHeightPx: Int,
        cropRegionWidthPx: Int,
        cropRegionHeightPx: Int,
        streamWidthPx: Int,
        streamHeightPx: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        isPortrait: Boolean,
        fallbackFovDegrees: Float = DEFAULT_PORTRAIT_FOV_DEGREES,
    ): FovResult {
        val geometryOnly = computeEffectiveHorizontalFov(
            sensorWidthMm,
            sensorHeightMm,
            focalLengthMm,
            viewportWidth,
            viewportHeight,
            isPortrait,
            fallbackFovDegrees,
        )

        val measurable = activeArrayWidthPx > 0 && activeArrayHeightPx > 0 &&
            cropRegionWidthPx > 0 && cropRegionHeightPx > 0 &&
            streamWidthPx > 0 && streamHeightPx > 0 &&
            viewportWidth > 1f && viewportHeight > 1f
        if (!measurable) return FovResult(geometryOnly, isDynamicStreamCalculated = false, baseHorizontalDegrees = geometryOnly)

        val activeShort = minOf(activeArrayWidthPx, activeArrayHeightPx).toDouble()
        val activeLong = maxOf(activeArrayWidthPx, activeArrayHeightPx).toDouble()
        val cropShort = minOf(cropRegionWidthPx, cropRegionHeightPx).toDouble()
        val cropLong = maxOf(cropRegionWidthPx, cropRegionHeightPx).toDouble()

        val cropFractionShort = cropShort / activeShort
        val cropFractionLong = cropLong / activeLong
        // A crop region larger than the array it is measured in is a driver reporting nonsense.
        if (cropFractionShort <= 0.0 || cropFractionShort > 1.01 ||
            cropFractionLong <= 0.0 || cropFractionLong > 1.01
        ) {
            return FovResult(geometryOnly, isDynamicStreamCalculated = false, baseHorizontalDegrees = geometryOnly)
        }

        // Everything below works in tangents, because a crop scales the extent on the sensor plane —
        // which is proportional to tan(halfAngle) — and not the angle itself.
        val rawTanShort = tan(
            computeHorizontalFov(sensorWidthMm, sensorHeightMm, focalLengthMm, true, fallbackFovDegrees) *
                0.5 * DEG_TO_RAD,
        )
        // Pixels are square, so the pixel array's own aspect converts the short half-angle into the
        // long one more reliably than the two millimetre figures do.
        val rawTanLong = rawTanShort * (activeLong / activeShort)

        // 1. Baseline unzoomed stream FOV (1.0x baseline with full active array)
        var baseTanLong = rawTanLong
        var baseTanShort = rawTanShort
        val activeAspect = activeLong / activeShort
        val streamAspect = maxOf(streamWidthPx, streamHeightPx).toDouble() /
            minOf(streamWidthPx, streamHeightPx).toDouble()
        if (streamAspect >= activeAspect) {
            baseTanShort = baseTanLong / streamAspect
        } else {
            baseTanLong = baseTanShort * streamAspect
        }
        val baseTanHorizontal = if (isPortrait) baseTanShort else baseTanLong
        val baseTanVertical = if (isPortrait) baseTanLong else baseTanShort
        val viewportAspect = (viewportWidth / viewportHeight).toDouble()
        val baseStreamAspectOnScreen = baseTanHorizontal / baseTanVertical
        val baseTanVisible = if (viewportAspect < baseStreamAspectOnScreen) {
            baseTanVertical * viewportAspect
        } else {
            baseTanHorizontal
        }
        val baseFov = (2.0 * atan(baseTanVisible) * RAD_TO_DEG).toFloat().coerceIn(1.0f, 150.0f)

        // 2. Dynamic stream FOV with real-time crop region applied
        var tanLong = rawTanLong * cropFractionLong
        var tanShort = rawTanShort * cropFractionShort

        // The stream is a centre crop of the crop region: whichever axis is relatively longer in the
        // stream survives whole and the other one is trimmed to match.
        val cropAspect = cropLong / cropShort
        if (streamAspect >= cropAspect) {
            tanShort = tanLong / streamAspect
        } else {
            tanLong = tanShort * streamAspect
        }

        val tanHorizontal = if (isPortrait) tanShort else tanLong
        val tanVertical = if (isPortrait) tanLong else tanShort
        if (tanVertical <= 0.0) return FovResult(geometryOnly, isDynamicStreamCalculated = false, baseHorizontalDegrees = geometryOnly)

        // PreviewView FILL_CENTER scales the stream until it covers the viewport, so the axis that
        // stays whole is the one the viewport is relatively longer in.
        val streamAspectOnScreen = tanHorizontal / tanVertical
        val tanVisible = if (viewportAspect < streamAspectOnScreen) {
            tanVertical * viewportAspect
        } else {
            tanHorizontal
        }

        val fov = (2.0 * atan(tanVisible) * RAD_TO_DEG).toFloat().coerceIn(1.0f, 150.0f)
        return FovResult(
            horizontalDegrees = fov,
            isDynamicStreamCalculated = true,
            baseHorizontalDegrees = baseFov,
        )
    }

    /**
     * Computes the effective focal length in pixels for perspective projection matching PreviewView FILL_CENTER.
     *
     * @param effectiveFovHorizontalDegrees Effective horizontal field of view in degrees.
     * @param viewportWidth Width of the preview viewport in pixels.
     * @param zoomFactor Total magnification factor (defaults to 1.0f).
     * @return Focal length in screen pixels.
     */
    fun computeEffectiveFocalLengthPx(
        effectiveFovHorizontalDegrees: Float,
        viewportWidth: Float,
        zoomFactor: Float = 1.0f,
    ): Float {
        val halfAngleRad = effectiveFovHorizontalDegrees * 0.5 * DEG_TO_RAD
        val baseFocalLength = ((viewportWidth * 0.5f) / tan(halfAngleRad)).toFloat()
        return baseFocalLength * zoomFactor
    }

    /**
     * Computes the effective focal length in pixels from a [FovResult].
     */
    fun computeEffectiveFocalLengthPx(
        fov: FovResult,
        viewportWidth: Float,
        zoomFactor: Float = 1.0f,
    ): Float = computeEffectiveFocalLengthPx(fov.baseHorizontalDegrees, viewportWidth, zoomFactor)

    /**
     * Calculates the zoomed horizontal Field of View visible in the viewport given a total zoom factor,
     * using exact physical lens optics:
     * $$\text{FOV}_{\text{zoomed}} = 2 \cdot \arctan\left(\frac{\tan(\text{FOV}_{\text{base}} / 2)}{\text{zoomFactor}}\right)$$
     *
     * @param baseEffectiveFovDegrees Base effective horizontal FOV at 1.0x zoom.
     * @param zoomFactor Total magnification factor (>= 0.0001x).
     * @return Narrowed horizontal FOV in degrees matching strict focal length scaling.
     */
    fun computeZoomedFov(
        baseEffectiveFovDegrees: Float,
        zoomFactor: Float,
    ): Float {
        val safeZoom = zoomFactor.coerceAtLeast(0.0001f)
        val safeBaseFov = baseEffectiveFovDegrees.coerceIn(0.1f, 160.0f)
        val halfAngleRad = safeBaseFov * 0.5 * DEG_TO_RAD
        val zoomedHalfAngleRad = atan(tan(halfAngleRad) / safeZoom.toDouble())
        return (2.0 * zoomedHalfAngleRad * RAD_TO_DEG).toFloat().coerceIn(0.0005f, 160.0f)
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

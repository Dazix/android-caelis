package com.dazix.caelis.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFovCalculatorTest {

    @Test
    fun `calculates standard wide lens FOV in portrait accurately`() {
        val fov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            isPortrait = true,
        )

        assertEquals(57.44f, fov, 0.5f)
    }

    @Test
    fun `calculates standard wide lens FOV in landscape accurately`() {
        val fov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            isPortrait = false,
        )

        assertEquals(72.3f, fov, 0.5f)
    }

    @Test
    fun `calculates ultrawide lens FOV accurately`() {
        val fov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 1.95f,
            isPortrait = true,
        )

        assertEquals(101.8f, fov, 0.8f)
    }

    @Test
    fun `calculates telephoto 5x lens FOV accurately`() {
        val fov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 5.6f,
            sensorHeightMm = 4.2f,
            focalLengthMm = 19.0f,
            isPortrait = true,
        )

        assertEquals(12.6f, fov, 0.5f)
    }

    @Test
    fun `calculates effective FOV with 20 to 9 screen crop factor accurately`() {
        // Sensor 4:3 in portrait (4.8mm wide, 6.4mm high -> aspect 0.75)
        // Screen 1080x2400 (aspect 0.45)
        // Crop ratio = 0.45 / 0.75 = 0.60
        // Raw FOV = 57.44° -> half angle = 28.72°, tan = 0.548
        // Effective tan = 0.548 * 0.60 = 0.3288 -> atan = 18.2° -> effective FOV = 36.4°
        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            isPortrait = true,
        )

        assertEquals(36.4f, effectiveFov, 0.6f)
    }

    @Test
    fun `effective FOV matches raw FOV when aspect ratio matches sensor`() {
        val rawFov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            isPortrait = true,
        )

        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 1080f,
            viewportHeight = 1440f, // Exact 4:3 aspect
            isPortrait = true,
        )

        assertEquals(rawFov, effectiveFov, 0.01f)
    }

    @Test
    fun `calculates effective FOV with 19_5 to 9 screen crop factor accurately`() {
        // Screen 1080x2340 (aspect 0.4615) on 4:3 sensor (aspect 0.75)
        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            isPortrait = true,
        )

        assertEquals(37.27f, effectiveFov, 0.6f)
    }

    @Test
    fun `calculates effective FOV with 16 to 9 screen crop factor accurately`() {
        // Screen 1080x1920 (aspect 0.5625) on 4:3 sensor (aspect 0.75)
        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 1080f,
            viewportHeight = 1920f,
            isPortrait = true,
        )

        assertEquals(44.69f, effectiveFov, 0.6f)
    }

    @Test
    fun `calculates effective FOV in landscape orientation on wide screen`() {
        // Screen 2400x1080 in landscape (aspect 2.22) on 4:3 sensor in landscape (aspect 1.333)
        // Viewport is wider than sensor aspect, so horizontal FOV is uncropped
        val rawLandscapeFov = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            isPortrait = false,
        )
        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 2400f,
            viewportHeight = 1080f,
            isPortrait = false,
        )

        assertEquals(rawLandscapeFov, effectiveFov, 0.01f)
    }

    @Test
    fun `falls back gracefully when sensor dimensions are missing`() {
        val fovPortrait = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = null,
            sensorHeightMm = null,
            focalLengthMm = null,
            isPortrait = true,
            fallbackFovDegrees = 56f,
        )
        assertEquals(56f, fovPortrait, 0.001f)

        val fovLandscape = CameraFovCalculator.computeHorizontalFov(
            sensorWidthMm = null,
            sensorHeightMm = null,
            focalLengthMm = null,
            isPortrait = false,
            fallbackFovDegrees = 56f,
        )
        assertEquals(56f * 1.333f, fovLandscape, 0.01f)
    }

    @Test
    fun `effective FOV applies 4 to 3 sensor aspect crop when sensor dimensions are missing`() {
        // Fallback 56° raw portrait FOV on 1080x2400 screen (aspect 0.45) with default 4:3 sensor (aspect 0.75 in portrait)
        // Crop ratio = 0.45 / 0.75 = 0.60
        // tan(28°) * 0.60 = 0.5317 * 0.60 = 0.3190 -> atan = 17.69° -> effective FOV = 35.38°
        val effectiveFov = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = null,
            sensorHeightMm = null,
            focalLengthMm = null,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            isPortrait = true,
            fallbackFovDegrees = 56f,
        )

        assertEquals(35.38f, effectiveFov, 0.5f)
    }

    @Test
    fun `calculates effective focal length in pixels accurately`() {
        val effectiveFov = 35.38f
        val viewportW = 1080f
        val focalLengthPx = CameraFovCalculator.computeEffectiveFocalLengthPx(effectiveFov, viewportW)

        // focalLengthPx = 540 / tan(35.38° / 2) = 540 / tan(17.69°) = 540 / 0.3190 = 1692.8 px
        assertEquals(1692.8f, focalLengthPx, 5.0f)

        val fovResult = FovResult(effectiveFov, isDynamicStreamCalculated = true, baseHorizontalDegrees = effectiveFov)
        val focalLengthPxFromResult = CameraFovCalculator.computeEffectiveFocalLengthPx(fovResult, viewportW)
        assertEquals(1692.8f, focalLengthPxFromResult, 5.0f)

        // Verifies zoomFactor directly scales focal length
        val zoomedFocalLengthPx = CameraFovCalculator.computeEffectiveFocalLengthPx(fovResult, viewportW, zoomFactor = 2.5f)
        assertEquals(1692.8f * 2.5f, zoomedFocalLengthPx, 10.0f)
    }

    /**
     * The case that was measured on the device: the overlay was drawn at 36.7° while fitting the
     * terrain profile to the camera image put the real field of view at about 32°. The difference is
     * a sensor crop the app never asked about.
     */
    @Test
    fun `a sensor crop region narrows the field of view the geometry alone predicts`() {
        val withoutCrop = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3072,
            cropRegionWidthPx = 4080,
            cropRegionHeightPx = 3072,
            streamWidthPx = 4032,
            streamHeightPx = 3024,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            isPortrait = true,
        )
        val withCrop = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3072,
            cropRegionWidthPx = 3492, // 0.856 of the active array, the factor measured on the device
            cropRegionHeightPx = 2630,
            streamWidthPx = 4032,
            streamHeightPx = 3024,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            isPortrait = true,
        )

        assertTrue(withoutCrop.isDynamicStreamCalculated)
        assertTrue(withCrop.isDynamicStreamCalculated)
        assertEquals(37.27f, withoutCrop.horizontalDegrees, 0.3f)
        assertEquals(32.1f, withCrop.horizontalDegrees, 0.3f)
        // Baseline 1.0x unzoomed FOV remains invariant across crop changes
        assertEquals(37.27f, withoutCrop.baseHorizontalDegrees, 0.3f)
        assertEquals(37.27f, withCrop.baseHorizontalDegrees, 0.3f)
        // The crop scales the tangent of the half angle, not the angle.
        val ratio = tanHalf(withCrop.horizontalDegrees) / tanHalf(withoutCrop.horizontalDegrees)
        assertEquals(0.856f, ratio, 0.005f)
    }

    /**
     * The stream's aspect ratio is not the answer on its own. FILL_CENTER on a tall phone scales the
     * stream until it covers the viewport, so what survives is the long axis; a 16:9 buffer and a 4:3
     * buffer off the same sensor show the same horizontal field of view.
     */
    @Test
    fun `a 16 to 9 stream and a 4 to 3 stream give the same horizontal FOV on a tall viewport`() {
        fun fovFor(streamW: Int, streamH: Int) = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3072,
            cropRegionWidthPx = 4080,
            cropRegionHeightPx = 3072,
            streamWidthPx = streamW,
            streamHeightPx = streamH,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            isPortrait = true,
        ).horizontalDegrees

        assertEquals(fovFor(4032, 3024), fovFor(1920, 1080), 0.15f)
    }

    @Test
    fun `a stream narrower than the viewport keeps its own width instead of inventing one`() {
        // A 4:3 stream shown in a 4:3 viewport: nothing is cropped, so the answer is the raw FOV.
        val fov = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3060,
            cropRegionWidthPx = 4080,
            cropRegionHeightPx = 3060,
            streamWidthPx = 4032,
            streamHeightPx = 3024,
            viewportWidth = 1080f,
            viewportHeight = 1440f,
            isPortrait = true,
        )

        assertTrue(fov.isDynamicStreamCalculated)
        assertEquals(57.44f, fov.horizontalDegrees, 0.4f)
    }

    @Test
    fun `landscape reads the crop across the sensor long axis`() {
        val fov = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3060,
            cropRegionWidthPx = 2040, // half the array in each direction
            cropRegionHeightPx = 1530,
            streamWidthPx = 4032,
            streamHeightPx = 3024,
            viewportWidth = 2400f,
            viewportHeight = 1080f,
            isPortrait = false,
        )

        assertTrue(fov.isDynamicStreamCalculated)
        // Landscape raw FOV is 72.3°; halving the sensor extent halves the tangent.
        assertEquals(0.5f, tanHalf(fov.horizontalDegrees) / tanHalf(72.3f), 0.01f)
    }

    /**
     * Not every device reports a crop region, and a driver that reports a broken one must not be
     * trusted over the geometry. Either way the answer has to stay the one the app shipped with.
     */
    @Test
    fun `missing or impossible measurements fall back to the geometry only answer`() {
        val geometry = CameraFovCalculator.computeEffectiveHorizontalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.38f,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            isPortrait = true,
        )

        fun fovWith(activeW: Int, activeH: Int, cropW: Int, cropH: Int, streamW: Int, streamH: Int) =
            CameraFovCalculator.computeStreamHorizontalFov(
                sensorWidthMm = 6.4f,
                sensorHeightMm = 4.8f,
                focalLengthMm = 4.38f,
                activeArrayWidthPx = activeW,
                activeArrayHeightPx = activeH,
                cropRegionWidthPx = cropW,
                cropRegionHeightPx = cropH,
                streamWidthPx = streamW,
                streamHeightPx = streamH,
                viewportWidth = 1080f,
                viewportHeight = 2340f,
                isPortrait = true,
            )

        val res0 = fovWith(0, 0, 0, 0, 0, 0)
        assertFalse(res0.isDynamicStreamCalculated)
        assertEquals(geometry, res0.horizontalDegrees, 0.001f)

        val res1 = fovWith(4080, 3072, 0, 0, 4032, 3024)
        assertFalse(res1.isDynamicStreamCalculated)
        assertEquals(geometry, res1.horizontalDegrees, 0.001f)

        val res2 = fovWith(4080, 3072, 4080, 3072, 0, 0)
        assertFalse(res2.isDynamicStreamCalculated)
        assertEquals(geometry, res2.horizontalDegrees, 0.001f)

        val res3 = fovWith(4080, 3072, -10, -10, 4032, 3024)
        assertFalse(res3.isDynamicStreamCalculated)
        assertEquals(geometry, res3.horizontalDegrees, 0.001f)

        // A crop region bigger than the array it lives in is not a wider lens.
        val res4 = fovWith(4080, 3072, 8000, 6000, 4032, 3024)
        assertFalse(res4.isDynamicStreamCalculated)
        assertEquals(geometry, res4.horizontalDegrees, 0.001f)
    }

    @Test
    fun `falls back to the default FOV when neither sensor nor crop is known`() {
        val fov = CameraFovCalculator.computeStreamHorizontalFov(
            sensorWidthMm = null,
            sensorHeightMm = null,
            focalLengthMm = null,
            activeArrayWidthPx = 0,
            activeArrayHeightPx = 0,
            cropRegionWidthPx = 0,
            cropRegionHeightPx = 0,
            streamWidthPx = 0,
            streamHeightPx = 0,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            isPortrait = true,
            fallbackFovDegrees = 56f,
        )

        assertFalse(fov.isDynamicStreamCalculated)
        assertEquals(35.38f, fov.horizontalDegrees, 0.5f)
    }

    private fun tanHalf(fovDegrees: Float): Float =
        kotlin.math.tan(fovDegrees * 0.5f * (Math.PI / 180.0).toFloat())

    @Test
    fun `calculates zoomed FOV correctly using exact optical tangent lens geometry`() {
        val baseFov = 56.0f
        val w = 1080f
        val baseFocalLengthPx = (w * 0.5f) / kotlin.math.tan(Math.toRadians(baseFov.toDouble() / 2.0)).toFloat()

        // 1.0x zoom
        val fov1x = CameraFovCalculator.computeZoomedFov(baseFov, 1.0f)
        assertEquals(56.0f, fov1x, 0.001f)
        val focalLength1x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov1x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 1.0f, focalLength1x, 0.01f)

        // 2.0x zoom: tan(fov/2) must be exactly tan(baseFov/2) / 2
        val fov2x = CameraFovCalculator.computeZoomedFov(baseFov, 2.0f)
        assertEquals(29.77f, fov2x, 0.05f)
        val focalLength2x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov2x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 2.0f, focalLength2x, 0.01f)

        // 10.0x zoom
        val fov10x = CameraFovCalculator.computeZoomedFov(baseFov, 10.0f)
        assertEquals(6.09f, fov10x, 0.05f)
        val focalLength10x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov10x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 10.0f, focalLength10x, 0.05f)

        // 50.0x zoom
        val fov50x = CameraFovCalculator.computeZoomedFov(baseFov, 50.0f)
        val focalLength50x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov50x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 50.0f, focalLength50x, 0.1f)

        // 100.0x zoom
        val fov100x = CameraFovCalculator.computeZoomedFov(baseFov, 100.0f)
        val focalLength100x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov100x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 100.0f, focalLength100x, 0.2f)

        // 2500.0x deep planetarium zoom
        val fov2500x = CameraFovCalculator.computeZoomedFov(baseFov, 2500.0f)
        val focalLength2500x = (w * 0.5f) / kotlin.math.tan(Math.toRadians(fov2500x.toDouble() / 2.0)).toFloat()
        assertEquals(baseFocalLengthPx * 2500.0f, focalLength2500x, 5.0f)
    }

    /** CameraPreview and the camera-less prediction (stillBackCameraFov) must pick the same lens. */
    @Test
    fun `the primary lens is the only one, or the standard wide one of a multi-camera`() {
        assertEquals(4.38f, CameraFovCalculator.primaryFocalLength(floatArrayOf(4.38f))!!, 0f)
        assertEquals(6.81f, CameraFovCalculator.primaryFocalLength(floatArrayOf(1.95f, 6.81f, 19.0f))!!, 0f)
        assertEquals(2.2f, CameraFovCalculator.primaryFocalLength(floatArrayOf(2.2f, 15f))!!, 0f)
        assertEquals(null, CameraFovCalculator.primaryFocalLength(null))
    }
}

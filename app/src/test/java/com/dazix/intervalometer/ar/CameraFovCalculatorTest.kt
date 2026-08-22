package com.dazix.intervalometer.ar

import org.junit.Assert.assertEquals
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
    fun `calculates zoomed FOV correctly and clamps within valid bounds`() {
        val baseFov = 56.0f

        assertEquals(56.0f, CameraFovCalculator.computeZoomedFov(baseFov, 1.0f), 0.001f)
        assertEquals(28.0f, CameraFovCalculator.computeZoomedFov(baseFov, 2.0f), 0.001f)
        assertEquals(14.0f, CameraFovCalculator.computeZoomedFov(baseFov, 4.0f), 0.001f)
        assertEquals(5.6f, CameraFovCalculator.computeZoomedFov(baseFov, 10.0f), 0.001f)

        // Extreme zoom clamping
        assertEquals(2.0f, CameraFovCalculator.computeZoomedFov(baseFov, 50.0f), 0.001f)
    }
}

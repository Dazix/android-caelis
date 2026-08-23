package com.dazix.intervalometer.ar

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProjectionTest {

    private val screenW = 1080f
    private val screenH = 2400f
    private val fovH = 56f

    // Device held upright in portrait pointing North:
    // Col 0: Physical X (+X_dev) is East (+X_w)  -> [1, 0, 0]
    // Col 1: Physical Y (+Y_dev) is Up (+Z_w)    -> [0, 0, 1]
    // Col 2: Physical Z (+Z_dev) is South (-Y_w) -> [0, -1, 0]
    private val northPortraitMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `verifies point directly in center of camera view projects to screen center`() {
        // Point is at Azimuth 0°, Elevation 0° (due North on horizon)
        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 1.0f)
        assertEquals(screenH / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies point to the East projects to the right of screen center`() {
        // Point is at Azimuth 15° (East of North), Elevation 0°
        val proj = CameraProjection.project(
            azimuthDegrees = 15.0,
            elevationDegrees = 0.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertTrue("X should be right of center, got ${proj.x}", proj.x > screenW / 2f)
        assertEquals(screenH / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies point above horizon projects with Y smaller than center (screen Y is down)`() {
        // Point is at Azimuth 0°, Elevation 20° (above horizon)
        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 20.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 1.0f)
        assertTrue("Y should be above center (smaller value), got ${proj.y}", proj.y < screenH / 2f)
    }

    @Test
    fun `verifies Zenith (+90° elevation) projects to center when phone is pointed up at the sky`() {
        // Phone tilted 90° up to look at zenith:
        // Col 0: Physical X is East (+X_w) -> [1, 0, 0]
        // Col 1: Physical Y is South (-Y_w)-> [0, -1, 0]
        // Col 2: Physical Z is Down (-Z_w) -> [0, 0, -1]
        // Back camera (-Z_dev) is Up (+Z_w)
        val zenithMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 90.0,
            rotationMatrix = zenithMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 1.0f)
        assertEquals(screenH / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies landscape rotation (ROTATION_90)`() {
        // Phone in landscape pointing North:
        // Col 0: Physical X (right of phone) is Up (+Z_w)   -> [0, 0, 1]
        // Col 1: Physical Y (top of phone) is West (-X_w)  -> [-1, 0, 0]
        // Col 2: Physical Z (screen) is South (-Y_w)        -> [0, -1, 0]
        val landscapeNorthMatrix = floatArrayOf(
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = landscapeNorthMatrix,
            viewportWidth = 2400f,
            viewportHeight = 1080f,
            displayRotation = Surface.ROTATION_90,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(2400f / 2f, proj.x, 1.0f)
        assertEquals(1080f / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies point behind camera calculates bearing for off-screen radar`() {
        // Point is at Azimuth 180° (due South, behind camera facing North)
        val proj = CameraProjection.project(
            azimuthDegrees = 180.0,
            elevationDegrees = 0.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertFalse(proj.isInFront)
        assertFalse(proj.isVisible)
        assertNotNull(proj.offscreenBearingDegrees)
    }

    @Test
    fun `verifies reverse landscape rotation (ROTATION_270)`() {
        // Phone in landscape with top to the right pointing North:
        // Col 0: Physical X (right of phone) is Down (-Z_w) -> [0, 0, -1]
        // Col 1: Physical Y (top of phone) is East (+X_w)  -> [1, 0, 0]
        // Col 2: Physical Z (screen) is South (-Y_w)        -> [0, -1, 0]
        val landscape270Matrix = floatArrayOf(
            0f, 1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = landscape270Matrix,
            viewportWidth = 2400f,
            viewportHeight = 1080f,
            displayRotation = Surface.ROTATION_270,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(2400f / 2f, proj.x, 1.0f)
        assertEquals(1080f / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies upside down portrait rotation (ROTATION_180)`() {
        // Phone held upside down pointing North:
        // Col 0: Physical X (+X_dev) is West (-X_w)  -> [-1, 0, 0]
        // Col 1: Physical Y (+Y_dev) is Down (-Z_w)  -> [0, 0, -1]
        // Col 2: Physical Z (+Z_dev) is South (-Y_w) -> [0, -1, 0]
        val upsideDownMatrix = floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = upsideDownMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_180,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 1.0f)
        assertEquals(screenH / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies projectFast produces identical coordinates to project`() {
        val outBuf = FloatArray(3)
        val inFront = CameraProjection.projectFast(
            azimuthDegrees = 15.0,
            elevationDegrees = 20.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
            outBuffer = outBuf,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 15.0,
            elevationDegrees = 20.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(inFront)
        assertEquals(proj.isInFront, inFront)
        assertEquals(proj.x, outBuf[0], 0.001f)
        assertEquals(proj.y, outBuf[1], 0.001f)
        assertEquals(proj.zCamera, outBuf[2], 0.001f)
    }

    @Test
    fun `verifies 3x3 rotation matrix support`() {
        val matrix3x3 = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = matrix3x3,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 1.0f)
        assertEquals(screenH / 2f, proj.y, 1.0f)
    }

    @Test
    fun `verifies offscreen bearing directions for all 4 quadrants`() {
        // When facing North:
        // East (+90° az) is to the Right -> bearing ~90°
        val eastProj = CameraProjection.project(90.0, 0.0, northPortraitMatrix, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(eastProj.isVisible)
        assertEquals(90f, eastProj.offscreenBearingDegrees ?: 0f, 2f)

        // West (270° az) is to the Left -> bearing ~270°
        val westProj = CameraProjection.project(270.0, 0.0, northPortraitMatrix, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(westProj.isVisible)
        assertEquals(270f, westProj.offscreenBearingDegrees ?: 0f, 2f)

        // High above camera (+70° elev) -> bearing ~0° (Up)
        val upProj = CameraProjection.project(0.0, 70.0, northPortraitMatrix, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(upProj.isVisible)
        assertEquals(0f, upProj.offscreenBearingDegrees ?: 0f, 2f)

        // Low below camera (-70° elev) -> bearing ~180° (Down)
        val downProj = CameraProjection.project(0.0, -70.0, northPortraitMatrix, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(downProj.isVisible)
        assertEquals(180f, downProj.offscreenBearingDegrees ?: 0f, 2f)
    }

    @Test
    fun `verifies diagonal panning with simultaneous pitch and yaw projects center landmark to exact screen center`() {
        // Construct rotation matrix for camera pointing at Azimuth 25°, Elevation 20°
        // In this diagonal orientation, a celestial landmark at (25°, 20°) must project to exact screen center (W/2, H/2).
        val azRad = Math.toRadians(25.0)
        val elRad = Math.toRadians(20.0)

        // Optical axis of camera in ENU: (sin(az)*cos(el), cos(az)*cos(el), sin(el))
        // Screen Right (+X_dev) is perpendicular to optical axis and horizontal (in xy plane): (cos(az), -sin(az), 0)
        // Screen Up (+Y_dev) = Z_dev x X_dev (cross product)
        val fwdX = (kotlin.math.sin(azRad) * kotlin.math.cos(elRad)).toFloat()
        val fwdY = (kotlin.math.cos(azRad) * kotlin.math.cos(elRad)).toFloat()
        val fwdZ = kotlin.math.sin(elRad).toFloat()

        // Col 2 (+Z_dev) is backwards (-fwd)
        val zX = -fwdX
        val zY = -fwdY
        val zZ = -fwdZ

        // Col 0 (+X_dev): right vector (cos(az), -sin(az), 0)
        val xX = kotlin.math.cos(azRad).toFloat()
        val xY = -kotlin.math.sin(azRad).toFloat()
        val xZ = 0f

        // Col 1 (+Y_dev) = +Z_dev cross +X_dev
        val yX = zY * xZ - zZ * xY
        val yY = zZ * xX - zX * xZ
        val yZ = zX * xY - zY * xX

        val diagonalMatrix = floatArrayOf(
            xX, yX, zX, 0f,
            xY, yY, zY, 0f,
            xZ, yZ, zZ, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(
            azimuthDegrees = 25.0,
            elevationDegrees = 20.0,
            rotationMatrix = diagonalMatrix,
            viewportWidth = screenW,
            viewportHeight = screenH,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = fovH,
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isVisible)
        assertEquals(screenW / 2f, proj.x, 0.5f)
        assertEquals(screenH / 2f, proj.y, 0.5f)
    }
}

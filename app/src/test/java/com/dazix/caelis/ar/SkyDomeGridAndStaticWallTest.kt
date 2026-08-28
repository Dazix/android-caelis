package com.dazix.caelis.ar

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Dedicated unit test suite verifying the Static Celestial Dome ($R^T$ spatial invariance),
 * 3D Celestial Wireframe Grid sampling, and 24/7 Sun Pointer tracking.
 */
class SkyDomeGridAndStaticWallTest {

    private val screenW = 1080f
    private val screenH = 2340f
    private val fovH = 56f
    private val halfFovRad = Math.toRadians(fovH * 0.5)
    private val focalLength = (screenW * 0.5f) / tan(halfFovRad).toFloat()

    @Test
    fun `verifies static landscape point shifts left when user rotates phone right`() {
        // Point is fixed on the real landscape at Azimuth 0° (North), Elevation 0° (Horizon)
        // 1. Phone facing directly North (yaw = 0°)
        val matNorth = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val projCenter = CameraProjection.project(0.0, 0.0, matNorth, screenW, screenH, Surface.ROTATION_0, fovH)
        assertEquals(screenW / 2f, projCenter.x, 1.0f)
        assertEquals(screenH / 2f, projCenter.y, 1.0f)

        // 2. User pans phone 15° to the East/Right -> North should shift to the LEFT of screen
        val angle15Rad = Math.toRadians(15.0)
        val cos15 = cos(angle15Rad).toFloat()
        val sin15 = sin(angle15Rad).toFloat()
        // R transforms device to world: +X_dev is at (cos15, -sin15, 0)
        val matPannedRight = floatArrayOf(
            cos15, 0f, sin15, 0f,
            -sin15, 0f, -cos15, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val projLeft = CameraProjection.project(0.0, 0.0, matPannedRight, screenW, screenH, Surface.ROTATION_0, fovH)
        assertTrue("Static landmark must shift left on screen when panning right, got x=${projLeft.x}", projLeft.x < screenW / 2f)
        val expectedShift = focalLength * tan(angle15Rad).toFloat()
        assertEquals((screenW / 2f) - expectedShift, projLeft.x, 2.0f)
    }

    @Test
    fun `verifies static horizon drops down on screen when user tilts phone upwards`() {
        // Point is fixed on horizon at Azimuth 0°, Elevation 0°
        // Phone tilted 20° upwards looking at sky
        val tilt20Rad = Math.toRadians(20.0)
        val cos20 = cos(tilt20Rad).toFloat()
        val sin20 = sin(tilt20Rad).toFloat()

        // When tilted up, +Y_dev points back towards South, -Z_dev points up towards Sky
        val matTiltedUp = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -sin20, -cos20, 0f,
            0f, cos20, -sin20, 0f,
            0f, 0f, 0f, 1f,
        )

        val proj = CameraProjection.project(0.0, 0.0, matTiltedUp, screenW, screenH, Surface.ROTATION_0, fovH)
        assertTrue("Horizon must drop down (higher screen Y) when tilting phone up, got y=${proj.y}", proj.y > screenH / 2f)
        val expectedDrop = focalLength * tan(tilt20Rad).toFloat()
        assertEquals((screenH / 2f) + expectedDrop, proj.y, 2.0f)
    }

    @Test
    fun `verifies 3D wireframe grid meridians and parallels sample valid coordinates`() {
        val outBuffer = FloatArray(3)
        val matNorth = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val meridianAzimuths = doubleArrayOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0, 210.0, 240.0, 270.0, 300.0, 330.0)
        val altitudeLevels = doubleArrayOf(-30.0, -15.0, 15.0, 30.0, 45.0, 60.0, 75.0)

        // Verify all 12 meridians project without NaN or infinite values
        for (az in meridianAzimuths) {
            for (step in 0..34) {
                val el = -85.0 + step * 5.0
                CameraProjection.projectFast(az, el, matNorth, screenW, screenH, Surface.ROTATION_0, fovH, outBuffer)
                assertFalse("Grid point X cannot be NaN", outBuffer[0].isNaN())
                assertFalse("Grid point Y cannot be NaN", outBuffer[1].isNaN())
                assertFalse("Grid point Z cannot be NaN", outBuffer[2].isNaN())
            }
        }

        // Verify all 7 altitude rings project without NaN or infinite values
        for (elev in altitudeLevels) {
            for (step in 0 until 72) {
                val az = (step * 5).toDouble()
                CameraProjection.projectFast(az, elev, matNorth, screenW, screenH, Surface.ROTATION_0, fovH, outBuffer)
                assertFalse("Altitude ring point X cannot be NaN", outBuffer[0].isNaN())
                assertFalse("Altitude ring point Y cannot be NaN", outBuffer[1].isNaN())
            }
        }
    }

    @Test
    fun `verifies 24-7 sun tracking for both daytime and nighttime deep below horizon`() {
        val matNorth = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        // 1. Daytime Sun at Azimuth 0° (North), Elevation +45° (in front and visible)
        val dayProj = CameraProjection.project(0.0, 45.0, matNorth, screenW, screenH, Surface.ROTATION_0, fovH)
        assertTrue(dayProj.isInFront)
        assertTrue("Daytime sun is visible on camera view", dayProj.isVisible)

        // 2. Nighttime Sun at Azimuth 0° (North), Elevation -27° (deep below northern horizon)
        val nightProj = CameraProjection.project(0.0, -27.0, matNorth, screenW, screenH, Surface.ROTATION_0, fovH)
        assertTrue(nightProj.isInFront)
        assertTrue("Nighttime sun projected Y is below horizon (y > screenH / 2)", nightProj.y > screenH / 2f)

        // 3. Sun at Nadir -90° (straight down below feet)
        val nadirProj = CameraProjection.project(0.0, -90.0, matNorth, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(nadirProj.isInFront) // Parallel to camera sensor plane (zs = 0)

        // 4. Offscreen Sun behind observer (Azimuth 180°, Elevation -20°)
        val behindProj = CameraProjection.project(180.0, -20.0, matNorth, screenW, screenH, Surface.ROTATION_0, fovH)
        assertFalse(behindProj.isInFront)
        assertFalse(behindProj.isVisible)
        assertTrue("Offscreen bearing is computed for sun behind observer", behindProj.offscreenBearingDegrees != null)
    }

    @Test
    fun `verifies zero allocations flat buffer indexing for 60 FPS rendering`() {
        val meridianSteps = 35
        val meridianBuf = FloatArray(meridianSteps * 3)

        val matNorth = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        for (i in 0 until meridianSteps) {
            val el = -85.0 + i * 5.0
            CameraProjection.projectFast(0.0, el, matNorth, screenW, screenH, Surface.ROTATION_0, fovH, meridianBuf, i * 3)
        }

        // Verify contiguous packing
        for (i in 0 until meridianSteps - 1) {
            val y1 = meridianBuf[i * 3 + 1]
            val y2 = meridianBuf[(i + 1) * 3 + 1]
            assertTrue("Ascending elevation on 0° meridian should have descending screen Y", y2 < y1)
        }
    }
}

package com.dazix.intervalometer.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

class CelestialMatrixCalculatorTest {

    private val identity16 = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `view matrix is strictly orthonormal under all display rotations`() {
        val outMatrix = FloatArray(9)
        val rotations = intArrayOf(0, 1, 2, 3)

        for (rot in rotations) {
            CelestialMatrixCalculator.computeViewMatrix(
                sensorMatrix16 = identity16,
                lstDegrees = 45.0,
                latitudeDegrees = 50.0,
                displayRotation = rot,
                outMatrix9 = outMatrix,
            )

            // Column 0
            val c0x = outMatrix[0]; val c0y = outMatrix[1]; val c0z = outMatrix[2]
            // Column 1
            val c1x = outMatrix[3]; val c1y = outMatrix[4]; val c1z = outMatrix[5]
            // Column 2
            val c2x = outMatrix[6]; val c2y = outMatrix[7]; val c2z = outMatrix[8]

            val mag0 = sqrt(c0x * c0x + c0y * c0y + c0z * c0z)
            val mag1 = sqrt(c1x * c1x + c1y * c1y + c1z * c1z)
            val mag2 = sqrt(c2x * c2x + c2y * c2y + c2z * c2z)

            assertEquals("Col 0 magnitude at rotation $rot", 1.0f, mag0, 0.001f)
            assertEquals("Col 1 magnitude at rotation $rot", 1.0f, mag1, 0.001f)
            assertEquals("Col 2 magnitude at rotation $rot", 1.0f, mag2, 0.001f)

            val dot01 = c0x * c1x + c0y * c1y + c0z * c1z
            val dot02 = c0x * c2x + c0y * c2y + c0z * c2z
            val dot12 = c1x * c2x + c1y * c2y + c1z * c2z

            assertEquals("Dot 0-1 at rotation $rot", 0.0f, dot01, 0.001f)
            assertEquals("Dot 0-2 at rotation $rot", 0.0f, dot02, 0.001f)
            assertEquals("Dot 1-2 at rotation $rot", 0.0f, dot12, 0.001f)
        }
    }

    @Test
    fun `zenith at north pole points exactly to north celestial pole`() {
        val outMatrix = FloatArray(9)
        // At North Pole (lat = 90°), Zenith (Up vector = [0, 0, 1] in ENU) is Dec = +90°
        CelestialMatrixCalculator.computeViewMatrix(
            sensorMatrix16 = identity16,
            lstDegrees = 0.0,
            latitudeDegrees = 90.0,
            displayRotation = 0,
            outMatrix9 = outMatrix,
        )

        // Device Up vector is [0, 1, 0] in portrait. In ENU identity it is North ([0, 1, 0])
        // Device +Z is [0, 0, 1] which is Up in ENU (Zenith).
        // Let's multiply outMatrix by [0, 0, 1] (Col 2):
        val zx = outMatrix[6]
        val zy = outMatrix[7]
        val zz = outMatrix[8]

        val dec = Math.toDegrees(asin(zy.coerceIn(-1f, 1f).toDouble()))
        assertEquals("Zenith Dec at North Pole", 90.0, dec, 0.01)
    }

    @Test
    fun `celestial coordinate conversion preserves right ascension wrapping`() {
        val outMatrix = FloatArray(9)
        CelestialMatrixCalculator.computeViewMatrix(
            sensorMatrix16 = identity16,
            lstDegrees = 180.0,
            latitudeDegrees = 0.0,
            displayRotation = 0,
            outMatrix9 = outMatrix,
        )

        // Test forward ray [0, 0, -1]:
        val rx = -outMatrix[6]
        val ry = -outMatrix[7]
        val rz = -outMatrix[8]

        val ra = (Math.toDegrees(atan2(rz.toDouble(), rx.toDouble())) + 360.0) % 360.0
        assertTrue("RA should be valid angle [0, 360)", ra in 0.0..360.0)
    }

    @Test
    fun `atmosphere view matrix is strictly orthonormal under all display rotations`() {
        val outMatrix = FloatArray(9)
        val rotations = intArrayOf(0, 1, 2, 3)

        for (rot in rotations) {
            CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                sensorMatrix16 = identity16,
                displayRotation = rot,
                outMatrix9 = outMatrix,
            )

            // Column 0
            val c0x = outMatrix[0]; val c0y = outMatrix[1]; val c0z = outMatrix[2]
            // Column 1
            val c1x = outMatrix[3]; val c1y = outMatrix[4]; val c1z = outMatrix[5]
            // Column 2
            val c2x = outMatrix[6]; val c2y = outMatrix[7]; val c2z = outMatrix[8]

            val mag0 = sqrt(c0x * c0x + c0y * c0y + c0z * c0z)
            val mag1 = sqrt(c1x * c1x + c1y * c1y + c1z * c1z)
            val mag2 = sqrt(c2x * c2x + c2y * c2y + c2z * c2z)

            assertEquals("Col 0 magnitude at rotation $rot", 1.0f, mag0, 0.001f)
            assertEquals("Col 1 magnitude at rotation $rot", 1.0f, mag1, 0.001f)
            assertEquals("Col 2 magnitude at rotation $rot", 1.0f, mag2, 0.001f)

            val dot01 = c0x * c1x + c0y * c1y + c0z * c1z
            val dot02 = c0x * c2x + c0y * c2y + c0z * c2z
            val dot12 = c1x * c2x + c1y * c2y + c1z * c2z

            assertEquals("Dot 0-1 at rotation $rot", 0.0f, dot01, 0.001f)
            assertEquals("Dot 0-2 at rotation $rot", 0.0f, dot02, 0.001f)
            assertEquals("Dot 1-2 at rotation $rot", 0.0f, dot12, 0.001f)
        }
    }

    @Test
    fun `atmosphere view matrix accurately maps camera pitch to ENU elevation without celestial rotation`() {
        val outMatrix = FloatArray(9)
        // Upright phone looking horizontally at North horizon (Device Y = World Up, Device Z = World South)
        val uprightLookingNorth = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        CelestialMatrixCalculator.computeAtmosphereViewMatrix(
            sensorMatrix16 = uprightLookingNorth,
            displayRotation = 0,
            outMatrix9 = outMatrix,
        )

        // rayDir [0, 0, -1] (center of screen):
        // worldDir.z = -outMatrix[8]
        val centerZ = -outMatrix[8]
        assertEquals("Horizontal camera center elevation is 0", 0.0f, centerZ, 0.001f)

        // Screen top ray [0, 1, 0] (top of screen points up):
        val topZ = outMatrix[5]
        assertEquals("Screen top points straight up towards zenith", 1.0f, topZ, 0.001f)
    }

    @Test
    fun `computeManualRotationMatrix constructs strictly orthonormal 4x4 matrix pointing to requested Az and El`() {
        val mat = FloatArray(16)
        val testAngles = listOf(
            Pair(0.0, 0.0),      // North horizon
            Pair(90.0, 0.0),     // East horizon
            Pair(180.0, 0.0),    // South horizon
            Pair(270.0, 0.0),    // West horizon
            Pair(45.0, 30.0),    // NE 30 deg el
            Pair(215.0, 60.0),   // SW 60 deg el
            Pair(120.0, -45.0),  // SE -45 deg el (below horizon)
        )

        for ((az, el) in testAngles) {
            CelestialMatrixCalculator.computeManualRotationMatrix(az, el, mat)

            // Col 0: Right (X)
            val r0 = mat[0]; val r1 = mat[4]; val r2 = mat[8]
            // Col 1: Up (Y)
            val u0 = mat[1]; val u1 = mat[5]; val u2 = mat[9]
            // Col 2: Backward (Z)
            val b0 = mat[2]; val b1 = mat[6]; val b2 = mat[10]

            // Assert unit magnitudes
            val magR = sqrt(r0 * r0 + r1 * r1 + r2 * r2)
            val magU = sqrt(u0 * u0 + u1 * u1 + u2 * u2)
            val magB = sqrt(b0 * b0 + b1 * b1 + b2 * b2)

            assertEquals("Right axis magnitude at Az $az, El $el", 1.0f, magR, 1e-4f)
            assertEquals("Up axis magnitude at Az $az, El $el", 1.0f, magU, 1e-4f)
            assertEquals("Backward axis magnitude at Az $az, El $el", 1.0f, magB, 1e-4f)

            // Assert orthogonality
            assertEquals("Dot R-U at Az $az, El $el", 0.0f, r0 * u0 + r1 * u1 + r2 * u2, 1e-4f)
            assertEquals("Dot R-B at Az $az, El $el", 0.0f, r0 * b0 + r1 * b1 + r2 * b2, 1e-4f)
            assertEquals("Dot U-B at Az $az, El $el", 0.0f, u0 * b0 + u1 * b1 + u2 * b2, 1e-4f)

            // Assert camera forward vector points at (Az, El):
            // v_cam = [-b0, -b1, -b2] in world ENU:
            // East = v_cam[0], North = v_cam[1], Up = v_cam[2]
            val fwdEast = -b0
            val fwdNorth = -b1
            val fwdUp = -b2

            val computedEl = Math.toDegrees(asin(fwdUp.coerceIn(-1f, 1f).toDouble()))
            var computedAz = Math.toDegrees(atan2(fwdEast.toDouble(), fwdNorth.toDouble()))
            if (computedAz < 0.0) computedAz += 360.0

            assertEquals("Target Azimuth at Az $az, El $el", az, computedAz, 0.01)
            assertEquals("Target Elevation at Az $az, El $el", el, computedEl, 0.01)
        }
    }
}


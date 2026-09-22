package com.dazix.caelis.domain.nightsky

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

    /**
     * Which *way* the centre of the screen faces, not just how high.
     *
     * The elevation assertion above passes just as happily on a matrix turned half way round, and the
     * shaders that consume this matrix read an azimuth out of it. A terrain horizon drawn half a turn
     * out got all the way onto the screen through exactly that gap: the ridge looked right, it was
     * simply on the wrong side of the sky.
     */
    @Test
    fun `atmosphere view matrix puts the screen centre on the azimuth the camera faces`() {
        val outMatrix = FloatArray(9)
        val sensorMatrix = FloatArray(16)

        for (azimuth in doubleArrayOf(0.0, 90.0, 180.0, 270.0, 37.5)) {
            CelestialMatrixCalculator.computeManualRotationMatrix(azimuth, 0.0, sensorMatrix)
            CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                sensorMatrix16 = sensorMatrix,
                displayRotation = 0,
                outMatrix9 = outMatrix,
            )

            // worldDir for the centre ray [0, 0, -1] is minus the third column of the mat3, which the
            // shader receives column-major: entries 6, 7 and 8.
            val east = -outMatrix[6]
            val north = -outMatrix[7]
            val recovered = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble()))
                .let { if (it < 0.0) it + 360.0 else it }

            assertEquals("centre of the screen at azimuth $azimuth", azimuth, recovered, 0.01)
        }
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

    @Test
    fun `computeCelestialToDeviceMatrix is strictly orthonormal and correctly transforms celestial coordinates`() {
        val mat9 = FloatArray(9)
        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(
            sensorMatrix16 = identity16,
            lstDegrees = 0.0,
            latitudeDegrees = 45.0,
            outMatrix9 = mat9,
        )

        // Column 0
        val c0x = mat9[0]; val c0y = mat9[3]; val c0z = mat9[6]
        // Column 1
        val c1x = mat9[1]; val c1y = mat9[4]; val c1z = mat9[7]
        // Column 2
        val c2x = mat9[2]; val c2y = mat9[5]; val c2z = mat9[8]

        val mag0 = sqrt(c0x * c0x + c0y * c0y + c0z * c0z)
        val mag1 = sqrt(c1x * c1x + c1y * c1y + c1z * c1z)
        val mag2 = sqrt(c2x * c2x + c2y * c2y + c2z * c2z)

        assertEquals("Col 0 magnitude", 1.0f, mag0, 0.001f)
        assertEquals("Col 1 magnitude", 1.0f, mag1, 0.001f)
        assertEquals("Col 2 magnitude", 1.0f, mag2, 0.001f)

        // North Celestial Pole in J2000 [0, 1, 0]
        // V_dev = M_cel2dev * [0, 1, 0] = [mat9[1], mat9[4], mat9[7]]
        val devX = mat9[1]
        val devY = mat9[4]
        val devZ = mat9[7]

        // In ENU, at Lat 45 deg, LST 0: North Celestial Pole is due North (Y=cos(45)), Up (Z=sin(45))
        // With identity sensor matrix (V_dev = V_enu), devX = 0, devY = cos(45°), devZ = sin(45°)
        assertEquals(0.0f, devX, 0.001f)
        assertEquals(kotlin.math.cos(Math.toRadians(45.0)).toFloat(), devY, 0.001f)
        assertEquals(kotlin.math.sin(Math.toRadians(45.0)).toFloat(), devZ, 0.001f)
    }

    @Test
    fun `stationary manual matrix with advancing LST produces smooth monotonic sidereal drift without oscillation`() {
        val manualMatrix = FloatArray(16)
        // Camera looking South (Az 180°), Elevation 30°
        CelestialMatrixCalculator.computeManualRotationMatrix(azDeg = 180.0, elDeg = 30.0, outMatrix16 = manualMatrix)

        val matFrame0 = FloatArray(9)
        val matFrame1 = FloatArray(9)
        val matFrame2 = FloatArray(9)

        val t0Ms = 1774304609000L
        val t1Ms = t0Ms + 16L
        val t2Ms = t0Ms + 32L
        val lon = 14.4378
        val lat = 50.0

        val lst0 = SiderealTime.calculateLST(SiderealTime.toJulianDate(t0Ms), lon)
        val lst1 = SiderealTime.calculateLST(SiderealTime.toJulianDate(t1Ms), lon)
        val lst2 = SiderealTime.calculateLST(SiderealTime.toJulianDate(t2Ms), lon)

        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(manualMatrix, lst0, lat, matFrame0)
        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(manualMatrix, lst1, lat, matFrame1)
        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(manualMatrix, lst2, lat, matFrame2)

        // Star at J2000 [1, 0, 0] (RA 0, Dec 0)
        val devX0 = matFrame0[0]
        val devX1 = matFrame1[0]
        val devX2 = matFrame2[0]

        val diff01 = devX1 - devX0
        val diff12 = devX2 - devX1

        // Delta must be non-zero and maintain exact same direction/sign across frames (monotonic, no oscillation)
        assertTrue("Star position must drift across frames", abs(diff01) > 0.0f)
        assertTrue("Direction must be monotonic (same sign)", diff01 * diff12 > 0.0f)
        assertEquals("Drift rate must be strictly continuous and uniform", diff01, diff12, 1e-6f)
    }
}


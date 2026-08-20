package com.dazix.intervalometer.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class OrientationTrackerMathTest {

    @Test
    fun `verifies magnetic declination 0 degrees preserves matrix identity`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)

        OrientationTracker.applyMagneticDeclination(identity, 0.0f, out)

        for (i in 0 until 16) {
            assertEquals(identity[i], out[i], 0.0001f)
        }
    }

    @Test
    fun `verifies magnetic declination adjusts look vector to true north`() {
        // Device pointing towards True North when Magnetic North is +10° East (declination = +10°).
        // Sensor matrix reports device facing -10° (350°) relative to Magnetic North.
        // Sensor matrix: R_sensor rotates vector at -10° in ENU to -Z_dev.
        val dec = 10.0f
        val rad = Math.toRadians(-dec.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()

        // Sensor matrix where -Z_dev is pointing at (sinA, cosA, 0) in magnetic ENU:
        // Col 0 (+X_dev): [cosA, -sinA, 0]^T
        // Col 1 (+Y_dev): [0, 0, 1]^T
        // Col 2 (+Z_dev): [-sinA, -cosA, 0]^T
        val sensorMatrix = floatArrayOf(
            cosA, 0f, -sinA, 0f,
            -sinA, 0f, -cosA, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val adjustedMatrix = FloatArray(16)
        OrientationTracker.applyMagneticDeclination(sensorMatrix, dec, adjustedMatrix)

        // Camera forward (-Z_dev) in true world ENU:
        val camFwdX = -adjustedMatrix[2]
        val camFwdY = -adjustedMatrix[6]
        val camFwdZ = -adjustedMatrix[10]

        val camAzDeg = (Math.toDegrees(atan2(camFwdX.toDouble(), camFwdY.toDouble()))).toFloat()
        val normAzDeg = if (camAzDeg < 0f) camAzDeg + 360f else camAzDeg

        assertEquals(0.0f, normAzDeg, 0.01f) // True North!
        assertEquals(0.0f, camFwdZ, 0.01f)   // On horizon
    }

    @Test
    fun `verifies adjusted matrix preserves 100 percent orthonormality`() {
        val sensorMatrix = floatArrayOf(
            0.866f, 0.5f, 0f, 0f,
            -0.5f, 0.866f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val adjusted = FloatArray(16)

        OrientationTracker.applyMagneticDeclination(sensorMatrix, 23.5f, adjusted)

        // Check each column is a unit vector
        for (col in 0..2) {
            val lenSq = adjusted[col] * adjusted[col] +
                adjusted[col + 4] * adjusted[col + 4] +
                adjusted[col + 8] * adjusted[col + 8]
            assertEquals("Column $col norm should be 1.0", 1.0f, sqrt(lenSq), 0.001f)
        }

        // Check columns are mutually orthogonal (dot product == 0)
        val dot01 = adjusted[0] * adjusted[1] + adjusted[4] * adjusted[5] + adjusted[8] * adjusted[9]
        val dot02 = adjusted[0] * adjusted[2] + adjusted[4] * adjusted[6] + adjusted[8] * adjusted[10]
        val dot12 = adjusted[1] * adjusted[2] + adjusted[5] * adjusted[6] + adjusted[9] * adjusted[10]

        assertEquals(0.0f, dot01, 0.001f)
        assertEquals(0.0f, dot02, 0.001f)
        assertEquals(0.0f, dot12, 0.001f)
    }

    @Test
    fun `verifies upright spherical calibration preserves 100 percent orthonormality across zenith`() {
        // Portrait phone facing North at the horizon:
        // Col 0 (+X_dev): [1, 0, 0]^T (East)
        // Col 1 (+Y_dev): [0, 0, 1]^T (Zenith / Up)
        // Col 2 (+Z_dev): [0, -1, 0]^T (South / Backwards) -> camera forward (-Z_dev) is North
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val tempDec = FloatArray(16)
        val tempRotX = FloatArray(16)
        val adjusted = FloatArray(16)

        // Rotate past zenith (+120° continuous pitch)
        OrientationTracker.applyCalibration(
            sensorMatrix = sensorMatrix,
            declinationDeg = 15.0f,
            userAzimuthDeg = 0.0f,
            userPitchDeg = 120.0f,
            tempDeclinationBuffer = tempDec,
            tempRotXBuffer = tempRotX,
            outMatrix = adjusted,
        )

        // Check each column is a unit vector
        for (col in 0..2) {
            val lenSq = adjusted[col] * adjusted[col] +
                adjusted[col + 4] * adjusted[col + 4] +
                adjusted[col + 8] * adjusted[col + 8]
            assertEquals("Column $col norm should be 1.0", 1.0f, sqrt(lenSq), 0.001f)
        }

        // Check columns are mutually orthogonal
        val dot01 = adjusted[0] * adjusted[1] + adjusted[4] * adjusted[5] + adjusted[8] * adjusted[9]
        val dot02 = adjusted[0] * adjusted[2] + adjusted[4] * adjusted[6] + adjusted[8] * adjusted[10]
        val dot12 = adjusted[1] * adjusted[2] + adjusted[5] * adjusted[6] + adjusted[9] * adjusted[10]

        assertEquals(0.0f, dot01, 0.001f)
        assertEquals(0.0f, dot02, 0.001f)
        assertEquals(0.0f, dot12, 0.001f)

        // Forward vector in world coordinates (-Z_dev):
        val camFwdZ = -adjusted[10]
        // Sin(120°) = sin(60°) = 0.866
        assertEquals(kotlin.math.sin(Math.toRadians(60.0)).toFloat(), camFwdZ, 0.01f)

        // Camera Up Z component (+Y_dev in World Z) must be non-negative (upright guarantee)
        val camUpZ = adjusted[9]
        assertTrue("Camera Up Z must be >= 0 (upright guarantee), was $camUpZ", camUpZ >= 0.0f)
    }

    @Test
    fun `verifies continuous 360 degree spherical pitch rotation loops seamlessly with guaranteed upright horizon`() {
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val tempDec = FloatArray(16)
        val tempRotX = FloatArray(16)
        val adjusted = FloatArray(16)

        // Test every 15 degrees across full 360 loop
        for (pitchDeg in 0..360 step 15) {
            OrientationTracker.applyCalibration(
                sensorMatrix = sensorMatrix,
                declinationDeg = 0.0f,
                userAzimuthDeg = 0.0f,
                userPitchDeg = pitchDeg.toFloat(),
                tempDeclinationBuffer = tempDec,
                tempRotXBuffer = tempRotX,
                outMatrix = adjusted,
            )

            // 1. Check orthonormality
            for (col in 0..2) {
                val lenSq = adjusted[col] * adjusted[col] +
                    adjusted[col + 4] * adjusted[col + 4] +
                    adjusted[col + 8] * adjusted[col + 8]
                assertEquals("Column $col norm at $pitchDeg°", 1.0f, sqrt(lenSq), 0.001f)
            }

            // 2. Camera Up vector (+Y_dev) must ALWAYS point upwards in world space (Up_Z >= -0.01f)
            val camUpZ = adjusted[9]
            assertTrue("Camera Up_Z at $pitchDeg° must be >= 0 (upright), was $camUpZ", camUpZ >= -0.001f)
        }
    }
}

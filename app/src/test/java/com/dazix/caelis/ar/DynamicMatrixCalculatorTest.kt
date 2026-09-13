package com.dazix.caelis.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class DynamicMatrixCalculatorTest {

    @Test
    fun `zero declination and zero azimuth offset preserves sensor matrix`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)

        DynamicMatrixCalculator.computeTrueWorldMatrix(
            sensorMatrix16 = identity,
            declinationDeg = 0f,
            azimuthOffsetDeg = 0f,
            outMatrix16 = out,
        )

        for (i in 0 until 16) {
            assertEquals("Index $i mismatch", identity[i], out[i], 1e-4f)
        }
    }

    @Test
    fun `pre-multiplication by 90 degrees rotates camera forward vector correctly`() {
        // Device pointing towards magnetic North (cam forward -Z_dev = [0, 1, 0]^T world)
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)

        // Rotate by 90 degrees azimuth offset (East)
        DynamicMatrixCalculator.computeTrueWorldMatrix(
            sensorMatrix16 = sensorMatrix,
            declinationDeg = 45f,
            azimuthOffsetDeg = 45f,
            outMatrix16 = out,
        )

        // Camera forward in True World ENU: [-out[2], -out[6], -out[10]]
        val camFwdX = -out[2]
        val camFwdY = -out[6]
        val camFwdZ = -out[10]

        val azDeg = Math.toDegrees(atan2(camFwdX.toDouble(), camFwdY.toDouble())).toFloat()
        val normAzDeg = (azDeg + 360f) % 360f

        assertEquals(90.0f, normAzDeg, 0.01f) // Pointing East in True World
        assertEquals(1.0f, camFwdX, 0.01f)
        assertEquals(0.0f, camFwdY, 0.01f)
        assertEquals(0.0f, camFwdZ, 0.01f)
    }

    @Test
    fun `orthonormality is strictly preserved after pre-multiplication`() {
        val sensorMatrix = floatArrayOf(
            0.866f, 0.5f, 0f, 0f,
            -0.5f, 0.866f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)

        DynamicMatrixCalculator.computeTrueWorldMatrix(
            sensorMatrix16 = sensorMatrix,
            declinationDeg = 12.3f,
            azimuthOffsetDeg = -4.7f,
            outMatrix16 = out,
        )

        // Row lengths must be 1.0 (orthonormal)
        val r0Len = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        val r1Len = kotlin.math.sqrt(out[4] * out[4] + out[5] * out[5] + out[6] * out[6])
        val r2Len = kotlin.math.sqrt(out[8] * out[8] + out[9] * out[9] + out[10] * out[10])

        assertEquals(1.0f, r0Len, 1e-4f)
        assertEquals(1.0f, r1Len, 1e-4f)
        assertEquals(1.0f, r2Len, 1e-4f)
    }
}

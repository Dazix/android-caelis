package com.dazix.caelis.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class QuaternionSlerpTest {

    @Test
    fun `slerp at endpoints u=0 and u=1 returns respective quaternions`() {
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        val q2 = floatArrayOf(0f, 0f, sin(Math.toRadians(45.0)).toFloat(), cos(Math.toRadians(45.0)).toFloat())
        val out = FloatArray(4)

        QuaternionSlerp.slerp(q1, q2, 0f, out)
        assertEquals(q1[0], out[0], 1e-4f)
        assertEquals(q1[1], out[1], 1e-4f)
        assertEquals(q1[2], out[2], 1e-4f)
        assertEquals(q1[3], out[3], 1e-4f)

        QuaternionSlerp.slerp(q1, q2, 1f, out)
        assertEquals(q2[0], out[0], 1e-4f)
        assertEquals(q2[1], out[1], 1e-4f)
        assertEquals(q2[2], out[2], 1e-4f)
        assertEquals(q2[3], out[3], 1e-4f)
    }

    @Test
    fun `slerp at u=0_5 gives exact half angle rotation`() {
        // q1 = 0 degrees around Z: [0, 0, 0, 1]
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        // q2 = 90 degrees around Z: [0, 0, sin(45°), cos(45°)]
        val q2 = floatArrayOf(0f, 0f, sin(Math.toRadians(45.0)).toFloat(), cos(Math.toRadians(45.0)).toFloat())
        val out = FloatArray(4)

        QuaternionSlerp.slerp(q1, q2, 0.5f, out)

        // Expected midpoint: 45 degrees around Z -> [0, 0, sin(22.5°), cos(22.5°)]
        val expectedZ = sin(Math.toRadians(22.5)).toFloat()
        val expectedW = cos(Math.toRadians(22.5)).toFloat()

        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(expectedZ, out[2], 1e-4f)
        assertEquals(expectedW, out[3], 1e-4f)
    }

    @Test
    fun `slerp with antipodal quaternion takes shortest path`() {
        val q1 = floatArrayOf(0f, 0f, 0f, 1f)
        // Antipodal representation of identity: [0, 0, 0, -1]
        val q2 = floatArrayOf(0f, 0f, 0f, -1f)
        val out = FloatArray(4)

        QuaternionSlerp.slerp(q1, q2, 0.5f, out)

        // Must remain on identity (w = 1 or -1) without tumbling
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(0f, out[2], 1e-4f)
        assertEquals(1f, kotlin.math.abs(out[3]), 1e-4f)
    }

    @Test
    fun `quaternionToRotationMatrix converts identity quaternion to identity matrix`() {
        val qIdentity = floatArrayOf(0f, 0f, 0f, 1f)
        val matrix = FloatArray(16)

        QuaternionSlerp.quaternionToRotationMatrix(qIdentity, matrix)

        val expected = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        for (i in 0 until 16) {
            assertEquals("Index $i mismatch", expected[i], matrix[i], 1e-4f)
        }
    }

    @Test
    fun `quaternionToRotationMatrix converts 90 deg Z rotation accurately`() {
        // 90° rotation around Z: [0, 0, sin(45°), cos(45°)]
        val q90Z = floatArrayOf(0f, 0f, sin(Math.toRadians(45.0)).toFloat(), cos(Math.toRadians(45.0)).toFloat())
        val matrix = FloatArray(16)

        QuaternionSlerp.quaternionToRotationMatrix(q90Z, matrix)

        // Rotation around Z by 90°:
        // [ 0  1  0  0 ]
        // [-1  0  0  0 ]
        // [ 0  0  1  0 ]
        // [ 0  0  0  1 ]
        assertEquals(0f, matrix[0], 1e-4f)
        assertEquals(1f, matrix[1], 1e-4f)
        assertEquals(0f, matrix[2], 1e-4f)

        assertEquals(-1f, matrix[4], 1e-4f)
        assertEquals(0f, matrix[5], 1e-4f)
        assertEquals(0f, matrix[6], 1e-4f)

        assertEquals(0f, matrix[8], 1e-4f)
        assertEquals(0f, matrix[9], 1e-4f)
        assertEquals(1f, matrix[10], 1e-4f)
    }

    @Test
    fun `quaternionToRowMajorMatrix matches the SensorManager layout`() {
        // SensorManager.getRotationMatrixFromVector for 90 deg about Z writes, row-major:
        // [ 0 -1  0 ]
        // [ 1  0  0 ]
        // [ 0  0  1 ]
        // so a device X axis maps to world Y. The column-major variant is the transpose and would
        // map it to -Y: the camera rotation inverted.
        val q90Z = floatArrayOf(0f, 0f, sin(Math.toRadians(45.0)).toFloat(), cos(Math.toRadians(45.0)).toFloat())
        val matrix = FloatArray(16)

        QuaternionSlerp.quaternionToRowMajorMatrix(q90Z, matrix)

        val expected = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        for (i in 0 until 16) assertEquals("Index $i mismatch", expected[i], matrix[i], 1e-4f)
    }

    @Test
    fun `quaternionToRowMajorMatrix is the transpose of the column-major matrix for a general rotation`() {
        val n = sqrt(0.1f * 0.1f + 0.7f * 0.7f + 0.3f * 0.3f + 0.6f * 0.6f)
        val q = floatArrayOf(0.1f / n, 0.7f / n, 0.3f / n, 0.6f / n)
        val col = FloatArray(16)
        val row = FloatArray(16)
        QuaternionSlerp.quaternionToRotationMatrix(q, col)
        QuaternionSlerp.quaternionToRowMajorMatrix(q, row)
        for (i in 0 until 4) for (j in 0 until 4) {
            assertEquals("($i,$j)", col[j * 4 + i], row[i * 4 + j], 1e-6f)
        }
    }
}

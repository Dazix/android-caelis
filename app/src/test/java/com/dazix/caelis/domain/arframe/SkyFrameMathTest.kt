package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SkyFrameMathTest {

    private val identity16 = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `north celestial pole stands due north at the observer's latitude`() {
        val m = FloatArray(16)
        val out = FloatArray(3)
        for (lst in doubleArrayOf(0.0, 77.0, 190.0, 300.0)) {
            SkyFrameMath.celestialToEnu(lst, 50.0, m)
            SkyFrameMath.transformDirection(m, 0f, 1f, 0f, out)
            assertEquals("east at lst $lst", 0f, out[0], 1e-5f)
            assertEquals("north at lst $lst", cos(Math.toRadians(50.0)).toFloat(), out[1], 1e-5f)
            assertEquals("up at lst $lst", sin(Math.toRadians(50.0)).toFloat(), out[2], 1e-5f)
        }
    }

    @Test
    fun `a star on the meridian at the local sidereal time culminates due south`() {
        // RA = LST puts the star on the meridian; dec 0 from latitude 50 N -> 40 deg up, south.
        val lst = 123.0
        val ra = Math.toRadians(lst)
        val m = FloatArray(16)
        val out = FloatArray(3)
        SkyFrameMath.celestialToEnu(lst, 50.0, m)
        SkyFrameMath.transformDirection(m, cos(ra).toFloat(), 0f, sin(ra).toFloat(), out)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(-cos(Math.toRadians(40.0)).toFloat(), out[1], 1e-5f)
        assertEquals(sin(Math.toRadians(40.0)).toFloat(), out[2], 1e-5f)
    }

    @Test
    fun `GPU celestial frame agrees with the Canvas pipeline's celestial to device matrix`() {
        // With an identity sensor matrix device == ENU, so the old row-major M_cel2dev and the new
        // column-major celestial -> ENU must map every star to the same vector.
        val gpu = FloatArray(16)
        val cpu = FloatArray(9)
        val out = FloatArray(3)
        SkyFrameMath.celestialToEnu(211.5, -33.9, gpu)
        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(identity16, 211.5, -33.9, cpu)
        val stars = arrayOf(floatArrayOf(0.3f, 0.5f, -0.81f), floatArrayOf(-0.9f, -0.1f, 0.42f))
        for (v in stars) {
            SkyFrameMath.transformDirection(gpu, v[0], v[1], v[2], out)
            for (row in 0 until 3) {
                val expected = cpu[row * 3] * v[0] + cpu[row * 3 + 1] * v[1] + cpu[row * 3 + 2] * v[2]
                assertEquals("row $row", expected, out[row], 1e-5f)
            }
        }
    }

    @Test
    fun `camera looks along the back camera axis in natural orientation`() {
        val m = FloatArray(16)
        val out = FloatArray(3)
        SkyFrameMath.cameraModelMatrix(identity16, 0, m)
        SkyFrameMath.transformDirection(m, 0f, 0f, -1f, out)
        assertVec(floatArrayOf(0f, 0f, -1f), out)
        SkyFrameMath.transformDirection(m, 1f, 0f, 0f, out)
        assertVec(floatArrayOf(1f, 0f, 0f), out)
    }

    @Test
    fun `display rotation turns screen right and up but never the viewing axis`() {
        // Screen right / up in device axes for each Surface.ROTATION_*.
        val cases = mapOf(
            0 to (floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)),
            90 to (floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)),
            180 to (floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)),
            270 to (floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)),
        )
        val m = FloatArray(16)
        val out = FloatArray(3)
        for ((rotation, axes) in cases) {
            SkyFrameMath.cameraModelMatrix(identity16, rotation, m)
            SkyFrameMath.transformDirection(m, 1f, 0f, 0f, out)
            assertVec(axes.first, out, "right at $rotation")
            SkyFrameMath.transformDirection(m, 0f, 1f, 0f, out)
            assertVec(axes.second, out, "up at $rotation")
            SkyFrameMath.transformDirection(m, 0f, 0f, -1f, out)
            assertVec(floatArrayOf(0f, 0f, -1f), out, "forward at $rotation")
        }
    }

    @Test
    fun `camera model matrix stays orthonormal for a tilted 3x3 sensor matrix`() {
        // 30 deg pitch about X, row-major 3x3 as SensorManager can also produce.
        val a = Math.toRadians(30.0)
        val r9 = floatArrayOf(
            1f, 0f, 0f,
            0f, cos(a).toFloat(), -sin(a).toFloat(),
            0f, sin(a).toFloat(), cos(a).toFloat(),
        )
        val m = FloatArray(16)
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            SkyFrameMath.cameraModelMatrix(r9, rotation, m)
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    val dot = m[i * 4] * m[j * 4] + m[i * 4 + 1] * m[j * 4 + 1] + m[i * 4 + 2] * m[j * 4 + 2]
                    assertEquals("col $i . col $j at $rotation", if (i == j) 1f else 0f, dot, 1e-5f)
                }
            }
            assertEquals(1f, m[15], 0f)
            assertEquals(0f, sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14]), 0f)
        }
    }

    private fun assertVec(expected: FloatArray, actual: FloatArray, message: String = "") {
        for (i in 0 until 3) assertEquals("$message [$i]", expected[i], actual[i], 1e-5f)
    }
}

package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArYawEstimatorTest {

    private fun rx(a: Double) = floatArrayOf(1f, 0f, 0f, 0f, cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat())
    private fun ry(a: Double) = floatArrayOf(cos(a).toFloat(), 0f, sin(a).toFloat(), 0f, 1f, 0f, -sin(a).toFloat(), 0f, cos(a).toFloat())
    private fun rz(a: Double) = floatArrayOf(cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat(), 0f, 0f, 0f, 1f)
    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9).also { o ->
        for (r in 0 until 3) for (c in 0 until 3) o[r * 3 + c] = (0 until 3).sumOf { k -> (a[r * 3 + k] * b[k * 3 + c]).toDouble() }.toFloat()
    }

    private val high = 3
    private val low = 1
    private val ms = 1_000_000L

    /** What the sensor tracker would report for a device at [arDevice] when ARCore's world is [psiDeg] off. */
    private fun reference(psiDeg: Double, arDevice: FloatArray) =
        mul(rz(Math.toRadians(psiDeg)), mul(ArWorldAlignment.EUS_TO_ENU, arDevice))

    private fun device(i: Int) = mul(ry(0.4 + 0.01 * i), mul(rx(-0.3 + 0.005 * i), rz(0.1)))

    @Test
    fun `converges on the yaw between the two worlds as the phone turns`() {
        val e = ArYawEstimator()
        assertFalse(e.hasEstimate)
        for (i in 0 until 60) {
            val d = device(i)
            e.addCompass(reference(123.0, d), high, d, i * 33 * ms)
        }
        assertTrue(e.isConverged)
        assertEquals(123.0, Math.toDegrees(e.yaw.toDouble()), 0.05)
        assertEquals(HeadingFusion.Source.COMPASS, e.source)
    }

    @Test
    fun `an unreliable compass is not averaged into a heading`() {
        val e = ArYawEstimator()
        for (i in 0 until 100) {
            val d = device(i)
            e.addCompass(reference(40.0, d), low, d, i * 33 * ms)
        }
        assertFalse(e.hasEstimate)
        assertFalse(e.isConverged)
    }

    @Test
    fun `an accurate Geospatial yaw replaces a compass estimate and outranks later compass samples`() {
        val e = ArYawEstimator()
        for (i in 0 until 60) {
            val d = device(i)
            e.addCompass(reference(10.0, d), high, d, i * 33 * ms)
        }
        assertEquals(10.0, Math.toDegrees(e.yaw.toDouble()), 0.05)

        val t = Math.toRadians(47.0)
        for (i in 60 until 70) {
            val arCamera = mul(device(i), rz(Math.toRadians(90.0)))
            val eusCamera = mul(ry(t), arCamera)
            e.addGeospatial(eusCamera, arCamera, 3f, i * 33 * ms)
            // The compass keeps disagreeing; Geospatial holds the heading for a while.
            val d = device(i)
            e.addCompass(reference(10.0, d), high, d, i * 33 * ms + 1)
        }
        assertEquals(HeadingFusion.Source.GEOSPATIAL, e.source)
        assertEquals(47.0, Math.toDegrees(e.yaw.toDouble()), 0.5)
    }

    @Test
    fun `a Geospatial yaw that is not accurate enough is ignored`() {
        val e = ArYawEstimator()
        val arCamera = device(0)
        e.addGeospatial(mul(ry(0.5), arCamera), arCamera, 40f, 0L)
        assertFalse(e.hasEstimate)
    }

    @Test
    fun `reset forgets the world`() {
        val e = ArYawEstimator()
        for (i in 0 until 60) {
            val d = device(i)
            e.addCompass(reference(77.0, d), high, d, i * 33 * ms)
        }
        e.reset()
        assertFalse(e.hasEstimate)
        assertFalse(e.isConverged)
    }
}

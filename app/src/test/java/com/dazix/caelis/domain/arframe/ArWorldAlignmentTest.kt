package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArWorldAlignmentTest {

    private fun rx(a: Double) = floatArrayOf(1f, 0f, 0f, 0f, cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat())
    private fun ry(a: Double) = floatArrayOf(cos(a).toFloat(), 0f, sin(a).toFloat(), 0f, 1f, 0f, -sin(a).toFloat(), 0f, cos(a).toFloat())
    private fun rz(a: Double) = floatArrayOf(cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat(), 0f, 0f, 0f, 1f)

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9).also { o ->
        for (r in 0 until 3) for (c in 0 until 3) o[r * 3 + c] = (0 until 3).sumOf { k -> (a[r * 3 + k] * b[k * 3 + c]).toDouble() }.toFloat()
    }

    private fun transpose(a: FloatArray) = FloatArray(9) { i -> a[(i % 3) * 3 + i / 3] }

    /** An arbitrary device pose in ARCore's (Y-up) world: turned, pitched and rolled. */
    private val arDevice = mul(ry(0.7), mul(rx(-0.4), rz(0.2)))

    @Test
    fun `recovers the yaw between ARCore's world and ENU from the compass tracker`() {
        for (deg in doubleArrayOf(-170.0, -45.0, 0.0, 12.5, 90.0, 179.0)) {
            val psi = Math.toRadians(deg)
            val reference = mul(rz(psi), mul(ArWorldAlignment.EUS_TO_ENU, arDevice))
            val yaw = ArWorldAlignment.estimateYaw(reference, arDevice, FloatArray(9))
            assertEquals("yaw for $deg", deg, Math.toDegrees(yaw.toDouble()), 1e-3)
        }
    }

    @Test
    fun `a slightly tilted reference still gives the yaw, not a mix of yaw and tilt`() {
        // The sensor tracker's gravity estimate disagrees with ARCore's by ~1 deg.
        val psi = Math.toRadians(33.0)
        val reference = mul(rx(Math.toRadians(1.0)), mul(rz(psi), mul(ArWorldAlignment.EUS_TO_ENU, arDevice)))
        val yaw = ArWorldAlignment.estimateYaw(reference, arDevice, FloatArray(9))
        assertEquals(33.0, Math.toDegrees(yaw.toDouble()), 0.05)
    }

    @Test
    fun `the device to ENU matrix reproduces the reference it was aligned to`() {
        val psi = Math.toRadians(-61.0)
        val reference = mul(rz(psi), mul(ArWorldAlignment.EUS_TO_ENU, arDevice))
        val out = FloatArray(16)
        ArWorldAlignment.deviceToEnu(psi.toFloat(), arDevice, FloatArray(9), out)
        for (r in 0 until 3) for (c in 0 until 3) assertEquals("[$r,$c]", reference[r * 3 + c], out[r * 4 + c], 1e-5f)
        assertEquals(1f, out[15], 0f)
    }

    @Test
    fun `ARCore's world at heading zero is read as east-up-south`() {
        // Device camera (-Z) looking along ARCore -Z with no yaw means looking north.
        val out = FloatArray(16)
        ArWorldAlignment.deviceToEnu(0f, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), FloatArray(9), out)
        // Column 2 is device +Z in ENU; the camera looks along -column 2.
        assertEquals(0f, -out[2], 1e-6f)
        assertEquals(1f, -out[6], 1e-6f)
        assertEquals(0f, -out[10], 1e-6f)
    }

    @Test
    fun `Geospatial yaw agrees with the compass yaw for the same world`() {
        val t = Math.toRadians(47.0)
        val arCamera = mul(arDevice, rz(Math.toRadians(90.0))) // a camera frame != device frame
        val eusCamera = mul(ry(t), arCamera)
        val yaw = ArWorldAlignment.yawFromGeospatial(eusCamera, arCamera)
        assertEquals(47.0, Math.toDegrees(yaw.toDouble()), 1e-3)

        // The same world seen through the compass path: the true device -> ENU is E * Ry(t) * arDevice.
        val reference = mul(ArWorldAlignment.EUS_TO_ENU, mul(ry(t), arDevice))
        val compassYaw = ArWorldAlignment.estimateYaw(reference, arDevice, FloatArray(9))
        assertEquals(Math.toDegrees(yaw.toDouble()), Math.toDegrees(compassYaw.toDouble()), 1e-3)
    }

    @Test
    fun `quaternion to matrix follows ARCore's (x, y, z, w) layout`() {
        val h = sin(Math.PI / 4).toFloat()
        val out = FloatArray(9)
        ArWorldAlignment.quaternionToMatrix(0f, 0f, h, h, out) // +90 deg about Z
        val expected = rz(Math.PI / 2)
        for (i in 0 until 9) assertEquals("[$i]", expected[i], out[i], 1e-6f)
        val m = mul(out, transpose(out))
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1f else 0f, m[i], 1e-6f)
    }
}

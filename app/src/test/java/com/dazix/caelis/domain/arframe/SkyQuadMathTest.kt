package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyQuadMathTest {

    @Test
    fun `looking north with the zenith up puts east on the right`() {
        val out = FloatArray(12)
        assertTrue(SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 0f, 1f, 0.1f, 0.05f, out))
        assertArrayEquals(
            floatArrayOf(
                -0.1f, 1f, 0.05f, // top-left: west, up
                0.1f, 1f, 0.05f,
                0.1f, 1f, -0.05f,
                -0.1f, 1f, -0.05f,
            ),
            out, 1e-6f,
        )
    }

    @Test
    fun `the up direction loses its radial part`() {
        val out = FloatArray(12)
        // "Up" tilted towards the viewing direction still yields a quad in the tangent plane.
        SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 1f, 1f, 0.1f, 0.1f, out)
        for (c in 0 until 4) assertArrayEquals(floatArrayOf(1f), floatArrayOf(out[c * 3 + 1]), 1e-6f)
    }

    @Test
    fun `projected through the camera the quad is upright and centred`() {
        val projector = SkyProjector()
        val model = FloatArray(16)
        // Camera looking north, level: device -> ENU with device Y = up, device -Z = north.
        val deviceToEnu = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)
        SkyFrameMath.cameraModelMatrix(deviceToEnu, 0, model)
        projector.update(model, 1000f, 2000f, 60f)
        val f = projector.focalLengthPixels
        val out = FloatArray(12)
        SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 0f, 1f, 50f / f, 20f / f, out)
        val p = FloatArray(3)
        projector.projectEnu(out[0], out[1], out[2], p)
        assertArrayEquals(floatArrayOf(450f, 980f), floatArrayOf(p[0], p[1]), 1e-2f)
        projector.projectEnu(out[6], out[7], out[8], p)
        assertArrayEquals(floatArrayOf(550f, 1020f), floatArrayOf(p[0], p[1]), 1e-2f)
    }

    @Test
    fun `a body at the pole has no defined up`() {
        assertFalse(SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 1f, 0f, 0.1f, 0.1f, FloatArray(12)))
    }
}

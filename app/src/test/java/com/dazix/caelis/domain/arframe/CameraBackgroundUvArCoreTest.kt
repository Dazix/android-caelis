package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two helpers that let a coordinate mapping computed by ARCore stand in for rotation and crop. */
class CameraBackgroundUvArCoreTest {

    private val eps = 1e-5f
    private val out = FloatArray(CameraBackgroundUv.UV_COUNT)

    private fun assertUv(expected: FloatArray, actual: FloatArray) {
        for (i in expected.indices) assertEquals("element $i", expected[i], actual[i], eps)
    }

    @Test
    fun `an identity mapping gives the triangle's own corners`() {
        // Screen (0,0), (1,0), (0,1) land on texture (0,0), (1,0), (0,1): u = x, v = y.
        assertTrue(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), true, out))
        // The triangle's corners are screen (0,1), (2,1), (0,-1).
        assertUv(floatArrayOf(0f, 1f, 2f, 1f, 0f, -1f), out)
    }

    @Test
    fun `a texture whose v runs the other way is flipped, nothing else`() {
        assertTrue(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), false, out))
        assertUv(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), out)
    }

    @Test
    fun `corner samples agree with rotation and crop for the same picture`() {
        // Buffer 4000 x 3000, turned 90 degrees clockwise, in a viewport with the upright picture's own
        // aspect (3 : 4), so there is no crop. The screen's top-left is the buffer's (u 0, v 1),
        // top-right (0, 0), bottom-left (1, 1), by CameraBackgroundUv's own rule u = y, v = 1 - x.
        val samples = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f)
        assertTrue(CameraBackgroundUv.fromCornerSamples(samples, true, out))
        val expected = FloatArray(CameraBackgroundUv.UV_COUNT)
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 90, 1500f, 2000f, expected))
        assertUv(expected, out)
    }

    @Test
    fun `a cropping mapping is extrapolated linearly past the screen`() {
        // The screen shows the middle 50 % of the texture along u: 0.25 .. 0.75, v unchanged.
        assertTrue(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0.25f, 0f, 0.75f, 0f, 0.25f, 1f), true, out))
        assertUv(floatArrayOf(0.25f, 1f, 1.25f, 1f, 0.25f, -1f), out)
    }

    @Test
    fun `non-finite or short input is refused and leaves the output alone`() {
        val untouched = floatArrayOf(9f, 9f, 9f, 9f, 9f, 9f)
        val a = untouched.copyOf()
        assertFalse(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0f, 0f, Float.NaN, 0f, 0f, 1f), true, a))
        assertFalse(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0f, 0f, 1f), true, a))
        assertFalse(CameraBackgroundUv.fromCornerSamples(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), true, FloatArray(5)))
        for (i in untouched.indices) assertEquals(untouched[i], a[i], 0f)
    }

    @Test
    fun `the direction of an image's top edge on the screen is how far it is turned`() {
        assertEquals(0, CameraBackgroundUv.rotationFromTopEdge(1f, 0f))
        assertEquals(90, CameraBackgroundUv.rotationFromTopEdge(0f, 1f))
        assertEquals(180, CameraBackgroundUv.rotationFromTopEdge(-1f, 0f))
        assertEquals(270, CameraBackgroundUv.rotationFromTopEdge(0f, -1f))
    }

    @Test
    fun `an edge with a little skew is still the nearest quarter turn`() {
        assertEquals(0, CameraBackgroundUv.rotationFromTopEdge(0.98f, 0.12f))
        assertEquals(90, CameraBackgroundUv.rotationFromTopEdge(-0.1f, 0.9f))
        assertEquals(270, CameraBackgroundUv.rotationFromTopEdge(0.2f, -0.8f))
        assertEquals(0, CameraBackgroundUv.rotationFromTopEdge(Float.NaN, 1f))
    }
}

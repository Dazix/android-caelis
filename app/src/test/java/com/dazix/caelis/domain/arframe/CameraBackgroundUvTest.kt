package com.dazix.caelis.domain.arframe

import com.dazix.caelis.ar.CameraFovCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * Expected values are worked out on paper from "turn the buffer clockwise by the rotation, scale it
 * to cover the viewport, trim the overflow evenly", never by running the code under test.
 *
 * The 4:3 sensor buffer is 4000 x 3000 (landscape, the way a phone camera writes it).
 */
class CameraBackgroundUvTest {

    private val eps = 1e-5f
    private val out = FloatArray(CameraBackgroundUv.UV_COUNT)
    private val point = FloatArray(2)

    private fun pointAt(sx: Double, sy: Double, rotation: Int, bw: Int, bh: Int, vw: Float, vh: Float): Pair<Float, Float> {
        assertTrue(CameraBackgroundUv.bufferPointAt(sx, sy, bw, bh, rotation, vw, vh, point))
        return point[0] to point[1]
    }

    private fun assertUv(expectedU: Float, expectedV: Float, actual: Pair<Float, Float>, what: String) {
        assertEquals("$what u", expectedU, actual.first, eps)
        assertEquals("$what v", expectedV, actual.second, eps)
    }

    @Test
    fun portraitTwentyByNineCropsTheSidesOfTheUprightPicture() {
        // Upright picture 3000 x 4000 (aspect 0.75) in a 1080 x 2400 viewport (0.45): the width keeps
        // 0.45 / 0.75 = 60 %, trimmed 20 % from each side. For a 90-degree clockwise turn the
        // upright left edge is the buffer's bottom edge, so the left trim is the buffer's bottom 20 %.
        val args = intArrayOf(4000, 3000, 90)
        assertUv(0f, 0.8f, pointAt(0.0, 0.0, args[2], args[0], args[1], 1080f, 2400f), "top-left")
        assertUv(1f, 0.2f, pointAt(1.0, 1.0, args[2], args[0], args[1], 1080f, 2400f), "bottom-right")
        assertUv(0f, 0.2f, pointAt(1.0, 0.0, args[2], args[0], args[1], 1080f, 2400f), "top-right")
        assertUv(1f, 0.8f, pointAt(0.0, 1.0, args[2], args[0], args[1], 1080f, 2400f), "bottom-left")
    }

    @Test
    fun landscapeTwentyByNineCropsTopAndBottomOfTheUprightPicture() {
        // Upright picture 4000 x 3000 (1.333) in 2400 x 1080 (2.222): the height keeps 0.6, trimmed
        // 20 % from top and bottom; rotation 0 so buffer rows are screen rows.
        assertUv(0f, 0.2f, pointAt(0.0, 0.0, 0, 4000, 3000, 2400f, 1080f), "top-left")
        assertUv(1f, 0.8f, pointAt(1.0, 1.0, 0, 4000, 3000, 2400f, 1080f), "bottom-right")
    }

    @Test
    fun landscapeUpsideDownTurnsTheCropAround() {
        // The other landscape orientation: rotation 180, so what was the top 20 % trimmed is the bottom.
        assertUv(1f, 0.8f, pointAt(0.0, 0.0, 180, 4000, 3000, 2400f, 1080f), "top-left")
        assertUv(0f, 0.2f, pointAt(1.0, 1.0, 180, 4000, 3000, 2400f, 1080f), "bottom-right")
    }

    @Test
    fun fourByThreeViewportOnA4x3BufferShowsTheWholeBuffer() {
        // 3:4 portrait viewport and a 4:3 buffer turned 90 degrees: upright aspect 0.75 equals the
        // viewport's, so nothing is trimmed and the corners are just the rotation.
        val vw = 1200f
        val vh = 1600f
        // 0: buffer 3000 x 4000 already upright.
        assertUv(0f, 0f, pointAt(0.0, 0.0, 0, 3000, 4000, vw, vh), "0 top-left")
        assertUv(1f, 1f, pointAt(1.0, 1.0, 0, 3000, 4000, vw, vh), "0 bottom-right")
        // 90 clockwise: the buffer's bottom-left corner is on screen top-left.
        assertUv(0f, 1f, pointAt(0.0, 0.0, 90, 4000, 3000, vw, vh), "90 top-left")
        assertUv(1f, 0f, pointAt(1.0, 1.0, 90, 4000, 3000, vw, vh), "90 bottom-right")
        // 180: everything opposite.
        assertUv(1f, 1f, pointAt(0.0, 0.0, 180, 3000, 4000, vw, vh), "180 top-left")
        assertUv(0f, 0f, pointAt(1.0, 1.0, 180, 3000, 4000, vw, vh), "180 bottom-right")
        // 270 clockwise (90 counter-clockwise): the buffer's top-right corner is on screen top-left.
        assertUv(1f, 0f, pointAt(0.0, 0.0, 270, 4000, 3000, vw, vh), "270 top-left")
        assertUv(0f, 1f, pointAt(1.0, 1.0, 270, 4000, 3000, vw, vh), "270 bottom-right")
    }

    @Test
    fun theCentreOfTheScreenIsTheCentreOfTheBufferForEveryRotation() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for (viewport in arrayOf(1080f to 2400f, 2400f to 1080f, 1200f to 1600f, 1600f to 1200f)) {
                assertUv(0.5f, 0.5f, pointAt(0.5, 0.5, rotation, 4000, 3000, viewport.first, viewport.second), "rotation $rotation $viewport")
            }
        }
    }

    @Test
    fun theTriangleCornersAreTheScreenCornersExtendedPastTheEdges() {
        // Same aspect, no rotation: clip (-1,-1), (3,-1), (-1,3) are screen (0,1), (2,1), (0,-1).
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 0, 1600f, 1200f, out))
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 1f, 0f, -1f), out)
    }

    @Test
    fun theTriangleCornersFollowTheCropAndTheRotation() {
        // 90 degrees, 20:9 portrait, visible width 60 %: screen (0,1) -> upright (0.2, 1),
        // (2,1) -> (1.4, 1), (0,-1) -> (0.2, -1); u = y, v = 1 - x.
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 90, 1080f, 2400f, out))
        assertArrayEquals(floatArrayOf(1f, 0.8f, 1f, -0.4f, -1f, 0.8f), out)
    }

    @Test
    fun theThreeCornersDefineTheMiddleOfTheScreenToo() {
        // The GPU interpolates linearly between the corners, so the corners alone must put the
        // screen centre on the buffer centre: screen (0.5, 0.5) is a quarter of the way along both
        // edges of the triangle from (0, 1).
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for (viewport in arrayOf(1080f to 2400f, 2400f to 1080f, 1200f to 1600f)) {
                assertTrue(CameraBackgroundUv.compute(4000, 3000, rotation, viewport.first, viewport.second, out))
                val u = out[0] + 0.25f * (out[2] - out[0]) + 0.25f * (out[4] - out[0])
                val v = out[1] + 0.25f * (out[3] - out[1]) + 0.25f * (out[5] - out[1])
                assertEquals("u, rotation $rotation $viewport", 0.5f, u, eps)
                assertEquals("v, rotation $rotation $viewport", 0.5f, v, eps)
            }
        }
    }

    @Test
    fun negativeRotationCountsTheSameAsItsPositiveTurn() {
        val a = FloatArray(6)
        assertTrue(CameraBackgroundUv.compute(4000, 3000, -90, 1080f, 2400f, a))
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 270, 1080f, 2400f, out))
        assertArrayEquals(out, a)
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 450, 1080f, 2400f, a))
        assertTrue(CameraBackgroundUv.compute(4000, 3000, 90, 1080f, 2400f, out))
        assertArrayEquals(out, a)
    }

    @Test
    fun theCroppedWidthMatchesWhatTheFieldOfViewCalculatorAssumes() {
        // The overlay's FOV and the picture's crop must come from the same FILL_CENTER fit. For a
        // 4:3 sensor in a 20:9 portrait viewport the calculator narrows the horizontal tangent by
        // viewportAspect / sensorAspect; the picture must show exactly that share of its width.
        val sensorW = 6.4f
        val sensorH = 4.8f
        val focal = 6.9f
        val raw = CameraFovCalculator.computeHorizontalFov(sensorW, sensorH, focal, isPortrait = true)
        val effective = CameraFovCalculator.computeEffectiveHorizontalFov(sensorW, sensorH, focal, 1080f, 2400f, isPortrait = true)
        val calculatorShare = tan(Math.toRadians(effective / 2.0)) / tan(Math.toRadians(raw / 2.0))

        // Rotation 90: the upright picture's x axis is the buffer's v axis.
        val left = pointAt(0.0, 0.5, 90, 4000, 3000, 1080f, 2400f)
        val right = pointAt(1.0, 0.5, 90, 4000, 3000, 1080f, 2400f)
        val uvShare = abs(left.second - right.second).toDouble()
        assertEquals(calculatorShare, uvShare, 1e-4)
    }

    @Test
    fun degenerateInputsAreRefusedAndLeaveTheOutputAlone() {
        val untouched = floatArrayOf(9f, 9f, 9f, 9f, 9f, 9f)
        val a = untouched.copyOf()
        assertFalse(CameraBackgroundUv.compute(0, 3000, 0, 1080f, 2400f, a))
        assertFalse(CameraBackgroundUv.compute(4000, 0, 0, 1080f, 2400f, a))
        assertFalse(CameraBackgroundUv.compute(4000, 3000, 0, 0f, 2400f, a))
        assertFalse(CameraBackgroundUv.compute(4000, 3000, 0, 1080f, 0f, a))
        assertFalse(CameraBackgroundUv.compute(4000, 3000, 45, 1080f, 2400f, a))
        assertArrayEquals(untouched, a)
        assertFalse(CameraBackgroundUv.bufferPointAt(0.0, 0.0, 4000, 3000, 30, 1080f, 2400f, point))
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("element $i", expected[i], actual[i], eps)
    }
}

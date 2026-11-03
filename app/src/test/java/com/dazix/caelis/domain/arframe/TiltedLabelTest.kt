package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TiltedLabelTest {

    private val angle = Math.toRadians(60.0).toFloat()

    /** Sensor-style device -> ENU for a phone looking north at the horizon, rolled by [rollDeg]. */
    private fun rolledDeviceToEnu(rollDeg: Double): FloatArray {
        val r = Math.toRadians(rollDeg)
        val m = FloatArray(16)
        // Device right, up and back in ENU, laid out as CelestialMatrixCalculator.computeManualRotationMatrix.
        m[0] = cos(r).toFloat(); m[4] = 0f; m[8] = sin(r).toFloat()
        m[1] = -sin(r).toFloat(); m[5] = 0f; m[9] = cos(r).toFloat()
        m[2] = 0f; m[6] = -1f; m[10] = 0f
        m[15] = 1f
        return m
    }

    private fun projector(rollDeg: Double): SkyProjector {
        val model = FloatArray(16)
        SkyFrameMath.cameraModelMatrix(rolledDeviceToEnu(rollDeg), 0, model)
        return SkyProjector().also { it.update(model, 1000f, 600f, 60f) }
    }

    @Test
    fun `on a level horizon the text starts at the lifted anchor and rises at 60 degrees`() {
        val box = TiltedLabel()
        box.place(200f, 300f, 0f, -1f, 10f, angle, 100f, 20f, 0f, 10f)

        assertEquals(0.5f, box.axisX, 1e-5f)
        assertEquals(-0.8660254f, box.axisY, 1e-5f)
        // The pivot is 10 px straight up; the centre is half the length along the axis from there.
        assertEquals(200f + 50f * 0.5f, box.centerX, 1e-3f)
        assertEquals(290f - 50f * 0.8660254f, box.centerY, 1e-3f)
        assertEquals(50f, box.halfLength, 0f)
        assertEquals(10f, box.halfThickness, 0f)
    }

    @Test
    fun `a rolled horizon turns the whole label with it`() {
        val level = TiltedLabel()
        level.place(0f, 0f, 0f, -1f, 10f, angle, 100f, 20f, 0f, 10f)
        for (rollDeg in listOf(30.0, -30.0)) {
            val roll = Math.toRadians(rollDeg).toFloat()
            // The vertical leaning by +roll is (0, -1) turned clockwise on a Y-down screen.
            val box = TiltedLabel()
            box.place(0f, 0f, sin(roll), -cos(roll), 10f, angle, 100f, 20f, 0f, 10f)
            val c = cos(roll)
            val s = sin(roll)
            assertEquals("axis x at $rollDeg", level.axisX * c - level.axisY * s, box.axisX, 1e-5f)
            assertEquals("axis y at $rollDeg", level.axisX * s + level.axisY * c, box.axisY, 1e-5f)
            assertEquals("centre x at $rollDeg", level.centerX * c - level.centerY * s, box.centerX, 1e-3f)
            assertEquals("centre y at $rollDeg", level.centerX * s + level.centerY * c, box.centerY, 1e-3f)
        }
    }

    @Test
    fun `the axis-aligned bounds cover every corner of the turned sprite`() {
        val box = TiltedLabel()
        box.place(500f, 300f, 0.3f, -0.9539392f, 12f, angle, 180f, 24f, 3f, 11f)
        for ((dx, dy) in listOf(0f to 0f, 180f to 0f, 180f to 24f, 0f to 24f)) {
            // Corner from the pivot, as label.mat lays it out: pivot + dx * axis - dy * normal.
            val ox = dx - 3f
            val oy = dy - 11f
            val x = 500f + 0.3f * 12f + ox * box.axisX - oy * box.normalX
            val y = 300f - 0.9539392f * 12f + ox * box.axisY - oy * box.normalY
            assertTrue("corner ($dx, $dy) x", abs(x - box.centerX) <= box.boundsHalfWidth + 1e-3f)
            assertTrue("corner ($dx, $dy) y", abs(y - box.centerY) <= box.boundsHalfHeight + 1e-3f)
        }
    }

    @Test
    fun `parallel neighbours whose bounds overlap but whose text does not do not collide`() {
        val a = TiltedLabel()
        a.place(100f, 300f, 0f, -1f, 10f, angle, 200f, 20f, 0f, 10f)
        val b = TiltedLabel()
        b.place(140f, 300f, 0f, -1f, 10f, angle, 200f, 20f, 0f, 10f)
        val boundsOverlap = abs(a.centerX - b.centerX) < a.boundsHalfWidth + b.boundsHalfWidth &&
            abs(a.centerY - b.centerY) < a.boundsHalfHeight + b.boundsHalfHeight
        assertTrue("the premise: their axis-aligned boxes overlap", boundsOverlap)
        assertFalse(b.overlapsParallel(a.centerX, a.centerY, a.halfLength, a.halfThickness))

        val c = TiltedLabel()
        c.place(110f, 300f, 0f, -1f, 10f, angle, 200f, 20f, 0f, 10f)
        assertTrue("10 px apart the lines cross", c.overlapsParallel(a.centerX, a.centerY, a.halfLength, a.halfThickness))
    }

    @Test
    fun `screenUp is the projected local vertical and follows the roll`() {
        val out = FloatArray(2)
        val p = FloatArray(3)
        val scratch = FloatArray(3)
        for (rollDeg in listOf(0.0, 30.0, -30.0)) {
            val projector = projector(rollDeg)
            for ((az, el) in listOf(0.0 to 0.0, 15.0 to 2.0)) {
                assertTrue(projector.projectAltAz(az, el, p))
                TiltedLabel.screenUp(projector, az, el, p[0], p[1], out, scratch)
                val roll = Math.toRadians(rollDeg)
                // Off the centre the vertical leans a little with the perspective, so a degree of slack.
                assertEquals("up x, roll $rollDeg at ($az, $el)", sin(roll).toFloat(), out[0], 0.03f)
                assertEquals("up y, roll $rollDeg at ($az, $el)", -cos(roll).toFloat(), out[1], 0.03f)
            }
        }
    }

    @Test
    fun `screenUp falls back to the screen's up behind the camera`() {
        val out = FloatArray(2)
        TiltedLabel.screenUp(projector(30.0), 180.0, 0.0, 0f, 0f, out, FloatArray(3))
        assertEquals(0f, out[0], 0f)
        assertEquals(-1f, out[1], 0f)
    }
}

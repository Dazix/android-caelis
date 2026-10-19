package com.dazix.caelis.domain.arframe

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVectorSettleTest {

    /** Row-major 3x3 for a phone pitched about device X by [tiltDeg] and turned about world Z by [yawDeg]. */
    private fun pose(tiltDeg: Double, yawDeg: Double = 0.0): FloatArray {
        val t = Math.toRadians(tiltDeg)
        val y = Math.toRadians(yawDeg)
        // Rz(yaw) * Rx(tilt), device -> world.
        val rx = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cos(t), -sin(t)),
            doubleArrayOf(0.0, sin(t), cos(t)),
        )
        val rz = arrayOf(
            doubleArrayOf(cos(y), -sin(y), 0.0),
            doubleArrayOf(sin(y), cos(y), 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0.0
            for (k in 0..2) s += rz[r][k] * rx[k][c]
            out[r * 3 + c] = s.toFloat()
        }
        return out
    }

    private val ms = 1_000_000L

    @Test
    fun `a game vector still converging is not settled`() {
        val settle = GameVectorSettle()
        // The device log: 10.8 degrees apart at the start, closing with a 3.5 s time constant.
        var settledAt = -1L
        var t = 0L
        while (t <= 15_000L) {
            val disagreement = 10.8 * kotlin.math.exp(-t / 3_500.0)
            if (settle.add(t * ms, pose(80.0), pose(80.0 + disagreement)) && settledAt < 0) settledAt = t
            t += 20L
        }
        // Below 0.1 degrees only after ~16.4 s: not settled within these 15 s.
        assertEquals(-1L, settledAt)
        assertFalse(settle.isSettled)
    }

    @Test
    fun `agreement held for two seconds settles`() {
        val settle = GameVectorSettle()
        var t = 0L
        while (t < 2_000L) {
            assertFalse("not before the hold has passed (t=$t)", settle.add(t * ms, pose(85.0), pose(85.05)))
            t += 20L
        }
        assertTrue(settle.add(2_000L * ms, pose(85.0), pose(85.05)))
    }

    @Test
    fun `a heading difference is not a disagreement`() {
        // The two vectors disagree on north by design; only the tilt says whether the game vector has converged.
        val settle = GameVectorSettle()
        settle.add(0L, pose(70.0, yawDeg = 0.0), pose(70.0, yawDeg = 140.0))
        assertEquals(0.0, settle.disagreementDegrees, 1e-3)
        assertTrue(settle.add(2_000L * ms, pose(70.0, yawDeg = 0.0), pose(70.0, yawDeg = 140.0)))
    }

    @Test
    fun `a break in the agreement restarts the hold`() {
        val settle = GameVectorSettle()
        settle.add(0L, pose(80.0), pose(80.0))
        settle.add(1_500L * ms, pose(80.0), pose(80.5))
        settle.add(1_600L * ms, pose(80.0), pose(80.0))
        assertFalse(settle.add(3_500L * ms, pose(80.0), pose(80.0)))
        assertTrue(settle.add(3_600L * ms, pose(80.0), pose(80.0)))
    }

    @Test
    fun `vectors that never agree settle at the timeout rather than never`() {
        val settle = GameVectorSettle()
        assertFalse(settle.add(0L, pose(80.0), pose(80.4)))
        assertFalse(settle.add(19_999L * ms, pose(80.0), pose(80.4)))
        assertTrue(settle.add(20_000L * ms, pose(80.0), pose(80.4)))
    }

    @Test
    fun `settled stays settled during a fast turn and a reset starts over`() {
        val settle = GameVectorSettle()
        settle.add(0L, pose(80.0), pose(80.0))
        assertTrue(settle.add(2_000L * ms, pose(80.0), pose(80.0)))
        assertTrue(settle.add(2_100L * ms, pose(80.0), pose(84.0)))
        settle.reset()
        assertFalse(settle.isSettled)
        assertFalse(settle.add(2_200L * ms, pose(80.0), pose(80.0)))
    }

    @Test
    fun `16-element matrices read the same row`() {
        val a = pose(60.0)
        val b = pose(61.0)
        fun widen(m: FloatArray) = FloatArray(16).also { out ->
            for (r in 0..2) for (c in 0..2) out[r * 4 + c] = m[r * 3 + c]
            out[15] = 1f
        }
        val settle = GameVectorSettle()
        settle.add(0L, widen(a), widen(b))
        assertEquals(1.0, settle.disagreementDegrees, 1e-3)
    }
}

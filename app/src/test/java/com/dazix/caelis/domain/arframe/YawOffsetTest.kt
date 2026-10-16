package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class YawOffsetTest {

    /** Row-major 4×4 rotation: yaw about Up, then pitch about the device X axis, then roll. */
    private fun orientation(yawDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val z = rotZ(yawDeg)
        val x = rotX(pitchDeg)
        val y = rotY(rollDeg)
        return to4x4(mul(mul(z, x), y))
    }

    private fun rotZ(d: Double): Array<DoubleArray> {
        val c = cos(Math.toRadians(d)); val s = sin(Math.toRadians(d))
        return arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    }

    private fun rotX(d: Double): Array<DoubleArray> {
        val c = cos(Math.toRadians(d)); val s = sin(Math.toRadians(d))
        return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c))
    }

    private fun rotY(d: Double): Array<DoubleArray> {
        val c = cos(Math.toRadians(d)); val s = sin(Math.toRadians(d))
        return arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c))
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }

    private fun to4x4(m: Array<DoubleArray>) = FloatArray(16).also {
        for (i in 0 until 3) for (j in 0 until 3) it[i * 4 + j] = m[i][j].toFloat()
        it[15] = 1f
    }

    /** The left-multiplication `OrientationTracker.applyMagneticDeclination` performs: [[c, s], [-s, c]]. */
    private fun applyDeclinationConvention(g: FloatArray, deg: Double): FloatArray {
        val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg))
        val a = arrayOf(doubleArrayOf(c, s, 0.0), doubleArrayOf(-s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val g3 = Array(3) { i -> DoubleArray(3) { j -> g[i * 4 + j].toDouble() } }
        return to4x4(mul(a, g3))
    }

    @Test
    fun `recovers the heading between two frames in the declination convention`() {
        val g = orientation(yawDeg = 33.0, pitchDeg = 80.0, rollDeg = 4.0)
        for (psi in listOf(-170.0, -21.0, -7.3, 0.0, 14.2, 90.0, 179.0)) {
            val m = applyDeclinationConvention(g, psi)
            assertEquals("psi $psi", psi, YawOffset.extractDegrees(m, g).toDouble(), 1e-3)
        }
    }

    @Test
    fun `stays defined with the camera at the zenith and the nadir`() {
        // A back camera looking straight up or down has no azimuth; the heading between the
        // two frames must still come out, because it is a property of the frames, not of the view.
        for (pitch in listOf(0.0, 180.0)) {
            val g = orientation(yawDeg = 120.0, pitchDeg = pitch, rollDeg = 0.0)
            val m = applyDeclinationConvention(g, 12.0)
            assertEquals("pitch $pitch", 12.0, YawOffset.extractDegrees(m, g).toDouble(), 1e-3)
        }
    }

    @Test
    fun `a small tilt disagreement between the fusions barely moves the heading`() {
        val g = orientation(yawDeg = 200.0, pitchDeg = 85.0, rollDeg = 0.0)
        // The two fusions never agree on gravity exactly: half a degree of extra pitch on one side.
        val tilted = orientation(yawDeg = 200.0, pitchDeg = 85.5, rollDeg = 0.0)
        val m = applyDeclinationConvention(tilted, -9.0)
        assertEquals(-9.0, YawOffset.extractDegrees(m, g).toDouble(), 0.1)
    }
}

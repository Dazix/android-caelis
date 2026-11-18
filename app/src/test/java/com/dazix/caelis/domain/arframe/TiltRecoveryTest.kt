package com.dazix.caelis.domain.arframe

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltRecoveryTest {

    private val g = TiltRecovery.STANDARD_GRAVITY
    private val stepNs = 5_000_000L // 200 Hz

    private fun rx(deg: Double): Array<DoubleArray> {
        val a = Math.toRadians(deg)
        return arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cos(a), -sin(a)),
            doubleArrayOf(0.0, sin(a), cos(a)),
        )
    }

    private fun ry(deg: Double): Array<DoubleArray> {
        val a = Math.toRadians(deg)
        return arrayOf(
            doubleArrayOf(cos(a), 0.0, sin(a)),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-sin(a), 0.0, cos(a)),
        )
    }

    private fun rz(deg: Double): Array<DoubleArray> {
        val a = Math.toRadians(deg)
        return arrayOf(
            doubleArrayOf(cos(a), -sin(a), 0.0),
            doubleArrayOf(sin(a), cos(a), 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { a[r][it] * b[it][c] } } }

    /** A phone held upright, camera towards [yawDeg] and raised by [elevationDeg]: device -> world. */
    private fun truePose(yawDeg: Double, elevationDeg: Double) = mul(rz(-yawDeg), rx(90.0 + elevationDeg))

    private fun row16(m: Array<DoubleArray>) = FloatArray(16).also {
        for (r in 0..2) for (c in 0..2) it[r * 4 + c] = m[r][c].toFloat()
        it[15] = 1f
    }

    /** Accelerometer reading of a still phone: world up in device coordinates, times g. */
    private fun gravity(pose: Array<DoubleArray>) = DoubleArray(3) { pose[2][it] * g }

    private fun elevationDeg(m: FloatArray) = Math.toDegrees(kotlin.math.asin((-m[10]).toDouble().coerceIn(-1.0, 1.0)))

    private fun azimuthDeg(m: FloatArray) = Math.toDegrees(atan2(-m[2].toDouble(), -m[6].toDouble()))

    /** Angle between the corrected matrix's idea of up and the true one. */
    private fun upErrorDeg(corrected: FloatArray, truth: Array<DoubleArray>): Double {
        val dot = (0..2).sumOf { corrected[8 + it] * truth[2][it] }
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun feed(
        recovery: TiltRecovery,
        fused: FloatArray,
        accel: DoubleArray,
        seconds: Double,
        rateRadS: Double = 0.0,
        startNs: Long = 0L,
    ): Long {
        var t = startNs
        repeat((seconds * 200).toInt()) {
            recovery.add(t, fused, accel[0].toFloat(), accel[1].toFloat(), accel[2].toFloat(), rateRadS)
            t += stepNs
        }
        return t
    }

    /** 0.3 s of a 300 °/s swing, the accelerometer measuring the hand; returns where it ended. */
    private fun swing(recovery: TiltRecovery, fused: FloatArray, startNs: Long = 0L): Long =
        feed(recovery, fused, doubleArrayOf(4.0, 13.0, -3.0), seconds = 0.3, rateRadS = Math.toRadians(300.0), startNs = startNs)

    @Test
    fun `without a swing gravity is not consulted at all`() {
        // The third version followed gravity slowly at rest too, and next to the fusion alone a slight
        // unrest was still visible on the device.
        val truth = truePose(0.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        feed(recovery, fused, gravity(truth), seconds = 10.0)
        assertEquals(0.0, recovery.correctionDegrees, 1e-12)
    }

    @Test
    fun `after the swing's window the correction fades out`() {
        val truth = truePose(0.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        // Past the 4 s window: learnt, and already fading for half a second.
        val t = feed(recovery, fused, gravity(truth), seconds = 4.5, startNs = swing(recovery, fused))
        val learnt = recovery.correctionDegrees
        assertTrue("learnt $learnt", learnt > 1.5)
        // The fusion has caught up by now; the correction must leave without a step.
        var previous = learnt
        var biggestStep = 0.0
        var at = t
        repeat(200 * 12) {
            recovery.add(at, row16(truth), gravity(truth)[0].toFloat(), gravity(truth)[1].toFloat(), gravity(truth)[2].toFloat(), 0.0)
            biggestStep = maxOf(biggestStep, abs(recovery.correctionDegrees - previous))
            previous = recovery.correctionDegrees
            at += stepNs
        }
        assertTrue("left ${recovery.correctionDegrees}", recovery.correctionDegrees < 0.05)
        assertTrue("step $biggestStep", biggestStep < 0.02)
    }

    @Test
    fun `a hand's twitches at rest do not shake the line`() {
        // The second version: every small movement restarted the error from one raw (tremor) sample,
        // which stood more than 0.3° off and fired the fast speed. Exact fusion, a tremor of ±0.5°
        // and a 15 °/s twitch for 0.1 s every 0.8 s, for 20 s.
        val truth = truePose(250.0, 0.0)
        val fused = row16(truth)
        val clean = gravity(truth)
        val random = java.util.Random(11)
        val recovery = TiltRecovery()
        var t = 0L
        var maxCorrection = 0.0
        for (i in 0 until 200 * 20) {
            val twitch = i % 160 < 20
            val rate = if (twitch) Math.toRadians(15.0) else Math.toRadians(1.0)
            val ax = clean[0] + random.nextGaussian() * 0.08
            val ay = clean[1] + random.nextGaussian() * 0.08
            val az = clean[2] + random.nextGaussian() * 0.08
            recovery.add(t, fused, ax.toFloat(), ay.toFloat(), az.toFloat(), rate)
            if (i > 200 * 3) maxCorrection = maxOf(maxCorrection, recovery.correctionDegrees)
            t += stepNs
        }
        assertTrue("correction $maxCorrection", maxCorrection < 0.08)
    }

    @Test
    fun `a fused pose 2 degrees high is mostly back on gravity within a second and fully later`() {
        // Camera towards north, so a fusion world tilted about east reads the camera 2° higher.
        val truth = truePose(0.0, -1.0)
        val fused = row16(mul(rx(2.0), truth))
        assertEquals(2.0, elevationDeg(fused) - elevationDeg(row16(truth)), 0.01)

        val recovery = TiltRecovery()
        val swingEnd = swing(recovery, fused)
        val t = feed(recovery, fused, gravity(truth), seconds = 1.0, startNs = swingEnd)
        val afterOne = fused.copyOf().also { recovery.applyTo(it) }
        // The fast band takes it to the edge of the slow one: from 2° to about the 0.3° threshold.
        assertTrue("up error ${upErrorDeg(afterOne, truth)}", upErrorDeg(afterOne, truth) < 0.35)

        // Meanwhile the fusion converges by itself (here: at once), and the correction follows it out.
        val exact = row16(truth)
        feed(recovery, exact, gravity(truth), seconds = 15.0, startNs = t)
        val settled = exact.copyOf().also { recovery.applyTo(it) }
        assertTrue("up error ${upErrorDeg(settled, truth)}", upErrorDeg(settled, truth) < 0.05)
        assertEquals(elevationDeg(row16(truth)), elevationDeg(settled), 0.05)
    }

    @Test
    fun `the first samples after a swing are not used`() {
        val truth = truePose(0.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        val a = gravity(truth)
        var t = swing(recovery, fused)
        // 0.24 s of stillness: inside the hold, where the swing's deceleration still is.
        repeat(48) {
            assertFalse(recovery.add(t, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), 0.0))
            t += stepNs
        }
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `hand tremor after a swing barely moves the overlay`() {
        // An exact fusion, and an accelerometer with the shake of a hand: ±0.05 m/s² (~0.3°) at 10 Hz
        // plus white noise. The first version followed this with a quarter second and bobbed visibly.
        val truth = truePose(250.0, 0.0)
        val fused = row16(truth)
        val clean = gravity(truth)
        val random = java.util.Random(7)
        val recovery = TiltRecovery()
        var t = swing(recovery, fused)
        var maxAfterSettle = 0.0
        for (i in 0 until 200 * 4) {
            val tremor = 0.05 * sin(2 * Math.PI * 10.0 * i / 200.0)
            val ax = clean[0] + tremor + random.nextGaussian() * 0.03
            val ay = clean[1] + random.nextGaussian() * 0.03
            val az = clean[2] - tremor + random.nextGaussian() * 0.03
            recovery.add(t, fused, ax.toFloat(), ay.toFloat(), az.toFloat(), Math.toRadians(1.0))
            if (i > 200) maxAfterSettle = maxOf(maxAfterSettle, recovery.correctionDegrees)
            t += stepNs
        }
        assertTrue("correction $maxAfterSettle", maxAfterSettle < 0.06)
    }

    @Test
    fun `the correction leaves the heading of a level camera alone`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(ry(1.5), mul(rx(1.5), truth)))
        val recovery = TiltRecovery()
        feed(recovery, fused, gravity(truth), seconds = 3.5, startNs = swing(recovery, fused))

        val corrected = fused.copyOf().also { recovery.applyTo(it) }
        val truthAz = azimuthDeg(row16(truth))
        assertEquals(truthAz, azimuthDeg(corrected), 0.05)
        assertTrue(upErrorDeg(corrected, truth) < 0.35)
    }

    @Test
    fun `nothing is learnt while the phone turns fast`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        feed(recovery, fused, gravity(truth), seconds = 1.0, rateRadS = Math.toRadians(30.0))
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `a moving phone keeps the correction it had`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        val t = feed(recovery, fused, gravity(truth), seconds = 2.0, startNs = swing(recovery, fused))
        val held = recovery.correctionDegrees
        assertTrue("held $held", held > 1.6)
        // A swing: the accelerometer measures the hand, and the correction must not chase it.
        feed(recovery, fused, doubleArrayOf(3.0, 14.0, -2.0), seconds = 0.5, rateRadS = 5.0, startNs = t)
        assertEquals(held, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `hand acceleration is not read as gravity`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val accel = gravity(truth).map { it * 1.1 }.toDoubleArray() // ~1 m/s² over 1 g
        val recovery = TiltRecovery()
        feed(recovery, fused, accel, seconds = 1.0, startNs = swing(recovery, fused))
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `an error far beyond a fusion lag is ignored`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(12.0), truth))
        val recovery = TiltRecovery()
        feed(recovery, fused, gravity(truth), seconds = 1.0, startNs = swing(recovery, fused))
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `a slow pan with an exact fusion stays uncorrected`() {
        val recovery = TiltRecovery()
        var t = 0L
        var maxCorrection = 0.0
        // 3 °/s around the vertical for 5 s, each sample with its own pose and gravity.
        for (i in 0 until 1000) {
            val truth = truePose(250.0 + 3.0 * i / 200.0, -1.0)
            val fused = row16(truth)
            val a = gravity(truth)
            recovery.add(t, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), Math.toRadians(3.0))
            maxCorrection = maxOf(maxCorrection, recovery.correctionDegrees)
            t += stepNs
        }
        assertTrue("correction $maxCorrection", maxCorrection < 0.01)
    }

    @Test
    fun `a gap in the samples is not a long stillness`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        val a = gravity(truth)
        recovery.add(0L, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), 0.0)
        recovery.add(10_000_000_000L, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), 0.0)
        recovery.add(10_005_000_000L, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), 0.0)
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
    }

    @Test
    fun `reset forgets the correction and its clock`() {
        val truth = truePose(250.0, 0.0)
        val fused = row16(mul(rx(2.0), truth))
        val recovery = TiltRecovery()
        feed(recovery, fused, gravity(truth), seconds = 1.0)
        recovery.reset()
        assertEquals(0.0, recovery.correctionDegrees, 1e-9)
        val untouched = fused.copyOf()
        recovery.applyTo(untouched)
        assertTrue(untouched.contentEquals(fused))
        val a = gravity(truth)
        assertFalse(recovery.add(5L, fused, a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), 0.0))
        assertTrue(abs(recovery.correctionDegrees) < 1e-9)
    }
}

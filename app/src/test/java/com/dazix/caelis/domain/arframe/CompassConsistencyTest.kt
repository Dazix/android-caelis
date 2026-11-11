package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sequences below are the `CaelisCompass` device logs of 2026-11-11, one row per second: the
 * game vector's camera azimuth and ψ (rotation vector heading minus game vector heading).
 */
class CompassConsistencyTest {

    private val second = 1_000_000_000L

    /** Feeds one row per second at camera elevation 0, interpolating 10 samples between rows. */
    private fun CompassConsistency.feed(startSecond: Int, rows: List<Pair<Float, Float>>): Int {
        var t = startSecond
        for (i in rows.indices) {
            val (az, psi) = rows[i]
            val (nextAz, nextPsi) = rows.getOrElse(i + 1) { rows[i] }
            val dAz = shortWay(nextAz - az)
            val dPsi = shortWay(nextPsi - psi)
            for (k in 0 until 10) {
                val f = k / 10f
                addPair(t * second + k * second / 10, az + dAz * f, 0f, psi + dPsi * f)
            }
            t++
        }
        return t
    }

    private fun shortWay(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // Pixel 7 Pro, 18:07:02-18:07:16: a full slow turn, ψ within 1.8°. Its compass was right.
    private val pixel7Turn = listOf(
        94f to -137.1f, 110f to -137.2f, 129f to -137.3f, 160f to -137.4f, -161f to -137.4f,
        -120f to -137.4f, -77f to -137.4f, -45f to -137.3f, -17f to -137.2f, -10f to -137.2f,
        -6f to -137.2f, 15f to -137.4f, 41f to -137.8f, 60f to -138.1f, 69f to -138.2f,
    )

    // Pixel 10 Pro, 18:42:24-18:42:58: a slow turn, ψ swinging with direction by ±22°, the same on
    // both turns. The overlay was tens of degrees out away from where it had been aligned.
    private val pixel10Turn = listOf(
        74f to -106.2f, 90f to -108.0f, 105f to -110.8f, 125f to -117.0f, 146f to -123.1f,
        170f to -129.0f, -174f to -134.9f, -150f to -139.0f, -131f to -142.7f, -110f to -142.5f,
        -95f to -141.6f, -75f to -130.0f, -57f to -119.4f, -35f to -108.0f, -13f to -98.7f,
        5f to -97.5f, 22f to -97.4f, 40f to -97.4f, 60f to -97.4f, 73f to -100.4f,
    )

    @Test
    fun `before the phone has turned there is no verdict`() {
        val c = CompassConsistency()
        c.feed(0, List(10) { 73f to -105f })
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
    }

    @Test
    fun `a slow full turn with a steady psi is a consistent compass`() {
        val c = CompassConsistency()
        c.feed(0, pixel7Turn)
        assertEquals(CompassEvidence.CONSISTENT, c.evidence)
    }

    @Test
    fun `a psi that swings with direction is an inconsistent compass`() {
        val c = CompassConsistency()
        c.feed(0, pixel10Turn)
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
    }

    @Test
    fun `the verdict is reached within a quarter turn`() {
        val c = CompassConsistency()
        // Pixel 10 Pro, first test: from 120.6° to 143.1° within 100° of turning.
        c.feed(0, listOf(60f to 120.6f, 85f to 128f, 110f to 133.8f, 135f to 141.5f, 160f to 143.1f))
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
    }

    @Test
    fun `a still phone keeps its verdict while psi drifts`() {
        val c = CompassConsistency()
        val t = c.feed(0, pixel7Turn)
        // Pixel 7 Pro by the radiator: ψ +3.3° in 2 s with the phone still. No turn, no evidence.
        c.feed(t, listOf(66f to -138f, 66f to -136.5f, 66f to -135.2f, 66f to -134.9f, 66f to -134.7f))
        assertEquals(CompassEvidence.CONSISTENT, c.evidence)
    }

    @Test
    fun `a consistent turn clears an earlier inconsistent one once it has expired`() {
        val c = CompassConsistency()
        val t = c.feed(0, pixel10Turn)
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
        // 30 s later, after a recalibration, a clean turn at a different ψ.
        c.feed(t + 30, pixel7Turn)
        assertEquals(CompassEvidence.CONSISTENT, c.evidence)
    }

    @Test
    fun `a fast turn is not evidence`() {
        val c = CompassConsistency()
        // A whole turn in one second, ψ jumping as on the Pixel 10 Pro during a figure eight.
        for (k in 0 until 20) {
            c.addPair(k * second / 20, k * 18f, 0f, if (k % 2 == 0) 120f else 80f)
        }
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
    }

    @Test
    fun `a camera pointing at the zenith has no azimuth to compare`() {
        val c = CompassConsistency()
        for (k in 0 until 200) {
            c.addPair(k * second / 10, (k * 2f) % 360f, 75f, if (k < 100) 0f else 30f)
        }
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
    }

    @Test
    fun `psi across the wrap is compared the short way round`() {
        val c = CompassConsistency()
        c.feed(0, List(15) { i -> (i * 25f) to (if (i % 2 == 0) 179f else -179f) })
        assertEquals(CompassEvidence.CONSISTENT, c.evidence)
    }

    @Test
    fun `a field far from the expected strength is inconsistent at once, after two seconds`() {
        val c = CompassConsistency()
        c.setExpectedField(49.8f)
        c.feed(0, pixel7Turn)
        // Pixel 10 Pro against a magnet: 201.8 µT.
        c.addField(20 * second, 201.8f)
        c.addField(21 * second, 201.8f)
        assertEquals("one second is a passing magnet", CompassEvidence.CONSISTENT, c.evidence)
        c.addField(22 * second, 201.8f)
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
    }

    @Test
    fun `a field back in range for two seconds leaves the turn to decide`() {
        val c = CompassConsistency()
        c.setExpectedField(49.8f)
        c.feed(0, pixel7Turn)
        c.addField(20 * second, 201.8f)
        c.addField(22 * second, 201.8f)
        c.addField(23 * second, 49f)
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
        c.addField(25 * second, 49f)
        assertEquals(CompassEvidence.CONSISTENT, c.evidence)
    }

    @Test
    fun `a radiator's field is not a magnet`() {
        val c = CompassConsistency()
        c.setExpectedField(49.8f)
        // Pixel 7 Pro by the radiator 55.6 µT, Pixel 10 Pro in a room 67.3 µT.
        for (s in 0..5) c.addField(s * second, if (s < 3) 55.6f else 67.3f)
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
    }

    @Test
    fun `without a position the Earth's whole range is the expectation`() {
        val c = CompassConsistency()
        for (s in 0..3) c.addField(s * second, 70f)
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
        for (s in 4..7) c.addField(s * second, 15f)
        assertEquals(CompassEvidence.INCONSISTENT, c.evidence)
    }

    @Test
    fun `reset forgets everything`() {
        val c = CompassConsistency()
        c.feed(0, pixel10Turn)
        c.reset()
        assertEquals(CompassEvidence.UNKNOWN, c.evidence)
    }
}

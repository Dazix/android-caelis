package com.dazix.caelis.ar

import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.domain.arframe.RollEase
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sun AR's pan under a virtual place froze the live matrix as it was, so a phone held at a slant
 * kept the ridge slanted for the whole pan. Night Sky's pan drops the roll; this one now does too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationPanLevelTest {

    private var nowNs = 1_000_000_000L

    private fun tracker() = OrientationTracker(ApplicationProvider.getApplicationContext())
        .also { it.clockNs = { nowNs } }

    private fun settle() {
        nowNs += RollEase.DURATION_NS
    }

    /** A pose looking at [azDeg]/[elDeg] with the phone turned [rollDeg] about its camera axis. */
    private fun rolledPose(azDeg: Double, elDeg: Double, rollDeg: Double): FloatArray {
        val m = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(azDeg, elDeg, m)
        val c = cos(Math.toRadians(rollDeg)).toFloat()
        val s = sin(Math.toRadians(rollDeg)).toFloat()
        for (row in 0..2) {
            val x = m[row * 4]
            val y = m[row * 4 + 1]
            m[row * 4] = c * x + s * y
            m[row * 4 + 1] = -s * x + c * y
        }
        return m
    }

    private fun azimuth(m: FloatArray): Double {
        val az = Math.toDegrees(atan2(-m[2].toDouble(), -m[6].toDouble()))
        return if (az < 0) az + 360 else az
    }

    private fun elevation(m: FloatArray): Double = Math.toDegrees(asin(-m[10].toDouble()))

    /** Level: the device's right axis has no vertical component. */
    private fun assertLevel(m: FloatArray) {
        assertEquals("device X must be horizontal", 0.0, m[8].toDouble(), 1e-5)
    }

    private fun assertOrthonormal(m: FloatArray) {
        for (a in 0..2) {
            for (b in 0..2) {
                val dot = (0..2).sumOf { r -> (m[r * 4 + a] * m[r * 4 + b]).toDouble() }
                assertEquals("columns $a.$b", if (a == b) 1.0 else 0.0, dot, 1e-5)
            }
        }
    }

    @Test
    fun `the pan starts level and keeps where the camera looks`() {
        val live = rolledPose(azDeg = 120.0, elDeg = 10.0, rollDeg = 25.0)
        assertTrue("the fixture must be rolled", abs(live[8]) > 0.3f)
        val tracker = tracker()

        tracker.onPanGesture(0f, 0f, 0.05f, live)
        settle()

        val m = tracker.getAdjustedMatrix()
        assertTrue(tracker.isSensorTrackingPaused.value)
        assertLevel(m)
        assertOrthonormal(m)
        assertEquals(120.0, azimuth(m), 0.01)
        assertEquals(10.0, elevation(m), 0.01)
    }

    @Test
    fun `the sky turns level smoothly instead of jumping`() {
        val live = rolledPose(azDeg = 120.0, elDeg = 10.0, rollDeg = 25.0)
        val level = FloatArray(16).also { OrientationTracker.levelPanMatrix(live, it) }
        val tracker = tracker()

        tracker.onPanGesture(0f, 0f, 0.05f, live)
        val atTouch = RollEase.rollAgainst(tracker.getAdjustedMatrix(), level)
        assertEquals("the first frame keeps the phone's roll", 25.0, atTouch.toDouble(), 0.01)

        var previous = atTouch
        repeat(20) {
            nowNs += RollEase.DURATION_NS / 20
            val m = tracker.getAdjustedMatrix()
            assertOrthonormal(m)
            assertEquals("the view direction holds while it levels", 120.0, azimuth(m), 0.01)
            val roll = RollEase.rollAgainst(m, level)
            assertTrue("roll $roll after $previous must only shrink", roll <= previous + 1e-4f && roll >= -1e-4f)
            // No frame may jump more than a fifth of the roll: cubic ease-out's first step is 0.14.
            assertTrue("step ${previous - roll} too large", previous - roll <= 25f * 0.15f)
            previous = roll
        }
        assertEquals(0.0, previous.toDouble(), 1e-4)
        assertLevel(tracker.getAdjustedMatrix())
    }

    @Test
    fun `dragging both ways keeps it level`() {
        val tracker = tracker()
        tracker.onPanGesture(0f, 0f, 0.05f, rolledPose(azDeg = 300.0, elDeg = -5.0, rollDeg = -40.0))

        tracker.onPanGesture(deltaX = 400f, deltaY = 0f, pixelsToDegreesFactor = 0.05f)
        tracker.onPanGesture(deltaX = -120f, deltaY = 300f, pixelsToDegreesFactor = 0.05f)
        settle()

        val m = tracker.getAdjustedMatrix()
        assertLevel(m)
        assertOrthonormal(m)
        // Right drag 20° turns the view left, left drag 6° back; up drag 15° raises it.
        assertEquals(286.0, azimuth(m), 0.01)
        assertEquals(10.0, elevation(m), 0.01)
    }

    @Test
    fun `a slanted phone near the zenith takes its azimuth from its up axis`() {
        val live = rolledPose(azDeg = 70.0, elDeg = 88.0, rollDeg = 30.0)
        val out = FloatArray(16)

        OrientationTracker.levelPanMatrix(live, out)

        assertFalse(out.any { it.isNaN() })
        assertLevel(out)
        assertOrthonormal(out)
        assertEquals(OrientationTracker.MAX_GESTURE_PITCH.toDouble(), elevation(out), 0.01)
        // The roll swings the up axis's horizontal part by up to the roll itself; it must stay on
        // the side the phone faces, not spin to an arbitrary direction.
        val diff = abs(((azimuth(out) - 70.0 + 540.0) % 360.0) - 180.0)
        assertTrue("azimuth ${azimuth(out)} too far from 70°", diff <= 31.0)
    }

    @Test
    fun `looking straight down keeps the facing side too`() {
        val live = rolledPose(azDeg = 200.0, elDeg = -89.5, rollDeg = 0.0)
        val out = FloatArray(16)

        OrientationTracker.levelPanMatrix(live, out)

        assertLevel(out)
        assertEquals(200.0, azimuth(out), 0.5)
        assertEquals(-OrientationTracker.MAX_GESTURE_PITCH.toDouble(), elevation(out), 0.01)
        assertEquals(1.0, sqrt(out[0] * out[0] + out[4] * out[4].toDouble()), 1e-5)
    }
}

package com.dazix.caelis.ar

import android.hardware.SensorEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * The north ARCore's world is aligned to is the sensor path *without* the standing calibration.
 * If the calibration leaked into it, a nudge would be counted twice (once in the yaw, once as the
 * calibration applied on top) and, worse, would only take effect as slowly as the heading fusion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationReferenceMatrixTest {

    private val ms = 1_000_000L

    private fun trackerWithHistory(): OrientationTracker {
        val tracker = OrientationTracker(ApplicationProvider.getApplicationContext())
        // The phone turning about Up: 11 rotation-vector samples, 10 ms apart.
        for (i in 0..10) {
            val half = (0.3 + 0.01 * i) / 2.0
            val event = ShadowSensorManager.createSensorEvent(4)
            event.values[0] = 0f
            event.values[1] = 0f
            event.values[2] = sin(half).toFloat()
            event.values[3] = cos(half).toFloat()
            setTimestamp(event, 1_000 * ms + i * 10 * ms)
            tracker.onSensorChanged(event)
        }
        return tracker
    }

    private fun setTimestamp(event: SensorEvent, ns: Long) {
        SensorEvent::class.java.getField("timestamp").setLong(event, ns)
    }

    @Test
    fun `the reference is the synchronized pose with the calibration taken out`() {
        val tracker = trackerWithHistory()
        val at = 1_000 * ms + 45 * ms
        tracker.setCalibration(azimuthDeg = 7f, pitchDeg = 3f)

        val calibrated = FloatArray(16)
        val reference = FloatArray(16)
        assertTrue(tracker.getSynchronizedMatrix(at, calibrated))
        assertTrue(tracker.getReferenceMatrix(at, reference))

        val expected = FloatArray(16)
        OrientationTracker.applyCalibration(reference, 0f, 7f, 3f, FloatArray(16), expected)
        for (i in 0 until 16) assertEquals("element $i", expected[i], calibrated[i], 1e-5f)
    }

    @Test
    fun `with no calibration the two agree`() {
        val tracker = trackerWithHistory()
        val at = 1_000 * ms + 45 * ms
        val a = FloatArray(16)
        val b = FloatArray(16)
        tracker.getSynchronizedMatrix(at, a)
        tracker.getReferenceMatrix(at, b)
        for (i in 0 until 16) assertEquals("element $i", a[i], b[i], 1e-6f)
    }

    @Test
    fun `an instant the history cannot bracket gives no reference and no live fallback`() {
        val tracker = trackerWithHistory()
        val untouched = FloatArray(16) { 9f }
        assertFalse(tracker.getReferenceMatrix(5_000 * ms, untouched))
        assertFalse(tracker.getReferenceMatrix(0L, untouched))
        for (v in untouched) assertEquals(9f, v, 0f)
    }

    @Test
    fun `the history must really cover the instant, which the ring buffer's clamping would hide`() {
        // Samples cover 1000..1100 ms. The ring buffer answers any timestamp by clamping to its nearest
        // end; for a reference that would be a pose from another instant (or another clock) passed
        // off as the right one.
        val tracker = trackerWithHistory()
        val out = FloatArray(16)
        assertTrue("exactly the oldest sample", tracker.getReferenceMatrix(1_000 * ms, out))
        assertTrue("exactly the newest sample", tracker.getReferenceMatrix(1_100 * ms, out))
        assertFalse("before the oldest sample", tracker.getReferenceMatrix(1_000 * ms - 1, out))
        assertTrue("a frame exposed just after the newest sample", tracker.getReferenceMatrix(1_100 * ms + 10 * ms, out))
        assertFalse("further past it than two sample periods", tracker.getReferenceMatrix(1_100 * ms + 10 * ms + 1, out))
    }

    @Test
    fun `on the single-sensor path the heading accuracy is the compass's own, high until it says otherwise`() {
        val tracker = trackerWithHistory()
        // Without both rotation sensors there is no heading lock to take, so nothing here can raise
        // the accuracy above what the compass reports.
        assertEquals(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH, tracker.headingAccuracy)
    }
}

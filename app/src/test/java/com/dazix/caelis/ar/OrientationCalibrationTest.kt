package com.dazix.caelis.ar

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The standing calibration is the only thing that can absorb a compass that reads a few degrees off,
 * and on a screenshot taken through a window it read 2.9° off. It is worth nothing if applying it
 * stops the overlay from tracking the phone, which is exactly what restoring it used to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationCalibrationTest {

    private fun tracker() = OrientationTracker(ApplicationProvider.getApplicationContext())

    @Test
    fun `setting a calibration leaves the sensors running`() {
        val tracker = tracker()

        tracker.setCalibration(azimuthDeg = 2.9f, pitchDeg = -1.3f)

        assertFalse(
            "a stored calibration must not freeze the overlay",
            tracker.isSensorTrackingPaused.value,
        )
        assertEquals(2.9f, tracker.calibrationAzimuthOffsetDeg, 1e-4f)
        assertEquals(-1.3f, tracker.calibrationPitchOffsetDeg, 1e-4f)
    }

    @Test
    fun `the gesture path still freezes, and is what it always was`() {
        val tracker = tracker()

        tracker.setPanOffsets(azimuthDeg = 5f, pitchDeg = 2f)

        assertTrue("dragging still pauses tracking", tracker.isSensorTrackingPaused.value)
        assertEquals(0f, tracker.calibrationAzimuthOffsetDeg, 1e-4f)
    }

    @Test
    fun `committing a pan turns it into the calibration and resumes tracking`() {
        val tracker = tracker()
        tracker.setCalibration(azimuthDeg = 1f, pitchDeg = 0.5f)
        tracker.setPanOffsets(azimuthDeg = 4f, pitchDeg = 1.5f)

        tracker.commitPanAsCalibration()

        assertEquals("the pan is added, not substituted", 5f, tracker.calibrationAzimuthOffsetDeg, 1e-3f)
        assertEquals(2f, tracker.calibrationPitchOffsetDeg, 1e-3f)
        assertEquals(0f, tracker.panAzimuthOffsetDeg, 1e-4f)
        assertEquals(0f, tracker.panPitchOffsetDeg, 1e-4f)
        assertFalse(tracker.isSensorTrackingPaused.value)
    }

    /**
     * Dropping a pan means "stop dragging", not "forget where north is". Losing the calibration here
     * would silently undo the alignment every time the user let go.
     */
    @Test
    fun `resuming tracking keeps the calibration`() {
        val tracker = tracker()
        tracker.setCalibration(azimuthDeg = 3f, pitchDeg = 1f)
        tracker.setPanOffsets(azimuthDeg = 10f, pitchDeg = 5f)

        tracker.resumeSensorTracking()

        assertEquals(3f, tracker.calibrationAzimuthOffsetDeg, 1e-4f)
        assertEquals(1f, tracker.calibrationPitchOffsetDeg, 1e-4f)
        assertEquals(0f, tracker.panAzimuthOffsetDeg, 1e-4f)
        assertFalse(tracker.isSensorTrackingPaused.value)
    }

    @Test
    fun `the calibration is clamped to what a sensor error can plausibly be`() {
        val tracker = tracker()

        tracker.setCalibration(azimuthDeg = 175f, pitchDeg = 90f)

        assertEquals(
            "turning the sky half way round is a mis-drag, not a calibration",
            OrientationTracker.MAX_CALIBRATION_AZIMUTH,
            tracker.calibrationAzimuthOffsetDeg,
            1e-4f,
        )
        assertEquals(
            "a 90° pitch offset is a mis-drag, not a calibration",
            OrientationTracker.MAX_CALIBRATION_PITCH,
            tracker.calibrationPitchOffsetDeg,
            1e-4f,
        )
    }

    /**
     * A small negative correction must stay small and negative. Wrapping it to 355° made the bound
     * meaningless and put the readout a full turn away from what the user had dialled in.
     */
    @Test
    fun `a small westward correction keeps its sign`() {
        val tracker = tracker()

        tracker.setCalibration(azimuthDeg = -2.9f, pitchDeg = 0f)

        assertEquals(-2.9f, tracker.calibrationAzimuthOffsetDeg, 1e-4f)
    }

    @Test
    fun `dragging moves the calibration and never pauses tracking`() {
        val tracker = tracker()

        tracker.nudgeCalibrationBy(-1.5f, 0.5f)
        tracker.nudgeCalibrationBy(-1.4f, 0.8f)

        assertEquals(-2.9f, tracker.calibrationAzimuthOffsetDeg, 1e-3f)
        assertEquals(1.3f, tracker.calibrationPitchOffsetDeg, 1e-3f)
        assertFalse(
            "a drag on the Sun AR screen is a calibration, not a frozen pan",
            tracker.isSensorTrackingPaused.value,
        )
    }

    /**
     * The published matrix has to move as soon as the calibration does. While tracking is paused
     * there is no next sensor sample to fold it into, and the nudge buttons would do nothing.
     */
    @Test
    fun `a calibration rotates the world frame it publishes`() {
        val tracker = tracker()

        tracker.setCalibration(azimuthDeg = 10f, pitchDeg = 0f)
        val rotated = tracker.getLiveMatrix().copyOf()

        tracker.setCalibration(azimuthDeg = 0f, pitchDeg = 0f)
        val identityCalibration = tracker.getLiveMatrix().copyOf()

        assertFalse(
            "the live matrix must differ once a calibration is applied",
            rotated.contentEquals(identityCalibration),
        )
    }
}

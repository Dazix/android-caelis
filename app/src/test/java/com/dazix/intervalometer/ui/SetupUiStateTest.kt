package com.dazix.intervalometer.ui

import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {

    private val calibrated = TapTarget(xFraction = 0.5f, yFraction = 0.9f)

    private val pixel = SetupUiState(
        targetPackage = "com.google.android.GoogleCamera",
        targetLabel = "Camera",
        target = calibrated,
    )

    @Test
    fun `choosing another app drops the previous app's calibration`() {
        val switched = pixel
            .copy(targetPackage = "com.example.other", targetLabel = "Other")
            .retargeted(pixel)

        assertNull(switched.target)
        assertNull(switched.toConfig(1_000L).target)
    }

    @Test
    fun `clearing the app drops the calibration too`() {
        val cleared = pixel.copy(targetPackage = null, targetLabel = null).retargeted(pixel)

        assertNull(cleared.target)
    }

    @Test
    fun `editing anything else keeps the calibration`() {
        val edited = pixel.copy(intervalValue = "20").retargeted(pixel)

        assertEquals(calibrated, edited.target)
    }

    @Test
    fun `re-selecting the same app keeps the calibration`() {
        val same = pixel.copy(targetLabel = "Camera (renamed)").retargeted(pixel)

        assertEquals(calibrated, same.target)
    }

    @Test
    fun `a jitter edit survives because the package did not change`() {
        val jittered = pixel.copy(target = calibrated.copy(jitter = true)).retargeted(pixel)

        assertEquals(true, jittered.target?.jitter)
    }

    @Test
    fun `time-lapse without a chosen app is a periodic tap, not a time-lapse of nothing`() {
        val mode = SetupUiState(mode = ModeChoice.TIMELAPSE, targetPackage = null).triggerMode()

        assertTrue(mode is TriggerMode.PeriodicTap)
        assertNull(mode.targetPackage)
    }

    @Test
    fun `periodic tap keeps the chosen app so the engine can watch the foreground`() {
        val mode = SetupUiState(
            mode = ModeChoice.PERIODIC_TAP,
            targetPackage = "com.example.metronome",
        ).triggerMode()

        assertTrue(mode is TriggerMode.PeriodicTap)
        assertEquals("com.example.metronome", mode.targetPackage)
    }

    @Test
    fun `time-lapse with an app drives that app`() {
        val mode = pixel.triggerMode()

        assertTrue(mode is TriggerMode.Timelapse)
        assertEquals("com.google.android.GoogleCamera", mode.targetPackage)
    }
}

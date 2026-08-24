package com.dazix.intervalometer.domain.nightsky

import com.dazix.intervalometer.ar.AmberNightVisionPalette
import com.dazix.intervalometer.ar.GreenNightVisionPalette
import com.dazix.intervalometer.ar.RedNightVisionPalette
import com.dazix.intervalometer.ar.paletteForTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NightVisionConfigTest {

    @Test
    fun `isTimeInRange with same-day range correctly tests inside and outside`() {
        val start = LocalTime.of(18, 0)
        val end = LocalTime.of(22, 0)

        // Inside
        assertTrue(isTimeInRange(LocalTime.of(18, 0), start, end))
        assertTrue(isTimeInRange(LocalTime.of(20, 30), start, end))
        assertTrue(isTimeInRange(LocalTime.of(21, 59), start, end))

        // Outside
        assertFalse(isTimeInRange(LocalTime.of(17, 59), start, end))
        assertFalse(isTimeInRange(LocalTime.of(22, 0), start, end))
        assertFalse(isTimeInRange(LocalTime.of(23, 0), start, end))
        assertFalse(isTimeInRange(LocalTime.of(10, 0), start, end))
    }

    @Test
    fun `isTimeInRange with overnight cross-midnight range correctly tests inside and outside`() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(4, 0)

        // Inside before midnight
        assertTrue(isTimeInRange(LocalTime.of(22, 0), start, end))
        assertTrue(isTimeInRange(LocalTime.of(23, 30), start, end))
        assertTrue(isTimeInRange(LocalTime.of(23, 59), start, end))

        // Inside after midnight
        assertTrue(isTimeInRange(LocalTime.of(0, 0), start, end))
        assertTrue(isTimeInRange(LocalTime.of(2, 15), start, end))
        assertTrue(isTimeInRange(LocalTime.of(3, 59), start, end))

        // Outside
        assertFalse(isTimeInRange(LocalTime.of(4, 0), start, end))
        assertFalse(isTimeInRange(LocalTime.of(4, 1), start, end))
        assertFalse(isTimeInRange(LocalTime.of(12, 0), start, end))
        assertFalse(isTimeInRange(LocalTime.of(21, 59), start, end))
    }

    @Test
    fun `isTimeInRange when start equals end`() {
        val time = LocalTime.of(12, 0)
        assertFalse(isTimeInRange(time, time, time))
    }

    @Test
    fun `paletteForTheme resolves correct primary, secondary, and shader tints`() {
        val red = paletteForTheme(NightVisionTheme.RED)
        assertEquals(RedNightVisionPalette, red)
        assertEquals(1.0f, red.shaderTintR, 0.001f)
        assertEquals(0.0f, red.shaderTintG, 0.001f)
        assertEquals(0.0f, red.shaderTintB, 0.001f)

        val amber = paletteForTheme(NightVisionTheme.AMBER)
        assertEquals(AmberNightVisionPalette, amber)
        assertEquals(1.0f, amber.shaderTintR, 0.001f)
        assertEquals(0.7f, amber.shaderTintG, 0.001f)
        assertEquals(0.0f, amber.shaderTintB, 0.001f)

        val green = paletteForTheme(NightVisionTheme.GREEN)
        assertEquals(GreenNightVisionPalette, green)
        assertEquals(0.0f, green.shaderTintR, 0.001f)
        assertEquals(1.0f, green.shaderTintG, 0.001f)
        assertEquals(0.0f, green.shaderTintB, 0.001f)
    }

    @Test
    fun `NightVisionState holds isActive and theme state`() {
        val stateRed = NightVisionState(isActive = true, theme = NightVisionTheme.RED)
        assertTrue(stateRed.isActive)
        assertEquals(NightVisionTheme.RED, stateRed.theme)

        val stateAmberOff = NightVisionState(isActive = false, theme = NightVisionTheme.AMBER)
        assertFalse(stateAmberOff.isActive)
        assertEquals(NightVisionTheme.AMBER, stateAmberOff.theme)
    }
}

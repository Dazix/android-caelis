package com.dazix.caelis.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationMathTest {

    private val width = 1080
    private val height = 2400

    private fun tap(x: Float, y: Float) =
        CalibrationMath.screenFractions(x, y, x, y, isDrag = false, widthPx = width, heightPx = height)

    private fun drag(left: Float, top: Float, right: Float, bottom: Float) =
        CalibrationMath.screenFractions(left, top, right, bottom, isDrag = true, width, height)

    @Test
    fun `a tap becomes a point target at that pixel`() {
        val fractions = tap(540f, 2040f)
        assertEquals(0.5f, fractions.x, 0.0001f)
        assertEquals(0.85f, fractions.y, 0.0001f)
        assertEquals(0f, fractions.width, 0f)
        assertEquals(0f, fractions.height, 0f)
    }

    @Test
    fun `a drag is stored by its centre and its size, not by its corner`() {
        val fractions = drag(340f, 1840f, 740f, 2240f)
        assertEquals(0.5f, fractions.x, 0.0001f)
        assertEquals(2040f / height, fractions.y, 0.0001f)
        assertEquals(400f / width, fractions.width, 0.0001f)
        assertEquals(400f / height, fractions.height, 0.0001f)
    }

    @Test
    fun `a drag captured bottom-right to top-left is normalised`() {
        val forwards = drag(340f, 1840f, 740f, 2240f)
        val backwards = drag(740f, 2240f, 340f, 1840f)
        assertEquals(forwards, backwards)
    }

    @Test
    fun `a touch outside the display is clamped into the unit square`() {
        val fractions = tap(-20f, height + 200f)
        assertEquals(0f, fractions.x, 0f)
        assertEquals(1f, fractions.y, 0f)
    }

    @Test
    fun `a degenerate display size cannot divide by zero`() {
        val fractions = CalibrationMath.screenFractions(
            left = 10f,
            top = 10f,
            right = 10f,
            bottom = 10f,
            isDrag = false,
            widthPx = 0,
            heightPx = 0,
        )
        assertEquals(1f, fractions.x, 0f)
        assertEquals(1f, fractions.y, 0f)
    }

    @Test
    fun `size is only recorded for a real drag`() {
        val fractions = CalibrationMath.screenFractions(
            left = 340f,
            top = 1840f,
            right = 344f,
            bottom = 1844f,
            isDrag = false,
            widthPx = width,
            heightPx = height,
        )
        assertEquals(0f, fractions.width, 0f)
        assertEquals(0f, fractions.height, 0f)
        assertEquals(342f / width, fractions.x, 0.0001f)
    }
}

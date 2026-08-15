package com.dazix.intervalometer.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel must never be placed where a dispatched shutter tap can land, because such a tap is
 * injected at input-dispatcher level and would be delivered to the panel instead of to the camera.
 */
class PanelPlacementTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val panelWidth = 400
    private val panelHeight = 300

    /** Where a portrait camera app puts its shutter, and the dispatcher's node-search region. */
    private val shutterRegion = Box(360, 1600, 720, 2400)

    private fun place(x: Int, y: Int, avoid: List<Box>) = PanelPlacement.resolve(
        desiredX = x,
        desiredY = y,
        panelWidth = panelWidth,
        panelHeight = panelHeight,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        avoid = avoid,
    )

    @Test
    fun `keeps the requested position when nothing is in the way`() {
        val position = place(64, 200, listOf(shutterRegion))
        assertEquals(PanelPosition(64, 200), position)
    }

    @Test
    fun `clamps a position that hangs off the screen`() {
        val position = place(-500, 99_999, emptyList())
        assertEquals(PanelPosition(0, screenHeight - panelHeight), position)
    }

    @Test
    fun `moves the panel out of the shutter region it was dragged into`() {
        val avoid = listOf(shutterRegion)
        val position = place(400, 1800, avoid)
        assertTrue(
            "panel at $position still overlaps the shutter region",
            PanelPlacement.isClear(position, panelWidth, panelHeight, avoid),
        )
    }

    @Test
    fun `moves the panel the shortest distance that clears the region`() {
        // 40 px into the top edge of the region: sliding up by 40 is the cheapest escape.
        val avoid = listOf(shutterRegion)
        val position = place(400, shutterRegion.top - panelHeight + 40, avoid)
        assertEquals(PanelPosition(400, shutterRegion.top - panelHeight), position)
    }

    @Test
    fun `clears a calibrated point target inflated into a box`() {
        // A landscape-style calibration on the right edge, nowhere near the bottom-centre third.
        val calibrated = Box(940, 1100, 1000, 1160).inflated(80)
        val avoid = listOf(calibrated, shutterRegion)
        val position = place(700, 1000, avoid)
        assertTrue(
            "panel at $position still overlaps a tap target",
            PanelPlacement.isClear(position, panelWidth, panelHeight, avoid),
        )
    }

    @Test
    fun `clears both regions at once when they surround the requested position`() {
        val calibrated = Box(0, 1200, 1080, 1500)
        val avoid = listOf(calibrated, shutterRegion)
        val position = place(500, 1300, avoid)
        assertTrue(
            "panel at $position still overlaps a tap target",
            PanelPlacement.isClear(position, panelWidth, panelHeight, avoid),
        )
        assertTrue(position.y in 0..(screenHeight - panelHeight))
    }

    @Test
    fun `stays on screen when every position overlaps something`() {
        val everything = listOf(Box(0, 0, screenWidth, screenHeight))
        val position = place(300, 300, everything)
        assertTrue(position.x in 0..(screenWidth - panelWidth))
        assertTrue(position.y in 0..(screenHeight - panelHeight))
        assertFalse(PanelPlacement.isClear(position, panelWidth, panelHeight, everything))
    }

    @Test
    fun `picks the least overlapping position when no position is free`() {
        // Everything but a 100 px strip down the left edge is forbidden.
        val almostEverything = listOf(Box(100, 0, screenWidth, screenHeight))
        val position = place(600, 400, almostEverything)
        assertEquals(0, position.x)
    }

    @Test
    fun `is a pure function of its inputs`() {
        val avoid = listOf(shutterRegion, Box(0, 1200, 1080, 1500))
        val first = place(500, 1300, avoid)
        val second = place(500, 1300, avoid)
        assertEquals(first, second)
    }

    @Test
    fun `an empty avoid box is ignored`() {
        val position = place(64, 200, listOf(Box(500, 500, 500, 900)))
        assertEquals(PanelPosition(64, 200), position)
    }

    @Test
    fun `box overlap is symmetric and edge-exclusive`() {
        val a = Box(0, 0, 100, 100)
        assertFalse(a.intersects(Box(100, 0, 200, 100)))
        assertTrue(a.intersects(Box(99, 0, 200, 100)))
        assertEquals(100L, a.overlapArea(Box(99, 0, 200, 100)))
        assertEquals(a.overlapArea(Box(50, 50, 150, 150)), Box(50, 50, 150, 150).overlapArea(a))
    }
}

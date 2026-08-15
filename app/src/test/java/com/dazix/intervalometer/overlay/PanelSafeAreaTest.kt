package com.dazix.intervalometer.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement against the safe area.
 *
 * The panel's window spans the whole display on purpose — that is what makes its coordinates the same
 * ones a dispatched gesture is aimed at — so nothing but this stops it from being placed behind the
 * status bar or the navigation pill, where the system consumes its touches and part of it is not even
 * drawn. The two spaces have to stay separate: raw pixels for the avoid-box arithmetic, safe area for
 * where the panel may actually land.
 */
class PanelSafeAreaTest {

    private val screenWidth = 1080
    private val screenHeight = 2340
    private val panelWidth = 420
    private val panelHeight = 480

    /** A Pixel-shaped safe area: status bar on top, gesture pill at the bottom. */
    private val safe = Box(left = 0, top = 115, right = screenWidth, bottom = screenHeight - 55)

    private fun resolve(desiredX: Int, desiredY: Int, avoid: List<Box> = emptyList()) =
        PanelPlacement.resolve(
            desiredX = desiredX,
            desiredY = desiredY,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            avoid = avoid,
            safe = safe,
        )

    @Test
    fun `dragging to the bottom stops above the navigation bar`() {
        val position = resolve(desiredX = 40, desiredY = screenHeight)
        assertEquals(safe.bottom - panelHeight, position.y)
        assertTrue(
            "panel bottom ${position.y + panelHeight} must not cross ${safe.bottom}",
            position.y + panelHeight <= safe.bottom,
        )
    }

    @Test
    fun `dragging to the top stops below the status bar`() {
        val position = resolve(desiredX = 40, desiredY = -500)
        assertEquals(safe.top, position.y)
    }

    @Test
    fun `a position already inside the safe area is left alone`() {
        val position = resolve(desiredX = 60, desiredY = 700)
        assertEquals(60, position.x)
        assertEquals(700, position.y)
    }

    @Test
    fun `avoid regions still win inside the safe area`() {
        // The shutter region of a portrait camera: bottom centre. The panel has to clear it *and* stay
        // inside the safe area, which is the combination the two coordinate spaces used to get wrong.
        val shutter = Box(
            left = screenWidth / 3,
            top = screenHeight - 700,
            right = screenWidth * 2 / 3,
            bottom = screenHeight - 200,
        )
        val position = resolve(desiredX = 300, desiredY = screenHeight - 600, avoid = listOf(shutter))
        assertTrue(
            "panel at $position must clear the shutter region",
            PanelPlacement.isClear(position, panelWidth, panelHeight, listOf(shutter)),
        )
        assertTrue("panel must stay below the status bar", position.y >= safe.top)
        assertTrue(
            "panel must stay above the navigation bar",
            position.y + panelHeight <= safe.bottom,
        )
    }

    @Test
    fun `a safe area smaller than the panel is ignored rather than obeyed`() {
        // A camera app in immersive mode can report insets that leave less than the panel's height. The
        // panel then goes back to the whole display: clipping it to nothing would lose Stop entirely.
        val tiny = Box(left = 0, top = 1_100, right = screenWidth, bottom = 1_300)
        val position = PanelPlacement.resolve(
            desiredX = 40,
            desiredY = 2_000,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            avoid = emptyList(),
            safe = tiny,
        )
        assertEquals(screenHeight - panelHeight, position.y)
    }

    @Test
    fun `no safe area means the whole display, as the older callers assumed`() {
        val position = PanelPlacement.resolve(
            desiredX = 0,
            desiredY = screenHeight,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            avoid = emptyList(),
        )
        assertEquals(screenHeight - panelHeight, position.y)
    }
}

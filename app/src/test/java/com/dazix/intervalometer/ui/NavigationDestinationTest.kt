package com.dazix.intervalometer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation table and the breakpoint that decides which of its two forms is shown.
 *
 * Both are the kind of thing that silently rots: a screen added to [Screen] without a destination
 * would simply never appear in the navigation, and a destination pointing at a screen twice would
 * light up two items at once.
 */
class NavigationDestinationTest {

    @Test
    fun `every destination points at a distinct screen`() {
        val screens = NavigationDestination.entries.map { it.screen }

        assertEquals(screens.size, screens.distinct().size)
    }

    @Test
    fun `every screen except standalone full-screen tools is reachable from the navigation`() {
        val covered = NavigationDestination.entries.map { it.screen }.toSet()
        val uncovered = Screen.entries.filterNot { it in covered }

        // The picker and Sun AR are pushed on top of screens and leave by their own Back, so they are
        // deliberately not destinations. Anything else missing here is an oversight.
        assertEquals(listOf(Screen.APP_PICKER, Screen.SUN_AR), uncovered)
    }

    @Test
    fun `standalone full-screen tools are not navigation destinations`() {
        assertNull(NavigationDestination.forScreen(Screen.APP_PICKER))
        assertNull(NavigationDestination.forScreen(Screen.SUN_AR))
    }

    @Test
    fun `each destination screen resolves back to its own destination`() {
        NavigationDestination.entries.forEach { destination ->
            assertEquals(destination, NavigationDestination.forScreen(destination.screen))
        }
    }

    @Test
    fun `every destination has a label and an icon`() {
        NavigationDestination.entries.forEach { destination ->
            assertTrue("label of $destination", destination.labelRes != 0)
            assertNotNull("icon of $destination", destination.icon)
        }
    }

    @Test
    fun `a phone upright gets the bottom bar and the same phone on its side gets the rail`() {
        // A Pixel 7 Pro: 412dp wide upright, 892dp on its side.
        assertFalse(wantsNavigationRail(412.dp))
        assertTrue(wantsNavigationRail(892.dp))
    }

    @Test
    fun `the breakpoint itself already counts as wide`() {
        assertTrue(wantsNavigationRail(RailBreakpoint))
        assertFalse(wantsNavigationRail(RailBreakpoint - 1.dp))
    }
}

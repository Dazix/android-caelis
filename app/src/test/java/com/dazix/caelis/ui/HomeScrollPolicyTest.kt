package com.dazix.caelis.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScrollPolicyTest {

    @Test
    fun `the card ends just above the floating toolbar`() {
        // Card 1500..1900, viewport 1000, toolbar covers the bottom 200: scroll 1100 puts its bottom at 800.
        assertEquals(
            1100,
            HomeScrollPolicy.settingsScrollTarget(
                cardTop = 1500, cardHeight = 400, viewportHeight = 1000, bottomClearance = 200, maxScroll = 5000,
            ),
        )
    }

    @Test
    fun `a card taller than the space left is scrolled to its top`() {
        assertEquals(
            1500,
            HomeScrollPolicy.settingsScrollTarget(
                cardTop = 1500, cardHeight = 900, viewportHeight = 1000, bottomClearance = 200, maxScroll = 5000,
            ),
        )
    }

    @Test
    fun `the target stays inside the scroll range`() {
        assertEquals(
            900,
            HomeScrollPolicy.settingsScrollTarget(
                cardTop = 1500, cardHeight = 400, viewportHeight = 1000, bottomClearance = 200, maxScroll = 900,
            ),
        )
        // Everything already fits on screen: nothing to scroll.
        assertEquals(
            0,
            HomeScrollPolicy.settingsScrollTarget(
                cardTop = 300, cardHeight = 200, viewportHeight = 1000, bottomClearance = 200, maxScroll = 0,
            ),
        )
    }
}

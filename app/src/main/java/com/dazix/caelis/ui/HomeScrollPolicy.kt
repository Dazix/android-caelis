package com.dazix.caelis.ui

/** Where the home screen scrolls to when the gear on its status card is pressed. Pixels throughout. */
internal object HomeScrollPolicy {

    /**
     * The scroll offset that shows the settings card whole, above the floating toolbar that covers
     * [bottomClearance] of the bottom of the viewport: scrolled no further than that takes. A card
     * taller than the space left is scrolled to its top instead, so its first row (the session setup)
     * is the part that is seen. Never outside `0..maxScroll`.
     */
    fun settingsScrollTarget(
        cardTop: Int,
        cardHeight: Int,
        viewportHeight: Int,
        bottomClearance: Int,
        maxScroll: Int,
    ): Int {
        val wholeCardVisible = cardTop + cardHeight + bottomClearance - viewportHeight
        return minOf(cardTop, wholeCardVisible).coerceIn(0, maxScroll.coerceAtLeast(0))
    }
}

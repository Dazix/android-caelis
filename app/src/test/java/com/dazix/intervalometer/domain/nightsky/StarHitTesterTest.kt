package com.dazix.intervalometer.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Test

class StarHitTesterTest {

    @Test
    fun `finds exact hit on visible star`() {
        val screenX = floatArrayOf(100f, 300f, 500f)
        val screenY = floatArrayOf(200f, 400f, 600f)
        val inFront = booleanArrayOf(true, true, true)

        val hitIndex = StarHitTester.findClosestVisibleStar(
            tapX = 300f,
            tapY = 400f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 3,
            maxRadiusPx = 50f,
        )

        assertEquals(1, hitIndex)
    }

    @Test
    fun `selects closest star when multiple stars are within touch radius`() {
        val screenX = floatArrayOf(100f, 110f, 150f)
        val screenY = floatArrayOf(100f, 105f, 150f)
        val inFront = booleanArrayOf(true, true, true)

        // Tap at (108, 104) -> distance to star 1 is sqrt(4+1)=2.23, to star 0 is sqrt(64+16)=8.94
        val hitIndex = StarHitTester.findClosestVisibleStar(
            tapX = 108f,
            tapY = 104f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 3,
            maxRadiusPx = 30f,
        )

        assertEquals(1, hitIndex)
    }

    @Test
    fun `returns minus one when tap is outside touch radius`() {
        val screenX = floatArrayOf(100f, 300f)
        val screenY = floatArrayOf(100f, 300f)
        val inFront = booleanArrayOf(true, true)

        val hitIndex = StarHitTester.findClosestVisibleStar(
            tapX = 150f,
            tapY = 150f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 2,
            maxRadiusPx = 20f,
        )

        assertEquals(-1, hitIndex)
    }

    @Test
    fun `ignores star if starInFront is false`() {
        val screenX = floatArrayOf(200f)
        val screenY = floatArrayOf(200f)
        val inFront = booleanArrayOf(false) // Behind camera plane

        val hitIndex = StarHitTester.findClosestVisibleStar(
            tapX = 200f,
            tapY = 200f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 1,
            maxRadiusPx = 50f,
        )

        assertEquals(-1, hitIndex)
    }

    @Test
    fun `handles empty catalog or non-positive radius gracefully`() {
        val screenX = floatArrayOf(100f)
        val screenY = floatArrayOf(100f)
        val inFront = booleanArrayOf(true)

        assertEquals(-1, StarHitTester.findClosestVisibleStar(100f, 100f, screenX, screenY, inFront, 0, 50f))
        assertEquals(-1, StarHitTester.findClosestVisibleStar(100f, 100f, screenX, screenY, inFront, 1, 0f))
        assertEquals(-1, StarHitTester.findClosestVisibleStar(100f, 100f, screenX, screenY, inFront, 1, -10f))
    }
}

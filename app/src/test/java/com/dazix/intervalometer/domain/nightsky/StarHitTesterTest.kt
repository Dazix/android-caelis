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

    @Test
    fun `finds exact hit on in-focus constellation text`() {
        val cx = floatArrayOf(400f, 800f)
        val cy = floatArrayOf(500f, 900f)
        val inFront = booleanArrayOf(true, true)
        val progress = floatArrayOf(1.0f, 0.8f)

        // For progress = 1.0, textY = 500 - (40 * 0) = 500
        val hitIndex = StarHitTester.findConstellationTextHit(
            tapX = 420f,
            tapY = 510f,
            centroidScreenX = cx,
            centroidScreenY = cy,
            centroidInFront = inFront,
            constellationProgress = progress,
            totalConstellations = 2,
            textOffsetYPx = 40f,
            halfWidthPx = 75f,
            halfHeightPx = 30f,
        )

        assertEquals(0, hitIndex)
    }

    @Test
    fun `ignores constellation text if progress is below threshold`() {
        val cx = floatArrayOf(400f)
        val cy = floatArrayOf(500f)
        val inFront = booleanArrayOf(true)
        val progress = floatArrayOf(0.2f) // Below 0.3f threshold

        val hitIndex = StarHitTester.findConstellationTextHit(
            tapX = 400f,
            tapY = 468f, // textY = 500 - 40*(1-0.2) = 468
            centroidScreenX = cx,
            centroidScreenY = cy,
            centroidInFront = inFront,
            constellationProgress = progress,
            totalConstellations = 1,
            textOffsetYPx = 40f,
            halfWidthPx = 75f,
            halfHeightPx = 30f,
            minProgress = 0.3f,
        )

        assertEquals(-1, hitIndex)
    }

    @Test
    fun `ignores constellation text if centroid is behind camera`() {
        val cx = floatArrayOf(400f)
        val cy = floatArrayOf(500f)
        val inFront = booleanArrayOf(false)
        val progress = floatArrayOf(1.0f)

        val hitIndex = StarHitTester.findConstellationTextHit(
            tapX = 400f,
            tapY = 500f,
            centroidScreenX = cx,
            centroidScreenY = cy,
            centroidInFront = inFront,
            constellationProgress = progress,
            totalConstellations = 1,
            textOffsetYPx = 40f,
            halfWidthPx = 75f,
            halfHeightPx = 30f,
        )

        assertEquals(-1, hitIndex)
    }

    @Test
    fun `respects elliptical bounding box geometry`() {
        val cx = floatArrayOf(500f)
        val cy = floatArrayOf(500f)
        val inFront = booleanArrayOf(true)
        val progress = floatArrayOf(1.0f)

        // Inside ellipse: dx = 60 (< 75), dy = 10 (< 30) -> (60/75)^2 + (10/30)^2 = 0.64 + 0.11 = 0.75 <= 1.0
        val hitInside = StarHitTester.findConstellationTextHit(
            tapX = 560f,
            tapY = 510f,
            centroidScreenX = cx,
            centroidScreenY = cy,
            centroidInFront = inFront,
            constellationProgress = progress,
            totalConstellations = 1,
            textOffsetYPx = 40f,
            halfWidthPx = 75f,
            halfHeightPx = 30f,
        )
        assertEquals(0, hitInside)

        // Outside ellipse: dx = 60, dy = 25 -> (60/75)^2 + (25/30)^2 = 0.64 + 0.69 = 1.33 > 1.0
        val hitOutside = StarHitTester.findConstellationTextHit(
            tapX = 560f,
            tapY = 525f,
            centroidScreenX = cx,
            centroidScreenY = cy,
            centroidInFront = inFront,
            constellationProgress = progress,
            totalConstellations = 1,
            textOffsetYPx = 40f,
            halfWidthPx = 75f,
            halfHeightPx = 30f,
        )
        assertEquals(-1, hitOutside)
    }
}

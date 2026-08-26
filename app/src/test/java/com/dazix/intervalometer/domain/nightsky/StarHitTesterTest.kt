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

    @Test
    fun `computePlanetHitRadiusPx clamps to min radius for tiny planets and scales for large or zoomed bodies`() {
        val minRadiusPx = 48f
        val focalLengthPx = 1000f

        // 1. Tiny distant planet (e.g. Neptune ~0.0006°): rendered radius is small, so clamped to maxOf(120px, minRadiusPx)
        val tinyRadius = StarHitTester.computePlanetHitRadiusPx(
            angularDiameterDeg = 0.0006f,
            isMoon = false,
            effectiveFocalLengthPx = focalLengthPx,
            zCamera = 1.0f,
            minTouchRadiusPx = minRadiusPx,
        )
        assertEquals(120f, tinyRadius, 0.001f)

        // 2. The Moon (~0.52°) is exempt from boost factor:
        // angularSizeRad = Math.toRadians(0.52) = ~0.0090757 rad
        // renderedRadiusPx = (0.0090757 * 1.0 * 1000 / 1.0) / 2 = ~4.537px -> clamped to 120px
        val normalMoonRadius = StarHitTester.computePlanetHitRadiusPx(
            angularDiameterDeg = 0.52f,
            isMoon = true,
            effectiveFocalLengthPx = focalLengthPx,
            zCamera = 1.0f,
            minTouchRadiusPx = minRadiusPx,
        )
        assertEquals(120f, normalMoonRadius, 0.001f)

        // 3. Deep Zoom on the Moon (effectiveFocalLengthPx = 50,000px):
        // renderedRadiusPx = (0.0090757 * 1.0 * 50000 / 1.0) / 2 = ~226.89px -> exceeds 120px
        val zoomedMoonRadius = StarHitTester.computePlanetHitRadiusPx(
            angularDiameterDeg = 0.52f,
            isMoon = true,
            effectiveFocalLengthPx = 50000f,
            zCamera = 1.0f,
            minTouchRadiusPx = minRadiusPx,
        )
        org.junit.Assert.assertTrue("Zoomed Moon radius must exceed 120px", zoomedMoonRadius > 200f)

        // 4. Boost factor applied for small planets at deep zoom (focal length = 1,500,000px):
        // Small planet with 0.0026° -> boost factor = 0.013 / 0.0026 = 5.0
        // renderedRadiusPx = (0.000045378 * 5.0 * 1500000) / 2 = 170.1px > 120px
        val boostedSmallPlanetRadius = StarHitTester.computePlanetHitRadiusPx(
            angularDiameterDeg = 0.0026f,
            isMoon = false,
            effectiveFocalLengthPx = 1500000f,
            zCamera = 1.0f,
            minTouchRadiusPx = minRadiusPx,
        )
        org.junit.Assert.assertTrue("Boosted small planet radius should exceed min radius at deep zoom", boostedSmallPlanetRadius > 120f)
    }

    @Test
    fun `finds exact hit on visible pre-projected planet`() {
        val planets = listOf(
            PlanetState(
                id = "Jupiter",
                nameEn = "Jupiter",
                nameCs = "Jupiter",
                raDegrees = 100.0,
                decDegrees = 20.0,
                magnitude = -2.5f,
                angularDiameterDeg = 0.012f,
                distanceFormatted = "5.2 AU",
                colorRgb = 0xFFFFE0B2.toInt(),
                isMoon = false,
            ),
            PlanetState(
                id = "Mars",
                nameEn = "Mars",
                nameCs = "Mars",
                raDegrees = 150.0,
                decDegrees = 10.0,
                magnitude = -1.2f,
                angularDiameterDeg = 0.004f,
                distanceFormatted = "1.5 AU",
                colorRgb = 0xFFFF5722.toInt(),
                isMoon = false,
            ),
        )

        val screenX = floatArrayOf(400f, 800f)
        val screenY = floatArrayOf(500f, 900f)
        val radiusPx = floatArrayOf(48f, 48f)
        val inFront = booleanArrayOf(true, true)

        val hit = StarHitTester.findPlanetHit(
            tapX = 410f,
            tapY = 505f,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = inFront,
            totalPlanets = 2,
            planets = planets,
            minTouchRadiusPx = 48f,
        )

        org.junit.Assert.assertNotNull(hit)
        assertEquals("Jupiter", hit?.id)
    }

    @Test
    fun `selects closest planet when multiple planets are within touch radius`() {
        val planets = listOf(
            PlanetState(
                id = "Venus",
                nameEn = "Venus",
                nameCs = "Venuše",
                raDegrees = 50.0,
                decDegrees = 15.0,
                magnitude = -4.0f,
                angularDiameterDeg = 0.008f,
                distanceFormatted = "0.7 AU",
                colorRgb = 0xFFFFFDF0.toInt(),
                isMoon = false,
            ),
            PlanetState(
                id = "Mercury",
                nameEn = "Mercury",
                nameCs = "Merkur",
                raDegrees = 50.5,
                decDegrees = 15.2,
                magnitude = -0.5f,
                angularDiameterDeg = 0.002f,
                distanceFormatted = "0.4 AU",
                colorRgb = 0xFFE0D8C8.toInt(),
                isMoon = false,
            ),
        )

        val screenX = floatArrayOf(500f, 520f)
        val screenY = floatArrayOf(600f, 600f)
        val radiusPx = floatArrayOf(48f, 48f)
        val inFront = booleanArrayOf(true, true)

        // Tap at (515, 600) -> distance to Mercury (520) is 5px, to Venus (500) is 15px
        val hit = StarHitTester.findPlanetHit(
            tapX = 515f,
            tapY = 600f,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = inFront,
            totalPlanets = 2,
            planets = planets,
            minTouchRadiusPx = 48f,
        )

        org.junit.Assert.assertNotNull(hit)
        assertEquals("Mercury", hit?.id)
    }

    @Test
    fun `returns null when tap is outside planet hit radius or planet is behind camera`() {
        val planets = listOf(
            PlanetState(
                id = "Saturn",
                nameEn = "Saturn",
                nameCs = "Saturn",
                raDegrees = 200.0,
                decDegrees = -15.0,
                magnitude = 0.5f,
                angularDiameterDeg = 0.005f,
                distanceFormatted = "9.5 AU",
                colorRgb = 0xFFFFD54F.toInt(),
                isMoon = false,
            ),
        )

        val screenX = floatArrayOf(500f)
        val screenY = floatArrayOf(500f)
        val radiusPx = floatArrayOf(48f)
        val inFront = booleanArrayOf(true)

        // Tap at (700, 500) -> distance is 200px > 120px hit radius
        val missHit = StarHitTester.findPlanetHit(
            tapX = 700f,
            tapY = 500f,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = inFront,
            totalPlanets = 1,
            planets = planets,
            minTouchRadiusPx = 48f,
        )
        org.junit.Assert.assertNull(missHit)

        // Planet behind camera plane (inFront = false)
        val behindHit = StarHitTester.findPlanetHit(
            tapX = 500f,
            tapY = 500f,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = booleanArrayOf(false),
            totalPlanets = 1,
            planets = planets,
            minTouchRadiusPx = 48f,
        )
        org.junit.Assert.assertNull(behindHit)
    }

    @Test
    fun `handles empty planets list or invalid radius gracefully`() {
        val screenX = floatArrayOf(100f)
        val screenY = floatArrayOf(100f)
        val radiusPx = floatArrayOf(48f)
        val inFront = booleanArrayOf(true)

        org.junit.Assert.assertNull(
            StarHitTester.findPlanetHit(100f, 100f, screenX, screenY, radiusPx, inFront, 0, emptyList(), 48f)
        )
        org.junit.Assert.assertNull(
            StarHitTester.findPlanetHit(100f, 100f, screenX, screenY, radiusPx, inFront, 1, emptyList(), 0f)
        )
    }

    @Test
    fun `standalone findPlanetHit with dynamic 3D projection resolves planet hit correctly`() {
        // Camera looking North (Azimuth = 0°, Elevation = 0°) at Equator (lat = 0°) and LST = 0°
        // North optical axis: RA = 0°, Dec = 90°
        val northMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val moonAtNorthPole = PlanetState(
            id = "Moon",
            nameEn = "Moon",
            nameCs = "Měsíc",
            raDegrees = 0.0,
            decDegrees = 90.0, // Directly in front (camera center)
            magnitude = -12.0f,
            angularDiameterDeg = 0.52f,
            distanceFormatted = "384,400 km",
            colorRgb = 0xFFF5F5F0.toInt(),
            isMoon = true,
        )

        val saturnOffscreen = PlanetState(
            id = "Saturn",
            nameEn = "Saturn",
            nameCs = "Saturn",
            raDegrees = 180.0,
            decDegrees = 0.0, // Behind camera
            magnitude = 0.5f,
            angularDiameterDeg = 0.005f,
            distanceFormatted = "9.5 AU",
            colorRgb = 0xFFFFD54F.toInt(),
            isMoon = false,
        )

        val planets = listOf(moonAtNorthPole, saturnOffscreen)
        val viewW = 1080f
        val viewH = 2400f
        val focalLengthPx = 1000f

        // Center tap (540, 1200) intersects Moon (projected at 540, 1200)
        val hit = StarHitTester.findPlanetHit(
            touchX = 545f,
            touchY = 1205f,
            currentPlanets = planets,
            matrix = northMatrix,
            lstDegrees = 0.0,
            latitudeDegrees = 0.0,
            viewportWidth = viewW,
            viewportHeight = viewH,
            displayRotation = 0,
            effectiveFocalLengthPx = focalLengthPx,
            minTouchRadiusPx = 48f,
        )

        org.junit.Assert.assertNotNull(hit)
        assertEquals("Moon", hit?.id)

        // Tap far away (100, 100) misses both
        val missHit = StarHitTester.findPlanetHit(
            touchX = 100f,
            touchY = 100f,
            currentPlanets = planets,
            matrix = northMatrix,
            lstDegrees = 0.0,
            latitudeDegrees = 0.0,
            viewportWidth = viewW,
            viewportHeight = viewH,
            displayRotation = 0,
            effectiveFocalLengthPx = focalLengthPx,
            minTouchRadiusPx = 48f,
        )
        org.junit.Assert.assertNull(missHit)
    }
}

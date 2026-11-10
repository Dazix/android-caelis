package com.dazix.caelis.domain.nightsky

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

    /**
     * Delphinus and Job's Coffin, Cygnus and the Northern Cross: an asterism's centroid lies inside
     * its constellation's 150 x 60 dp hit ellipse. The first index in catalogue order used to win, and
     * constellations come before asterisms, so the asterism's name could not be tapped until a zoom
     * pulled the two apart.
     */
    @Test
    fun `overlapping names go to the nearest, not to the first in catalogue order`() {
        val cx = floatArrayOf(500f, 520f)
        val cy = floatArrayOf(500f, 540f)
        val inFront = booleanArrayOf(true, true)
        val progress = floatArrayOf(1f, 1f)

        fun hit(x: Float, y: Float) = StarHitTester.findConstellationTextHit(
            tapX = x, tapY = y,
            centroidScreenX = cx, centroidScreenY = cy, centroidInFront = inFront,
            constellationProgress = progress, totalConstellations = 2,
            textOffsetYPx = 40f, halfWidthPx = 75f, halfHeightPx = 30f,
        )

        assertEquals("on the asterism's name", 1, hit(520f, 540f))
        assertEquals("on the constellation's name", 0, hit(500f, 500f))
        assertEquals("closer to the asterism", 1, hit(510f, 525f))
    }

    @Test
    fun `a name moved by the declutter is hit where it is drawn, and a name left out is not hit`() {
        val cx = floatArrayOf(500f, 500f)
        val cy = floatArrayOf(500f, 500f)
        val inFront = booleanArrayOf(true, true)
        val progress = floatArrayOf(1f, 1f)
        // The constellation keeps its spot, the asterism on the same spot was moved a line (25 px) down.
        val shift = floatArrayOf(0f, 25f)
        val hidden = booleanArrayOf(false, false)

        fun hit(x: Float, y: Float) = StarHitTester.findConstellationTextHit(
            tapX = x, tapY = y,
            centroidScreenX = cx, centroidScreenY = cy, centroidInFront = inFront,
            constellationProgress = progress, totalConstellations = 2,
            textOffsetYPx = 40f, halfWidthPx = 75f, halfHeightPx = 30f,
            labelShiftYPx = shift, labelHidden = hidden,
        )

        assertEquals(1, hit(500f, 525f))
        assertEquals(0, hit(500f, 500f))
        assertEquals("a tap above both lines is the constellation's", 0, hit(500f, 490f))

        hidden[1] = true
        assertEquals("a name not drawn yields to the one that is", 0, hit(500f, 525f))
        hidden[0] = true
        assertEquals(-1, hit(500f, 500f))
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

    @Test
    fun `bright star at radius edge beats dim star near center`() {
        val tapX = 100f
        val tapY = 100f

        // Star 0: Dim star (mag 6.5) at distance 12px -> (112, 100)
        // Star 1: Sirius (mag -1.46) at distance 38px -> (100, 138)
        val screenX = floatArrayOf(112f, 100f)
        val screenY = floatArrayOf(100f, 138f)
        val inFront = booleanArrayOf(true, true)
        val magnitudes = floatArrayOf(6.5f, -1.46f)

        // With magnitude weighting, Sirius (index 1) must win despite being further away
        val bestStarIndex = StarHitTester.findClosestVisibleStar(
            tapX = tapX,
            tapY = tapY,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 2,
            maxRadiusPx = 48f,
            starMagnitudes = magnitudes,
        )

        assertEquals(1, bestStarIndex)

        // Without magnitude weighting (distance only), dim star 0 wins because 12px < 38px
        val distanceOnlyWinner = StarHitTester.findClosestVisibleStar(
            tapX = tapX,
            tapY = tapY,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            totalStars = 2,
            maxRadiusPx = 48f,
            starMagnitudes = null,
        )
        assertEquals(0, distanceOnlyWinner)
    }

    @Test
    fun `computeMagnitudeWeight increases monotonically with brightness`() {
        val wVenus = StarHitTester.computeMagnitudeWeight(-4.0f)
        val wSirius = StarHitTester.computeMagnitudeWeight(-1.46f)
        val wVega = StarHitTester.computeMagnitudeWeight(0.0f)
        val wPolaris = StarHitTester.computeMagnitudeWeight(2.0f)
        val wNakedEye = StarHitTester.computeMagnitudeWeight(6.0f)
        val wTelescopic = StarHitTester.computeMagnitudeWeight(10.0f)

        org.junit.Assert.assertTrue("Venus > Sirius", wVenus > wSirius)
        org.junit.Assert.assertTrue("Sirius > Vega", wSirius > wVega)
        org.junit.Assert.assertTrue("Vega > Polaris", wVega > wPolaris)
        org.junit.Assert.assertTrue("Polaris > NakedEye", wPolaris > wNakedEye)
        org.junit.Assert.assertTrue("NakedEye > Telescopic", wNakedEye > wTelescopic)
    }

    @Test
    fun `insertCandidate maintains sorted order and enforces capacity`() {
        val outIndices = IntArray(3)
        val outScores = FloatArray(3)
        var count = 0

        // Insert index 10 with score 50.0
        count = StarHitTester.insertCandidate(10, 50.0f, outIndices, outScores, count, 3)
        assertEquals(1, count)
        assertEquals(10, outIndices[0])
        assertEquals(50.0f, outScores[0], 0.001f)

        // Insert index 20 with score 10.0 (should go to position 0)
        count = StarHitTester.insertCandidate(20, 10.0f, outIndices, outScores, count, 3)
        assertEquals(2, count)
        assertEquals(20, outIndices[0])
        assertEquals(10, outIndices[1])
        assertEquals(10.0f, outScores[0], 0.001f)
        assertEquals(50.0f, outScores[1], 0.001f)

        // Insert index 30 with score 30.0 (should go to position 1)
        count = StarHitTester.insertCandidate(30, 30.0f, outIndices, outScores, count, 3)
        assertEquals(3, count)
        assertEquals(20, outIndices[0])
        assertEquals(30, outIndices[1])
        assertEquals(10, outIndices[2])

        // Insert index 40 with score 25.0 (should replace index 10 at the end)
        count = StarHitTester.insertCandidate(40, 25.0f, outIndices, outScores, count, 3)
        assertEquals(3, count)
        assertEquals(20, outIndices[0]) // score 10.0
        assertEquals(40, outIndices[1]) // score 25.0
        assertEquals(30, outIndices[2]) // score 30.0

        // Insert index 50 with score 100.0 (worse than capacity -> rejected)
        count = StarHitTester.insertCandidate(50, 100.0f, outIndices, outScores, count, 3)
        assertEquals(3, count)
        assertEquals(20, outIndices[0])
        assertEquals(40, outIndices[1])
        assertEquals(30, outIndices[2])
    }

    @Test
    fun `findTopVisibleStars collects multiple candidates in dense cluster`() {
        val tapX = 200f
        val tapY = 200f

        // 4 stars within 48dp radius
        val screenX = floatArrayOf(205f, 195f, 220f, 210f, 400f) // 5th star is far away
        val screenY = floatArrayOf(205f, 205f, 210f, 190f, 400f)
        val inFront = booleanArrayOf(true, true, true, true, true)
        val mags = floatArrayOf(2.0f, 2.5f, 1.0f, 3.0f, 0.0f)

        val outIndices = IntArray(5)
        val outScores = FloatArray(5)

        val candidateCount = StarHitTester.findTopVisibleStars(
            tapX = tapX,
            tapY = tapY,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            starMagnitudes = mags,
            totalStars = 5,
            maxRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            maxCandidates = 5,
        )

        assertEquals(4, candidateCount) // 4 stars inside radius, 5th rejected
        // Top candidate should be star 2 (mag 1.0f) or star 0 (mag 2.0 at 7px)
        org.junit.Assert.assertTrue(outScores[0] < outScores[1])
        org.junit.Assert.assertTrue(outScores[1] < outScores[2])
        org.junit.Assert.assertTrue(outScores[2] < outScores[3])
    }

    @Test
    fun `findTopPlanets collects candidate planets sorted by score`() {
        val tapX = 300f
        val tapY = 300f

        val jupiter = PlanetState(
            id = "Jupiter",
            nameEn = "Jupiter",
            raDegrees = 0.0,
            decDegrees = 0.0,
            magnitude = -2.5f,
            angularDiameterDeg = 0.01f,
            distanceFormatted = "5.2 AU",
            colorRgb = 0xFFFFD54F.toInt(),
            isMoon = false,
        )
        val mars = PlanetState(
            id = "Mars",
            nameEn = "Mars",
            raDegrees = 0.0,
            decDegrees = 0.0,
            magnitude = -0.5f,
            angularDiameterDeg = 0.005f,
            distanceFormatted = "1.5 AU",
            colorRgb = 0xFFFF5722.toInt(),
            isMoon = false,
        )

        val screenX = floatArrayOf(305f, 315f)
        val screenY = floatArrayOf(305f, 310f)
        val radiusPx = floatArrayOf(40f, 30f)
        val inFront = booleanArrayOf(true, true)

        val outIndices = IntArray(5)
        val outScores = FloatArray(5)

        val count = StarHitTester.findTopPlanets(
            tapX = tapX,
            tapY = tapY,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = inFront,
            totalPlanets = 2,
            planets = listOf(jupiter, mars),
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            maxCandidates = 5,
        )

        assertEquals(2, count)
        assertEquals(0, outIndices[0]) // Jupiter should be first due to brighter magnitude (-2.5 vs -0.5)
        assertEquals(1, outIndices[1])
        org.junit.Assert.assertTrue(outScores[0] < outScores[1])
    }

    @Test
    fun `findTopDsos collects candidate DSOs sorted by score`() {
        val tapX = 400f
        val tapY = 400f

        // DSO 0: M31 Andromeda (mag 3.44, radius 25px) at (405, 405)
        // DSO 1: M32 Satellite (mag 8.08, radius 10px) at (402, 402)
        // DSO 2: M110 (mag 8.92, radius 12px) at (600, 600) - far away
        val screenX = floatArrayOf(405f, 402f, 600f)
        val screenY = floatArrayOf(405f, 402f, 600f)
        val radiusPx = floatArrayOf(25f, 10f, 12f)
        val inFront = booleanArrayOf(true, true, true)
        val mags = floatArrayOf(3.44f, 8.08f, 8.92f)

        val outIndices = IntArray(5)
        val outScores = FloatArray(5)

        val count = StarHitTester.findTopDsos(
            tapX = tapX,
            tapY = tapY,
            dsoScreenX = screenX,
            dsoScreenY = screenY,
            dsoRadiusPx = radiusPx,
            dsoInFront = inFront,
            dsoMagnitudes = mags,
            totalDsos = 3,
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            maxCandidates = 5,
        )

        assertEquals(2, count)
        // DSO 0 (M31, mag 3.44) should score better than DSO 1 (M32, mag 8.08) despite DSO 1 being slightly closer
        assertEquals(0, outIndices[0])
        assertEquals(1, outIndices[1])
        org.junit.Assert.assertTrue(outScores[0] < outScores[1])
    }

    @Test
    fun `findTopDsos ignores DSOs behind camera or outside hit radius`() {
        val screenX = floatArrayOf(500f, 500f)
        val screenY = floatArrayOf(500f, 500f)
        val radiusPx = floatArrayOf(20f, 20f)
        val inFront = booleanArrayOf(false, true) // 0 is behind camera
        val mags = floatArrayOf(4.0f, 4.0f)

        val outIndices = IntArray(3)
        val outScores = FloatArray(3)

        // Tap at (500, 500) hits DSO 1 (inFront=true), ignores DSO 0 (inFront=false)
        val count = StarHitTester.findTopDsos(
            tapX = 500f,
            tapY = 500f,
            dsoScreenX = screenX,
            dsoScreenY = screenY,
            dsoRadiusPx = radiusPx,
            dsoInFront = inFront,
            dsoMagnitudes = mags,
            totalDsos = 2,
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            maxCandidates = 3,
        )

        assertEquals(1, count)
        assertEquals(1, outIndices[0])

        // Tap far away (800, 800) misses both
        val missCount = StarHitTester.findTopDsos(
            tapX = 800f,
            tapY = 800f,
            dsoScreenX = screenX,
            dsoScreenY = screenY,
            dsoRadiusPx = radiusPx,
            dsoInFront = inFront,
            dsoMagnitudes = mags,
            totalDsos = 2,
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            maxCandidates = 3,
        )

        assertEquals(0, missCount)
    }

    @Test
    fun `findTopDsos handles empty arguments gracefully`() {
        val outIndices = IntArray(3)
        val outScores = FloatArray(3)
        val screenX = floatArrayOf(100f)
        val screenY = floatArrayOf(100f)
        val radiusPx = floatArrayOf(20f)
        val inFront = booleanArrayOf(true)

        assertEquals(0, StarHitTester.findTopDsos(100f, 100f, screenX, screenY, radiusPx, inFront, null, 0, 48f, outIndices, outScores))
        assertEquals(0, StarHitTester.findTopDsos(100f, 100f, screenX, screenY, radiusPx, inFront, null, 1, 0f, outIndices, outScores))
        assertEquals(0, StarHitTester.findTopDsos(100f, 100f, screenX, screenY, radiusPx, inFront, null, 1, -10f, outIndices, outScores))
    }

    @Test
    fun `isOccludedByLandscape correctly identifies objects above and below stylized horizon`() {
        // Star above horizon (vz = +0.5, alt ~ +30 deg)
        org.junit.Assert.assertFalse(
            StarHitTester.isOccludedByLandscape(
                vx = 0f, vy = 1f, vz = 0.5f,
                isLandscapeVisible = true,
                useRealHorizon = false,
                horizonProfile = null,
            )
        )

        // Star below horizon (vz = -0.2, alt ~ -11.5 deg)
        org.junit.Assert.assertTrue(
            StarHitTester.isOccludedByLandscape(
                vx = 0f, vy = 1f, vz = -0.2f,
                isLandscapeVisible = true,
                useRealHorizon = false,
                horizonProfile = null,
            )
        )

        // Star below horizon but landscape layer is OFF
        org.junit.Assert.assertFalse(
            StarHitTester.isOccludedByLandscape(
                vx = 0f, vy = 1f, vz = -0.2f,
                isLandscapeVisible = false,
                useRealHorizon = false,
                horizonProfile = null,
            )
        )
    }

    @Test
    fun `isOccludedByLandscape correctly checks against real DEM horizon profile`() {
        val elevations = FloatArray(com.dazix.caelis.domain.horizon.HorizonProfile.SAMPLE_COUNT) { 5.0f } // 5 deg ridge all around
        val profile = com.dazix.caelis.domain.horizon.HorizonProfile(
            elevationsDegrees = elevations,
            observer = com.dazix.caelis.domain.solar.GeoLocation(50.0, 15.0),
            observerGroundElevationMeters = 300.0,
            eyeHeightMeters = 1.7,
            maxRangeMeters = 50000.0,
            generatedAtEpochMillis = 1000L,
            coveredSamples = com.dazix.caelis.domain.horizon.HorizonProfile.SAMPLE_COUNT,
        )

        // Star at altitude +10 deg (vz = sin(10 deg) ~ 0.1736) -> Above ridge (5 deg)
        org.junit.Assert.assertFalse(
            StarHitTester.isOccludedByLandscape(
                vx = 0f, vy = 1f, vz = 0.1736f,
                isLandscapeVisible = false,
                useRealHorizon = true,
                horizonProfile = profile,
            )
        )

        // Star at altitude +2 deg (vz = sin(2 deg) ~ 0.0349) -> Below ridge (5 deg)
        org.junit.Assert.assertTrue(
            StarHitTester.isOccludedByLandscape(
                vx = 0f, vy = 1f, vz = 0.0349f,
                isLandscapeVisible = false,
                useRealHorizon = true,
                horizonProfile = profile,
            )
        )
    }

    @Test
    fun `findTopVisibleStars filters out stars below landscape when landscape is active`() {
        val screenX = floatArrayOf(200f, 200f)
        val screenY = floatArrayOf(200f, 200f)
        val inFront = booleanArrayOf(true, true)
        val worldX = floatArrayOf(0f, 0f)
        val worldY = floatArrayOf(1f, 1f)
        val worldZ = floatArrayOf(0.5f, -0.5f) // Star 0 is above horizon, star 1 is below horizon

        val outIndices = IntArray(2)
        val outScores = FloatArray(2)

        // When landscape is ON, only star 0 is found
        val countOn = StarHitTester.findTopVisibleStars(
            tapX = 200f,
            tapY = 200f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            starMagnitudes = floatArrayOf(0f, 0f),
            totalStars = 2,
            maxRadiusPx = 50f,
            outIndices = outIndices,
            outScores = outScores,
            starWorldX = worldX,
            starWorldY = worldY,
            starWorldZ = worldZ,
            isLandscapeVisible = true,
        )
        assertEquals(1, countOn)
        assertEquals(0, outIndices[0])

        // When landscape is OFF, both stars are found
        val countOff = StarHitTester.findTopVisibleStars(
            tapX = 200f,
            tapY = 200f,
            starScreenX = screenX,
            starScreenY = screenY,
            starInFront = inFront,
            starMagnitudes = floatArrayOf(0f, 0f),
            totalStars = 2,
            maxRadiusPx = 50f,
            outIndices = outIndices,
            outScores = outScores,
            starWorldX = worldX,
            starWorldY = worldY,
            starWorldZ = worldZ,
            isLandscapeVisible = false,
        )
        assertEquals(2, countOff)
    }

    @Test
    fun `findTopDsos and findTopPlanets filter out occluded objects`() {
        val screenX = floatArrayOf(300f)
        val screenY = floatArrayOf(300f)
        val inFront = booleanArrayOf(true)
        val radiusPx = floatArrayOf(20f)
        val worldX = floatArrayOf(0f)
        val worldY = floatArrayOf(1f)
        val worldZ = floatArrayOf(-0.3f) // Below horizon

        val outIndices = IntArray(1)
        val outScores = FloatArray(1)

        val dsoCount = StarHitTester.findTopDsos(
            tapX = 300f,
            tapY = 300f,
            dsoScreenX = screenX,
            dsoScreenY = screenY,
            dsoRadiusPx = radiusPx,
            dsoInFront = inFront,
            dsoMagnitudes = floatArrayOf(4f),
            totalDsos = 1,
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            dsoWorldX = worldX,
            dsoWorldY = worldY,
            dsoWorldZ = worldZ,
            isLandscapeVisible = true,
        )
        assertEquals(0, dsoCount)

        val planet = PlanetState(
            id = "Jupiter",
            nameEn = "Jupiter",
            raDegrees = 0.0,
            decDegrees = 0.0,
            magnitude = -2.0f,
            angularDiameterDeg = 0.01f,
            distanceFormatted = "5.2 AU",
            illuminationFraction = 1.0f,
            colorRgb = 0xFFFFFFFF.toInt(),
        )
        val planetCount = StarHitTester.findTopPlanets(
            tapX = 300f,
            tapY = 300f,
            planetScreenX = screenX,
            planetScreenY = screenY,
            planetRadiusPx = radiusPx,
            planetInFront = inFront,
            totalPlanets = 1,
            planets = listOf(planet),
            minTouchRadiusPx = 48f,
            outIndices = outIndices,
            outScores = outScores,
            planetWorldX = worldX,
            planetWorldY = worldY,
            planetWorldZ = worldZ,
            isLandscapeVisible = true,
        )
        assertEquals(0, planetCount)
    }

    @Test
    fun `findTopMeteorRadiants hits visible radiant and filters occluded ones`() {
        val screenX = floatArrayOf(200f, 400f)
        val screenY = floatArrayOf(200f, 400f)
        val inFront = booleanArrayOf(true, true)
        val worldX = floatArrayOf(0f, 0f)
        val worldY = floatArrayOf(1f, 1f)
        val worldZ = floatArrayOf(0.5f, -0.3f) // 0 is above horizon, 1 is below horizon

        val outIndices = IntArray(2)
        val outScores = FloatArray(2)

        // Hit radiant 0 above horizon
        val countAbove = StarHitTester.findTopMeteorRadiants(
            tapX = 205f,
            tapY = 205f,
            meteorScreenX = screenX,
            meteorScreenY = screenY,
            meteorInFront = inFront,
            totalMeteors = 2,
            minTouchRadiusPx = 50f,
            outIndices = outIndices,
            outScores = outScores,
            meteorWorldX = worldX,
            meteorWorldY = worldY,
            meteorWorldZ = worldZ,
            isLandscapeVisible = true,
        )
        assertEquals(1, countAbove)
        assertEquals(0, outIndices[0])

        // Tap radiant 1 below horizon with landscape visible -> occluded
        val countBelow = StarHitTester.findTopMeteorRadiants(
            tapX = 405f,
            tapY = 405f,
            meteorScreenX = screenX,
            meteorScreenY = screenY,
            meteorInFront = inFront,
            totalMeteors = 2,
            minTouchRadiusPx = 50f,
            outIndices = outIndices,
            outScores = outScores,
            meteorWorldX = worldX,
            meteorWorldY = worldY,
            meteorWorldZ = worldZ,
            isLandscapeVisible = true,
        )
        assertEquals(0, countBelow)
    }
}

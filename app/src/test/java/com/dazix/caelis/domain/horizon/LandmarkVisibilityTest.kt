package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkVisibilityTest {

    private val observer = GeoLocation(50.0, 15.0)

    /**
     * A 1° tent of [heightDegrees] at [azimuth] over a skyline at -1°, wrapping across north. A tent
     * rather than a plateau because the rule under test wants a local maximum: a real summit has one.
     */
    private fun profile(
        covered: Int = HorizonProfile.SAMPLE_COUNT,
        ridges: List<Pair<Double, Double>>,
        halfWidthDegrees: Double = 1.0,
    ): HorizonProfile {
        val samples = FloatArray(HorizonProfile.SAMPLE_COUNT) { -1f }
        for ((azimuth, height) in ridges) {
            for (i in 0 until HorizonProfile.SAMPLE_COUNT) {
                val delta = azimuthDelta(HorizonProfile.azimuthForIndex(i), azimuth)
                if (delta <= halfWidthDegrees) {
                    val tent = height - (height + 1.0) * (delta / halfWidthDegrees) * 0.5
                    samples[i] = maxOf(samples[i], tent.toFloat())
                }
            }
        }
        return HorizonProfile(
            elevationsDegrees = samples,
            observer = observer,
            observerGroundElevationMeters = 200.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 0L,
            coveredSamples = covered,
        )
    }

    /** A peak [distance] metres from the observer on [bearing]; flat-earth is exact enough at tens of km. */
    private fun peak(
        distance: Double,
        bearing: Double,
        elevation: Int = 1000,
        name: String = "P",
    ): LandmarkPeak {
        val metresPerDegree = 111_194.93
        val radians = Math.toRadians(bearing)
        val lat = observer.latitude + distance * cos(radians) / metresPerDegree
        val lon = observer.longitude +
            distance * sin(radians) / (metresPerDegree * cos(Math.toRadians(observer.latitude)))
        return LandmarkPeak(lat, lon, elevation, name)
    }

    /** Apparent altitude of a 1000 m summit at 10 km for an eye at 201.6 m, worked by hand (R_eff = 7 323 km). */
    private val tenKmApparent = 4.5265

    @Test
    fun `a summit on the ridge is selected with its place in the sky`() {
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 90.0)),
            profile(ridges = listOf(90.0 to tenKmApparent)),
            maxLabels = 10,
        )

        val landmark = result.single()
        assertEquals(10_000.0, landmark.distanceMeters, 30.0)
        // The helper walks a flat-earth offset; the great-circle bearing of a point 10 km due east at 50 N
        // is 0.11 degrees off 90, which is the meridian convergence the real formula is right to include.
        assertEquals(90.0, landmark.azimuthDegrees, 0.2)
        assertEquals(tenKmApparent, landmark.apparentElevationDegrees, 0.01)
        assertEquals(1000, landmark.elevationMeters)
    }

    @Test
    fun `all three names travel with the landmark`() {
        val named = peak(10_000.0, 90.0).copy(name = "Śnieżka", names = mapOf("cs" to "Sněžka", "en" to "Sniezka"))

        val landmark = LandmarkVisibility.select(
            listOf(named),
            profile(ridges = listOf(90.0 to tenKmApparent)),
            maxLabels = 10,
        ).single()

        assertEquals("Sněžka", landmark.displayName("cs"))
        assertEquals("Sniezka", landmark.displayName("en"))
        assertEquals("Śnieżka", landmark.name)
    }

    @Test
    fun `a summit behind a nearer ridge is not drawn`() {
        // The skyline there is 9 degrees; the summit would appear at 4.5, hidden behind the hill in front.
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 90.0)),
            profile(ridges = listOf(90.0 to 9.0), halfWidthDegrees = 6.0),
            maxLabels = 10,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a summit that Earth curvature sinks below the skyline is not drawn`() {
        // 1000 m at 60 km: 4.5 degrees with no curvature, but the 245 m of drop leaves it at 0.7.
        val result = LandmarkVisibility.select(
            listOf(peak(59_000.0, 90.0)),
            profile(ridges = listOf(90.0 to 3.0)),
            maxLabels = 10,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a summit beyond the profile range is not drawn even on a ridge`() {
        val far = peak(61_000.0, 90.0, elevation = 4000)
        val result = LandmarkVisibility.select(
            listOf(far),
            profile(ridges = listOf(90.0 to 3.0)),
            maxLabels = 10,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a summit closer than 500 metres is not drawn`() {
        val result = LandmarkVisibility.select(
            listOf(peak(400.0, 90.0, elevation = 400)),
            profile(ridges = listOf(90.0 to 20.0)),
            maxLabels = 10,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a slope without a local maximum is not labelled`() {
        // Skyline rises steadily through the summit's azimuth: it lies on the line, but not on a top.
        val samples = FloatArray(HorizonProfile.SAMPLE_COUNT) { i ->
            -1f + 0.1f * i.coerceAtMost(200)
        }
        val slope = HorizonProfile(
            samples, observer, 200.0, 1.6, 60_000.0, 0L,
        )
        val azimuth = HorizonProfile.azimuthForIndex(100)
        val onSlope = peak(10_000.0, azimuth, elevation = 1000)
        val altitude = slope.elevationAt(azimuth)
        // Pick an elevation whose apparent altitude is the skyline there.
        val eye = slope.observerEyeElevationMeters
        val drop = 10_000.0 * 10_000.0 / (2.0 * HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS)
        val elevation = (eye + drop + 10_000.0 * Math.tan(Math.toRadians(altitude))).toInt()

        val result = LandmarkVisibility.select(listOf(onSlope.copy(elevationMeters = elevation)), slope, 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a summit just north-west of north is found across the wrap`() {
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 359.9)),
            profile(ridges = listOf(359.9 to tenKmApparent)),
            maxLabels = 10,
        )

        val azimuth = result.single().azimuthDegrees
        assertEquals(359.9, azimuth, 0.05)
    }

    @Test
    fun `a ridge sampled on the other side of north still counts`() {
        // The local maximum is the sample at 0.0, the summit sits at 359.8: 0.2 degrees away across the wrap.
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 359.8)),
            profile(ridges = listOf(0.0 to tenKmApparent)),
            maxLabels = 10,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `a summit a little below the ridge within the elevation tolerance is kept`() {
        // OSM says 1000 m and the elevation model 1015 m: about 0.09 degrees at 10 km.
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 90.0)),
            profile(ridges = listOf(90.0 to tenKmApparent + 0.09)),
            maxLabels = 10,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `a summit well above the drawn ridge is not drawn`() {
        // The skyline here is 2 degrees lower than the summit stands: a gap in the elevation data or
        // the edge of a covered sector, not a ridge. A name there would float over nothing.
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 90.0)),
            profile(ridges = listOf(90.0 to tenKmApparent - 2.0)),
            maxLabels = 10,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `labels closer than the minimum separation are thinned to the one standing out most`() {
        val tall = peak(10_000.0, 90.0, elevation = 1200, name = "Tall")
        val low = peak(10_000.0, 91.0, elevation = 1000, name = "Low")
        val profile = profile(
            ridges = listOf(
                90.0 to Math.toDegrees(Math.atan2(1200 - 201.6 - 6.83, 10_000.0)),
                91.0 to tenKmApparent,
            ),
            halfWidthDegrees = 0.5,
        )

        val result = LandmarkVisibility.select(listOf(low, tall), profile, maxLabels = 10)

        assertEquals(listOf("Tall"), result.map { it.name })
    }

    @Test
    fun `the label cap keeps the most prominent and returns them in azimuth order`() {
        val bearings = listOf(10.0, 60.0, 110.0, 160.0, 210.0)
        val heights = listOf(1000, 1400, 1200, 800, 1600)
        val peaks = bearings.zip(heights).map { (b, h) -> peak(10_000.0, b, h, name = "P$h") }
        val ridges = bearings.zip(heights).map { (b, h) ->
            b to Math.toDegrees(Math.atan2(h - 201.6 - 6.83, 10_000.0))
        }
        val profile = profile(ridges = ridges, halfWidthDegrees = 0.5)

        val result = LandmarkVisibility.select(peaks, profile, maxLabels = 3)

        assertEquals(listOf("P1400", "P1200", "P1600"), result.map { it.name })
    }

    @Test
    fun `a profile that is not usable selects nothing`() {
        val unusable = profile(
            covered = HorizonProfile.MIN_USABLE_SAMPLES - 1,
            ridges = listOf(90.0 to tenKmApparent),
        )

        val result = LandmarkVisibility.select(listOf(peak(10_000.0, 90.0)), unusable, maxLabels = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `zero labels selects nothing`() {
        val result = LandmarkVisibility.select(
            listOf(peak(10_000.0, 90.0)),
            profile(ridges = listOf(90.0 to tenKmApparent)),
            maxLabels = 0,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `the same summit listed twice is drawn once`() {
        val one = peak(10_000.0, 90.0)

        val result = LandmarkVisibility.select(
            listOf(one, one.copy()),
            profile(ridges = listOf(90.0 to tenKmApparent)),
            maxLabels = 10,
        )

        assertEquals(1, result.size)
    }

    private fun landmark(azimuth: Double, altitude: Double = 2.0) = HorizonLandmark(
        name = "P", elevationMeters = 1000,
        distanceMeters = 10_000.0, azimuthDegrees = azimuth, apparentElevationDegrees = altitude,
    )

    @Test
    fun `a summit beside the drawn top is drawn on it`() {
        // A broad tent, so a summit a few tenths of a degree off its top still passes the ridge test.
        val skyline = profile(ridges = listOf(90.0 to tenKmApparent), halfWidthDegrees = 3.0)
        val selected = LandmarkVisibility.select(listOf(peak(10_000.0, 90.4)), skyline, maxLabels = 10).single()
        // The stored landmark keeps the summit's own bearing: what is pinned is only where it is drawn.
        assertTrue(selected.azimuthDegrees > 90.2)

        val drawn = skyline.withLandmarks(listOf(selected), resolved = true).drawnLandmarks.single()

        assertEquals(90.0, drawn.azimuthDegrees, 0.0)
        assertEquals(skyline.elevationsDegrees[360].toDouble(), drawn.apparentElevationDegrees, 0.0)
        assertEquals(selected.name, drawn.name)
        assertEquals(selected.distanceMeters, drawn.distanceMeters, 0.0)
    }

    @Test
    fun `pinning lands on a sample, picks the nearest top and pins to itself`() {
        val skyline = profile(ridges = listOf(90.0 to 3.0, 90.75 to 2.5), halfWidthDegrees = 0.5)

        val nearFirst = LandmarkVisibility.pinToSkyline(landmark(90.3), skyline)
        val nearSecond = LandmarkVisibility.pinToSkyline(landmark(90.5), skyline)

        assertEquals(90.0, nearFirst.azimuthDegrees, 0.0)
        assertEquals(3.0, nearFirst.apparentElevationDegrees, 1e-6)
        assertEquals(90.75, nearSecond.azimuthDegrees, 0.0)
        assertEquals(2.5, nearSecond.apparentElevationDegrees, 1e-6)
        assertEquals(nearFirst, LandmarkVisibility.pinToSkyline(nearFirst, skyline))
    }

    @Test
    fun `pinning wraps across north`() {
        val skyline = profile(ridges = listOf(0.0 to 3.0))

        val drawn = LandmarkVisibility.pinToSkyline(landmark(359.8), skyline)

        assertEquals(0.0, drawn.azimuthDegrees, 0.0)
        assertEquals(3.0, drawn.apparentElevationDegrees, 1e-6)
    }

    @Test
    fun `a landmark with no top near it is drawn where it is`() {
        val skyline = profile(ridges = listOf(90.0 to 3.0))
        val far = landmark(120.0, altitude = 1.5)

        assertEquals(far, LandmarkVisibility.pinToSkyline(far, skyline))
    }
}

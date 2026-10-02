package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.StarHitTester
import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class HorizonMeshBuilderTest {

    private fun profileOf(covered: Int = HorizonProfile.SAMPLE_COUNT, fill: (Int) -> Float) = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { fill(it) },
        observer = GeoLocation(50.0755, 14.4378),
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
        coveredSamples = covered,
    )

    /** A 10 deg ridge within 20 deg of one bearing, flat 0 deg everywhere else. */
    private fun ridgeAt(bearing: Double) = profileOf { i ->
        val az = HorizonProfile.azimuthForIndex(i)
        val d = ((az - bearing + 540.0) % 360.0) - 180.0
        if (kotlin.math.abs(d) <= 20.0) 10f else 0f
    }

    private fun vertices(profile: HorizonProfile?) =
        FloatArray(HorizonMeshBuilder.VERTEX_COUNT * HorizonMeshBuilder.FLOATS_PER_VERTEX).also {
            HorizonMeshBuilder.buildVertices(profile, it)
        }

    /** Highest skyline vertex, as an ENU direction after alignment. */
    private fun highestSkylineDirection(v: FloatArray, azOffset: Float = 0f, elOffset: Float = 0f): FloatArray {
        var best = -1
        var bestEl = Float.NEGATIVE_INFINITY
        for (k in 0 until HorizonMeshBuilder.VERTEX_COUNT step 2) {
            val el = v[k * 4 + 1]
            if (el > bestEl) { bestEl = el; best = k }
        }
        val out = FloatArray(3)
        HorizonMeshBuilder.directionOf(v[best * 4], v[best * 4 + 1], azOffset, elOffset, out)
        return out
    }

    private fun azimuthOf(d: FloatArray): Double =
        (Math.toDegrees(atan2(d[0].toDouble(), d[1].toDouble())) + 360.0) % 360.0

    @Test
    fun `a ridge in each cardinal direction is drawn in that direction, not a half or quarter turn out`() {
        for ((name, bearing) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            val d = highestSkylineDirection(vertices(ridgeAt(bearing)))
            val az = azimuthOf(d)
            val err = kotlin.math.abs(((az - bearing + 540.0) % 360.0) - 180.0)
            assertTrue("$name ridge drawn at azimuth $az", err <= 20.0)
            assertEquals("$name ridge height", 10.0, Math.toDegrees(asin(d[2].toDouble())), 1e-3)
        }
    }

    @Test
    fun `sample zero is north and every quarter degree steps clockwise`() {
        val v = vertices(profileOf { 0f })
        val d = FloatArray(3)
        for (i in intArrayOf(0, 360, 720, 1080)) {
            val k = i * 2
            assertEquals(i * 0.25f, v[k * 4], 0f)
            HorizonMeshBuilder.directionOf(v[k * 4], v[k * 4 + 1], 0f, 0f, d)
            assertEquals(i * 0.25, azimuthOf(d), 1e-3)
        }
    }

    @Test
    fun `aligned skyline occludes exactly what the hit tester treats as occluded`() {
        val profile = profileOf { i -> 3f + 5f * kotlin.math.sin(i * 2 * Math.PI / 1440 * 3).toFloat() }
        val v = vertices(profile)
        val azOffset = 4.5f
        val elOffset = -1.25f
        val d = FloatArray(3)
        for (k in 0 until HorizonMeshBuilder.VERTEX_COUNT step 2) {
            HorizonMeshBuilder.directionOf(v[k * 4], v[k * 4 + 1], azOffset, elOffset, d)
            val skylineAz = azimuthOf(d)
            val skylineEl = Math.toDegrees(asin(d[2].toDouble()))
            // Just above the drawn skyline is sky, just below is terrain, per the hit tester.
            for ((deltaEl, occluded) in listOf(0.05 to false, -0.05 to true)) {
                val el = Math.toRadians(skylineEl + deltaEl)
                val az = Math.toRadians(skylineAz)
                val vx = (kotlin.math.sin(az) * kotlin.math.cos(el)).toFloat()
                val vy = (kotlin.math.cos(az) * kotlin.math.cos(el)).toFloat()
                val vz = kotlin.math.sin(el).toFloat()
                val hit = StarHitTester.isOccludedByLandscape(
                    vx, vy, vz,
                    isLandscapeVisible = false,
                    useRealHorizon = true,
                    horizonProfile = profile,
                    alignAzimuthOffsetDeg = azOffset,
                    alignElevationOffsetDeg = elOffset,
                )
                assertEquals("vertex $k at az $skylineAz, delta $deltaEl", occluded, hit)
            }
        }
    }

    @Test
    fun `an unusable profile falls back to the flat ring instead of a partial skyline`() {
        val v = vertices(profileOf(covered = HorizonProfile.MIN_USABLE_SAMPLES - 1) { 12f })
        for (k in 0 until HorizonMeshBuilder.VERTEX_COUNT step 2) assertEquals(0f, v[k * 4 + 1], 0f)
        val flat = vertices(null)
        for (k in 0 until HorizonMeshBuilder.VERTEX_COUNT step 2) assertEquals(0f, flat[k * 4 + 1], 0f)
    }

    @Test
    fun `the skirt hangs to the nadir and the offsets never lift it`() {
        val v = vertices(ridgeAt(0.0))
        val d = FloatArray(3)
        for (k in 1 until HorizonMeshBuilder.VERTEX_COUNT step 2) {
            assertEquals(HorizonMeshBuilder.NADIR, v[k * 4 + 1], 0f)
            HorizonMeshBuilder.directionOf(v[k * 4], v[k * 4 + 1], 7f, -3f, d)
            assertEquals(-1f, d[2], 1e-6f)
        }
    }

    @Test
    fun `panorama u keeps north in the middle of the image and closes the ring without a seam`() {
        val v = vertices(null)
        assertEquals(0.5f, v[2], 0f)
        val last = (HorizonMeshBuilder.VERTEX_COUNT - 2) * 4
        assertEquals(360f, v[last], 0f)
        assertEquals(1.5f, v[last + 2], 1e-6f)
    }

    @Test
    fun `indices cover every quad with both triangles`() {
        val idx = IntArray(HorizonMeshBuilder.INDEX_COUNT)
        HorizonMeshBuilder.buildIndices(idx)
        assertEquals(0, idx.min())
        assertEquals(HorizonMeshBuilder.VERTEX_COUNT - 1, idx.max())
        assertEquals(listOf(0, 1, 2, 2, 1, 3), idx.take(6))
    }
}

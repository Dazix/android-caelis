package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HeadingFusionTest {

    private val high = 3
    private val medium = HeadingFusion.SENSOR_STATUS_ACCURACY_MEDIUM
    private val low = 1
    private fun rad(deg: Double) = Math.toRadians(deg).toFloat()
    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble())

    @Test
    fun `a jittery compass converges to a sub-degree heading`() {
        val f = HeadingFusion()
        val rnd = Random(7)
        var t = 0L
        repeat(400) {
            f.addCompass(rad(40.0 + rnd.nextGaussian() * 4.0), high, t)
            t += 16_000_000L
        }
        assertTrue("converged, uncertainty ${f.uncertaintyDegrees}", f.isConverged)
        assertEquals(40.0, deg(f.yaw), 1.0)
        assertEquals(HeadingFusion.Source.COMPASS, f.source)
    }

    @Test
    fun `not converged means not known, even when the first samples agree`() {
        val f = HeadingFusion()
        repeat(5) { f.addCompass(rad(10.0), high, it * 16_000_000L) }
        assertFalse(f.isConverged)
    }

    @Test
    fun `a low accuracy magnetometer is ignored rather than averaged in`() {
        val f = HeadingFusion()
        repeat(100) { f.addCompass(rad(90.0), low, it * 16_000_000L) }
        assertFalse(f.isConverged)
        assertEquals(HeadingFusion.Source.NONE, f.source)
        repeat(100) { f.addCompass(rad(10.0), medium, it * 16_000_000L) }
        assertEquals(10.0, deg(f.yaw), 0.01)
    }

    @Test
    fun `averaging works across the plus-minus 180 degree seam`() {
        val f = HeadingFusion()
        repeat(200) { i -> f.addCompass(rad(if (i % 2 == 0) 178.0 else -178.0), high, i * 16_000_000L) }
        assertEquals(180.0, kotlin.math.abs(deg(f.yaw)), 0.5)
    }

    @Test
    fun `a transient magnetic disturbance is rejected once converged`() {
        val f = HeadingFusion()
        repeat(200) { f.addCompass(rad(-20.0), high, it * 16_000_000L) }
        repeat(10) { f.addCompass(rad(60.0), high, (200 + it) * 16_000_000L) }
        assertEquals(-20.0, deg(f.yaw), 0.01)
        assertTrue(f.isConverged)
    }

    @Test
    fun `a persistent disagreement restarts the estimate instead of holding a wrong north`() {
        val f = HeadingFusion(resetAfterRejects = 30)
        repeat(200) { f.addCompass(rad(-20.0), high, it * 16_000_000L) }
        repeat(200) { f.addCompass(rad(60.0), high, (200 + it) * 16_000_000L) }
        assertEquals(60.0, deg(f.yaw), 0.5)
    }

    @Test
    fun `an accurate Geospatial yaw replaces the compass and holds it off for a while`() {
        val f = HeadingFusion(geospatialHoldNanos = 2_000_000_000L)
        repeat(200) { f.addCompass(rad(5.0), high, it * 16_000_000L) }
        val t0 = 10_000_000_000L
        repeat(40) { f.addGeospatial(rad(12.0), yawAccuracyDegrees = 3f, timestampNs = t0 + it * 33_000_000L) }
        assertEquals(HeadingFusion.Source.GEOSPATIAL, f.source)
        assertEquals(12.0, deg(f.yaw), 0.01)
        // Compass inside the hold window changes nothing.
        f.addCompass(rad(5.0), high, t0 + 40 * 33_000_000L + 100_000_000L)
        assertEquals(12.0, deg(f.yaw), 0.01)
    }

    @Test
    fun `an inaccurate Geospatial yaw is not trusted`() {
        val f = HeadingFusion()
        repeat(100) { f.addGeospatial(rad(12.0), yawAccuracyDegrees = 25f, timestampNs = it * 33_000_000L) }
        assertEquals(HeadingFusion.Source.NONE, f.source)
        assertFalse(f.isConverged)
        f.addGeospatial(rad(12.0), yawAccuracyDegrees = Float.NaN, timestampNs = 0L)
        assertEquals(HeadingFusion.Source.NONE, f.source)
    }

    @Test
    fun `reset forgets everything`() {
        val f = HeadingFusion()
        repeat(200) { f.addCompass(rad(5.0), high, it * 16_000_000L) }
        f.reset()
        assertFalse(f.isConverged)
        assertEquals(HeadingFusion.Source.NONE, f.source)
    }
}

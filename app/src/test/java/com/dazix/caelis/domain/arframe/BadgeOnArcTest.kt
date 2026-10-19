package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class BadgeOnArcTest {

    private val out = FloatArray(2)

    // A pill 160 x 52 px, the gap the rectangle placement left (18 px) and the least that clears a dot.
    private val halfW = 80f
    private val halfH = 26f
    private val gap = 18f
    private val minGap = 15f

    private fun offset(tx: Float, ty: Float) = BadgeOnArc.offset(tx, ty, halfW, halfH, gap, minGap, out)

    /** Distance from the origin (the dot) to the outline of the capsule centred at (cx, cy); 0 inside. */
    private fun distanceToCapsule(cx: Float, cy: Float): Float {
        val a = halfW - halfH
        // The dot relative to the capsule centre, and the nearest point of the capsule's spine to it.
        val px = -cx
        val py = -cy
        val nearestX = max(-a, min(a, px))
        val dx = px - nearestX
        return max(0f, sqrt(dx * dx + py * py) - halfH)
    }

    @Test
    fun `on a vertical arc the pill is half its height plus the least gap away`() {
        offset(0f, 5f)
        assertEquals(0f, out[0], 1e-3f)
        assertEquals(26f + 15f, out[1], 1e-3f)
    }

    @Test
    fun `on a level arc the pill is half its width plus the least gap away`() {
        offset(-3f, 0f)
        assertEquals(-(80f + 15f), out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }

    @Test
    fun `the pill lies along the arc whatever its slant`() {
        offset(30f, -40f)
        // Same direction as (30, -40): the ratio is kept.
        assertEquals(30f / -40f, out[0] / out[1], 1e-4f)
        assertTrue(out[0] > 0f && out[1] < 0f)
    }

    @Test
    fun `a capsule reaches less than its rectangle on a slant and the same on the axes`() {
        assertEquals(26f, BadgeOnArc.capsuleReach(0f, 1f, halfW, halfH), 1e-3f)
        assertEquals(80f, BadgeOnArc.capsuleReach(1f, 0f, halfW, halfH), 1e-3f)
        val t = 1f / sqrt(5f)
        val slanted = BadgeOnArc.capsuleReach(2f * t, t, halfW, halfH)
        val rectangle = abs(2f * t) * halfW + abs(t) * halfH
        assertTrue("capsule $slanted vs rectangle $rectangle", slanted < rectangle - 20f)
        // On the outline: a point at that reach is exactly halfH from the spine.
        val px = 2f * t * slanted
        val py = t * slanted
        val nearest = max(-(halfW - halfH), min(halfW - halfH, px))
        assertEquals(halfH, sqrt((px - nearest) * (px - nearest) + py * py), 1e-2f)
    }

    @Test
    fun `the gap to the pill's outline is three quarters of what the rectangle placement left, and never less than the least`() {
        for (degrees in 0 until 360 step 10) {
            val radians = Math.toRadians(degrees.toDouble())
            val tx = cos(radians).toFloat()
            val ty = sin(radians).toFloat()
            offset(tx, ty)
            val centreDistance = sqrt(out[0] * out[0] + out[1] * out[1])
            val outline = BadgeOnArc.capsuleReach(tx, ty, halfW, halfH)
            val rectangle = abs(tx) * halfW + abs(ty) * halfH
            val visible = centreDistance - outline
            val old = rectangle - outline + gap
            assertEquals("at $degrees°", max(old * BadgeOnArc.GAP_SHARE, minGap), visible, 1e-2f)
            assertTrue("at $degrees° the gap $visible is not larger than before $old", visible <= old)
        }
    }

    @Test
    fun `the dot stays clear of the pill whatever the slant`() {
        for (degrees in 0 until 360 step 10) {
            val radians = Math.toRadians(degrees.toDouble())
            offset(cos(radians).toFloat(), sin(radians).toFloat())
            val distance = distanceToCapsule(out[0], out[1])
            assertTrue("dot ${"%.1f".format(distance)} px from the pill at $degrees°", distance >= 4f)
        }
    }

    @Test
    fun `an arc with no direction puts the pill straight down`() {
        offset(0f, 0f)
        assertEquals(0f, out[0], 1e-3f)
        assertEquals(26f + 15f, out[1], 1e-3f)
    }

    @Test
    fun `a bubble on the stretch between the dot and the pill is hit and one beyond or beside is not`() {
        // Dot at the origin, pill centre 100 px down; a 46 x 21 px bubble.
        assertTrue(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 0f, 40f, 23f, 10.5f))
        assertTrue(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 15f, 40f, 23f, 10.5f))
        assertFalse(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 0f, 160f, 23f, 10.5f))
        assertFalse(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 60f, 40f, 23f, 10.5f))
        // One that ends the segment inside it counts, and so does one standing behind the dot's own end.
        assertTrue(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 0f, 95f, 23f, 10.5f))
        assertTrue(BadgeOnArc.segmentHitsRect(0f, 0f, 0f, 100f, 0f, -5f, 23f, 10.5f))
    }
}

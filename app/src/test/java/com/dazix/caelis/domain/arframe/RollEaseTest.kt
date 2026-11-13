package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both AR screens drop the phone's roll when a manual pose takes over; dropped in one frame, a phone
 * held at a slant made the whole sky jump. [RollEase] turns it out instead.
 */
class RollEaseTest {

    private fun level(azDeg: Double, elDeg: Double) =
        FloatArray(16).also { CelestialMatrixCalculator.computeManualRotationMatrix(azDeg, elDeg, it) }

    private fun rolled(azDeg: Double, elDeg: Double, rollDeg: Float) =
        level(azDeg, elDeg).also { RollEase.applyRoll(it, rollDeg) }

    @Test
    fun `the weight starts whole, only shrinks and ends at zero`() {
        assertEquals(1f, RollEase.weight(0L), 0f)
        var previous = 1f
        for (i in 1..50) {
            val w = RollEase.weight(RollEase.DURATION_NS * i / 50)
            assertTrue("weight $w at step $i", w <= previous && w >= 0f)
            previous = w
        }
        assertEquals(0f, RollEase.weight(RollEase.DURATION_NS), 0f)
        assertEquals(0f, RollEase.weight(RollEase.DURATION_NS * 10), 0f)
        assertEquals("a clock that steps back holds the start", 1f, RollEase.weight(-5L), 0f)
    }

    @Test
    fun `rollAgainst reads back the roll applyRoll put in`() {
        for (roll in listOf(-170f, -40f, 0f, 12.5f, 89f, 150f)) {
            assertEquals(roll, RollEase.rollAgainst(rolled(200.0, 20.0, roll), level(200.0, 20.0)), 1e-3f)
        }
    }

    @Test
    fun `the first frame is the phone's own pose and the last is level`() {
        val live = rolled(45.0, -10.0, 30f)
        val target = level(45.0, -10.0)
        val ease = RollEase()

        ease.begin(live, target, nowNs = 1_000L)

        val first = target.copyOf().also { ease.applyTo(it, 1_000L) }
        assertArrayEquals(live, first, 1e-5f)

        val halfway = target.copyOf().also { ease.applyTo(it, 1_000L + RollEase.DURATION_NS / 2) }
        assertEquals(30f * 0.125f, RollEase.rollAgainst(halfway, target), 1e-3f)

        val last = target.copyOf().also { ease.applyTo(it, 1_000L + RollEase.DURATION_NS) }
        assertArrayEquals(target, last, 0f)
        assertFalse("done once level", ease.isActive)
    }

    @Test
    fun `clear makes the next frame level`() {
        val target = level(300.0, 5.0)
        val ease = RollEase()
        ease.begin(rolled(300.0, 5.0, -25f), target, nowNs = 0L)
        assertTrue(ease.isActive)

        ease.clear()

        val m = target.copyOf().also { ease.applyTo(it, 0L) }
        assertArrayEquals(target, m, 0f)
    }
}

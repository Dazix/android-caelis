package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a Pixel 10 Pro's first start the compass stood about 180° out (its magnetometer carries a
 * ~450 µT offset from the built-in magnets, ten times the Earth's field, until the fusion has
 * estimated it) and the ±20° calibration could not reach the real sky.
 */
class CoarseHeadingTest {

    @Test
    fun `allowed only once the gyro holds the heading and the compass is not trusted`() {
        assertTrue(CoarseHeading.isAllowed(locked = true, compassState = HeadingState.UNRELIABLE))
        assertTrue(CoarseHeading.isAllowed(locked = true, compassState = HeadingState.LOW))
        assertFalse("a trusted compass keeps the ±20° bound", CoarseHeading.isAllowed(locked = true, compassState = HeadingState.OK))
        // Without the lock the compass would turn the overlay again as soon as its offset estimate
        // converged, and the coarse correction would then be the error.
        assertFalse(CoarseHeading.isAllowed(locked = false, compassState = HeadingState.UNRELIABLE))
    }

    @Test
    fun `the part beyond the bound becomes the coarse correction`() {
        assertEquals(160f, CoarseHeading.coarseFor(180f, currentCoarseDegrees = 0f, boundDegrees = 20f, allowed = true), 1e-4f)
        assertEquals(-150f, CoarseHeading.coarseFor(-170f, currentCoarseDegrees = 0f, boundDegrees = 20f, allowed = true), 1e-4f)
    }

    @Test
    fun `within the bound there is no coarse correction, and a drag back gives it up first`() {
        assertEquals(0f, CoarseHeading.coarseFor(12f, currentCoarseDegrees = 0f, boundDegrees = 20f, allowed = true), 0f)
        // 160 coarse + 20 calibration, dragged back by 30: the calibration stays at its bound.
        assertEquals(130f, CoarseHeading.coarseFor(150f, currentCoarseDegrees = 160f, boundDegrees = 20f, allowed = true), 1e-4f)
    }

    @Test
    fun `the total is taken the short way round`() {
        // 200° is -160°: a coarse correction of -140°, not +180°.
        assertEquals(-140f, CoarseHeading.coarseFor(200f, currentCoarseDegrees = 0f, boundDegrees = 20f, allowed = true), 1e-4f)
    }

    @Test
    fun `not allowed, the coarse correction stays what it was`() {
        assertEquals(0f, CoarseHeading.coarseFor(180f, currentCoarseDegrees = 0f, boundDegrees = 20f, allowed = false), 0f)
        assertEquals(90f, CoarseHeading.coarseFor(180f, currentCoarseDegrees = 90f, boundDegrees = 20f, allowed = false), 0f)
    }
}

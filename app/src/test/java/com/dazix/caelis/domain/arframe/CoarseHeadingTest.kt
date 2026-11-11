package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On a Pixel 10 Pro's first start the compass stood about 180° out (its magnetometer carries a
 * ~450 µT offset from the built-in magnets, ten times the Earth's field, until the fusion has
 * estimated it) and the ±20° calibration could not reach the real sky. The correction past the
 * bound is never refused: steel nearby shifts north by tens of degrees on a compass that is
 * consistent in every direction.
 */
class CoarseHeadingTest {

    @Test
    fun `the part beyond the bound becomes the coarse correction`() {
        assertEquals(160f, CoarseHeading.coarseFor(180f, boundDegrees = 20f), 1e-4f)
        assertEquals(-150f, CoarseHeading.coarseFor(-170f, boundDegrees = 20f), 1e-4f)
    }

    @Test
    fun `within the bound there is no coarse correction, and a drag back gives it up first`() {
        assertEquals(0f, CoarseHeading.coarseFor(12f, boundDegrees = 20f), 0f)
        // 160 coarse + 20 calibration, dragged back by 30: the calibration stays at its bound.
        assertEquals(130f, CoarseHeading.coarseFor(150f, boundDegrees = 20f), 1e-4f)
        // All the way back: nothing coarse is left.
        assertEquals(0f, CoarseHeading.coarseFor(-5f, boundDegrees = 20f), 0f)
    }

    @Test
    fun `the total is taken the short way round`() {
        // 200° is -160°: a coarse correction of -140°, not +180°.
        assertEquals(-140f, CoarseHeading.coarseFor(200f, boundDegrees = 20f), 1e-4f)
    }
}

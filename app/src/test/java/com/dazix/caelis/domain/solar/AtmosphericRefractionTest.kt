package com.dazix.caelis.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphericRefractionTest {

    private fun apparent(h: Double) = h + AtmosphericRefraction.degrees(h)

    @Test
    fun `the correction is continuous through the horizon`() {
        val above = AtmosphericRefraction.degrees(-0.8333 + 1e-9)
        val below = AtmosphericRefraction.degrees(-0.8333 - 1e-9)
        assertEquals(above, below, 1e-6)
        assertTrue("about 0.6° at the horizon, was $above", above in 0.5..0.7)
    }

    @Test
    fun `the correction does not go back to zero below the horizon`() {
        // Taking it away underground bends the day arc where it meets the horizon.
        for (h in listOf(-1.0, -2.0, -5.0, -15.0, -45.0, -89.0)) {
            val r = AtmosphericRefraction.degrees(h)
            assertTrue("refraction at $h° was $r", r >= 0.55)
        }
    }

    @Test
    fun `the correction settles on a constant far below the horizon`() {
        assertEquals(AtmosphericRefraction.deepValue, AtmosphericRefraction.degrees(-60.0), 1e-6)
        assertTrue("deep value ${AtmosphericRefraction.deepValue}", AtmosphericRefraction.deepValue in 0.6..1.5)
    }

    @Test
    fun `the apparent elevation changes its slope smoothly from far below the horizon to high up`() {
        val step = 0.01
        var previousSlope = Double.NaN
        var h = -30.0
        while (h < 60.0) {
            val slope = (apparent(h + step) - apparent(h)) / step
            assertTrue("apparent elevation must increase with the geometric one at $h°, slope $slope", slope > 0.5)
            if (!previousSlope.isNaN()) {
                assertTrue("slope jumps from $previousSlope to $slope at $h°", kotlin.math.abs(slope - previousSlope) < 0.005)
            }
            previousSlope = slope
            h += step
        }
    }

    @Test
    fun `there is no correction near the zenith`() {
        assertEquals(0.0, AtmosphericRefraction.degrees(89.0), 0.0)
    }
}

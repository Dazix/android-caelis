package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RidgeSamplesTest {

    private fun samples(perAzimuth: (Int) -> Int): RidgeSamples {
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1)
        for (i in 0 until HorizonProfile.SAMPLE_COUNT) offsets[i + 1] = offsets[i] + perAzimuth(i)
        val total = offsets.last()
        return RidgeSamples(offsets, FloatArray(total) { 1_000f + it }, FloatArray(total))
    }

    @Test
    fun `counts the points of each azimuth`() {
        val s = samples { if (it == 3) 2 else 1 }
        assertEquals(2, s.count(3))
        assertEquals(1, s.count(4))
    }

    @Test
    fun `refuses more points on one azimuth than the cap allows`() {
        assertThrows(IllegalArgumentException::class.java) {
            samples { if (it == 0) RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 2 else 0 }
        }
    }

    @Test
    fun `refuses offsets that run backwards`() {
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1) { 1 }
        offsets[0] = 0
        offsets[5] = 0
        assertThrows(IllegalArgumentException::class.java) {
            RidgeSamples(offsets, FloatArray(1), FloatArray(1))
        }
    }

    @Test
    fun `refuses arrays that do not match the offsets`() {
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1) { 0 }
        assertThrows(IllegalArgumentException::class.java) {
            RidgeSamples(offsets, FloatArray(1), FloatArray(0))
        }
    }
}

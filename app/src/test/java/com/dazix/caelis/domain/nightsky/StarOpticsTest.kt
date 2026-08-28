package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarOpticsTest {

    @Test
    fun `verifies limiting magnitude increases logarithmically with zoom`() {
        val mag04x = StarOptics.computeFaintestVisibleMagnitude(0.4f)
        val mag1x = StarOptics.computeFaintestVisibleMagnitude(1.0f)
        val mag2x = StarOptics.computeFaintestVisibleMagnitude(2.0f)
        val mag4x = StarOptics.computeFaintestVisibleMagnitude(4.0f)
        val mag10x = StarOptics.computeFaintestVisibleMagnitude(10.0f)
        val mag19x = StarOptics.computeFaintestVisibleMagnitude(19.0f)
        val mag150x = StarOptics.computeFaintestVisibleMagnitude(150.0f)

        assertEquals(4.81f, mag04x, 0.02f)
        assertEquals(5.8f, mag1x, 0.01f)
        assertEquals(6.55f, mag2x, 0.05f)
        assertEquals(7.30f, mag4x, 0.05f)
        assertEquals(8.30f, mag10x, 0.01f)
        assertEquals(9.00f, mag19x, 0.05f)
        assertEquals(11.24f, mag150x, 0.05f)

        // Monotonic progression
        assertTrue(mag04x < mag1x)
        assertTrue(mag1x < mag2x)
        assertTrue(mag2x < mag4x)
        assertTrue(mag4x < mag10x)
        assertTrue(mag10x < mag19x)
        assertTrue(mag19x < mag150x)
    }

    @Test
    fun `verifies optical stroke scaling uses square root to prevent ballooning`() {
        val scale04x = StarOptics.computeOpticScale(0.4f)
        val scale1x = StarOptics.computeOpticScale(1.0f)
        val scale4x = StarOptics.computeOpticScale(4.0f)
        val scale9x = StarOptics.computeOpticScale(9.0f)

        assertEquals(0.632f, scale04x, 0.01f)
        assertEquals(1.0f, scale1x, 0.001f)
        assertEquals(2.0f, scale4x, 0.001f)
        assertEquals(3.0f, scale9x, 0.001f)
    }

    @Test
    fun `verifies bucket index mapping for all stellar magnitude classes`() {
        // Bucket 0: Mag < 1.0 (Sirius -1.44, Vega 0.03, Rigel 0.13, Betelgeuse 0.5)
        assertEquals(0, StarOptics.computeBucketIndex(-1.44f))
        assertEquals(0, StarOptics.computeBucketIndex(0.0f))
        assertEquals(0, StarOptics.computeBucketIndex(0.99f))

        // Bucket 1: 1.0 <= Mag < 2.0 (Polaris 1.98, Bellatrix 1.64)
        assertEquals(1, StarOptics.computeBucketIndex(1.0f))
        assertEquals(1, StarOptics.computeBucketIndex(1.5f))
        assertEquals(1, StarOptics.computeBucketIndex(1.99f))

        // Bucket 2: 2.0 <= Mag < 3.0
        assertEquals(2, StarOptics.computeBucketIndex(2.0f))
        assertEquals(2, StarOptics.computeBucketIndex(2.7f))

        // Bucket 3: 3.0 <= Mag < 4.0
        assertEquals(3, StarOptics.computeBucketIndex(3.0f))
        assertEquals(3, StarOptics.computeBucketIndex(3.99f))

        // Bucket 4: 4.0 <= Mag < 5.0
        assertEquals(4, StarOptics.computeBucketIndex(4.0f))
        assertEquals(4, StarOptics.computeBucketIndex(4.8f))

        // Bucket 5: Mag >= 5.0 (Telescopic background stars up to mag 10.0+)
        assertEquals(5, StarOptics.computeBucketIndex(5.0f))
        assertEquals(5, StarOptics.computeBucketIndex(7.5f))
        assertEquals(5, StarOptics.computeBucketIndex(9.0f))
        assertEquals(5, StarOptics.computeBucketIndex(10.0f))
        assertEquals(5, StarOptics.computeBucketIndex(12.0f))
    }

    @Test
    fun `verifies atmospheric airmass extinction dims magnitude near horizon`() {
        val baseMag = 2.0f

        // High above horizon (> 10°): no extinction
        assertEquals(2.0f, StarOptics.computeAirmassExtinction(baseMag, 30.0f), 0.001f)
        assertEquals(2.0f, StarOptics.computeAirmassExtinction(baseMag, 10.0f), 0.001f)

        // Midway in extinction band (5.0°): +0.75 mag dimmer
        assertEquals(2.75f, StarOptics.computeAirmassExtinction(baseMag, 5.0f), 0.01f)

        // Exactly at horizon (0.0°): full extinction
        assertEquals(baseMag, StarOptics.computeAirmassExtinction(baseMag, 0.0f), 0.001f)

        // Sub-horizon (< 0°): unaffected by atmosphere (handled by landscape layer)
        assertEquals(baseMag, StarOptics.computeAirmassExtinction(baseMag, -10.0f), 0.001f)
    }

    @Test
    fun `verifies hybrid star rendering sprite constants`() {
        assertEquals(3.0f, StarOptics.SPRITE_MAGNITUDE_THRESHOLD, 0.001f)
        assertEquals(64, StarOptics.STAR_SPRITE_SIZE)
    }
}

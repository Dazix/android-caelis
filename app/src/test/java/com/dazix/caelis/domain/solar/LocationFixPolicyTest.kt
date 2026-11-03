package com.dazix.caelis.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixPolicyTest {

    @Test
    fun `with satellites only an accurate fresh gps or fused position qualifies`() {
        assertTrue(LocationFixPolicy.qualifies("gps", 15f, 5_000L, satellites = true))
        assertTrue(LocationFixPolicy.qualifies("fused", 100f, 59_999L, satellites = true))
        assertFalse("network is not waited out for", LocationFixPolicy.qualifies("network", 20f, 0L, satellites = true))
        assertFalse("over 100 m", LocationFixPolicy.qualifies("gps", 101f, 0L, satellites = true))
        assertFalse("a minute old", LocationFixPolicy.qualifies("gps", 10f, 60_000L, satellites = true))
    }

    @Test
    fun `without satellites a network position within 500 m and ten minutes qualifies`() {
        assertTrue(LocationFixPolicy.qualifies("network", 20f, 0L, satellites = false))
        assertTrue(LocationFixPolicy.qualifies("fused", 500f, 9 * 60_000L, satellites = false))
        assertTrue("a satellite position is still fine", LocationFixPolicy.qualifies("gps", 5f, 0L, satellites = false))
        assertFalse("a bare cell position", LocationFixPolicy.qualifies("network", 2_000f, 0L, satellites = false))
        assertFalse("ten minutes old", LocationFixPolicy.qualifies("network", 20f, 10 * 60_000L, satellites = false))
        assertFalse("passive has no provider of its own", LocationFixPolicy.qualifies("passive", 20f, 0L, satellites = false))
    }

    @Test
    fun `a position without accuracy never qualifies`() {
        assertFalse(LocationFixPolicy.qualifies("gps", null, 0L, satellites = true))
        assertFalse(LocationFixPolicy.qualifies("network", null, 0L, satellites = false))
    }

    @Test
    fun `a position stamped slightly ahead of the clock is fresh`() {
        assertTrue(LocationFixPolicy.qualifies("gps", 10f, -500L, satellites = true))
    }

    @Test
    fun `a device without satellites waits seconds, not minutes`() {
        assertEquals(120_000L, LocationFixPolicy.timeoutMs(satellites = true))
        assertEquals(15_000L, LocationFixPolicy.timeoutMs(satellites = false))
    }
}

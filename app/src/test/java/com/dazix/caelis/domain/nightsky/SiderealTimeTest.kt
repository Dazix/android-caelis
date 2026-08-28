package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SiderealTimeTest {

    @Test
    fun `verifies GMST at J2000 epoch (2000-01-01T12_00_00Z)`() {
        // J2000.0 is 2000-01-01T12:00:00Z -> JD 2451545.0
        val j2000Instant = Instant.parse("2000-01-01T12:00:00Z")
        val jd = SiderealTime.toJulianDate(j2000Instant)
        assertEquals(2451545.0, jd, 0.0001)

        val gmstDeg = SiderealTime.computeGmstDegrees(j2000Instant)
        assertEquals(280.4606, gmstDeg, 0.01)

        val gmstHours = gmstDeg / 15.0
        assertEquals(18.6974, gmstHours, 0.01)
    }

    @Test
    fun `verifies LST at Greenwich equals GMST`() {
        val instant = Instant.parse("2026-08-18T20:00:00Z")
        val gmst = SiderealTime.computeGmstDegrees(instant)
        val lstGreenwich = SiderealTime.computeLstDegrees(instant, 0.0)

        assertEquals(gmst, lstGreenwich, 0.0001)
    }

    @Test
    fun `verifies LST advances with East longitude`() {
        val instant = Instant.parse("2026-08-18T20:00:00Z")
        val lonPrague = 14.4378
        val gmst = SiderealTime.computeGmstDegrees(instant)
        val lstPrague = SiderealTime.computeLstDegrees(instant, lonPrague)

        val expected = (gmst + lonPrague) % 360.0
        assertEquals(expected, lstPrague, 0.0001)
    }

    @Test
    fun `verifies LST wrapping in range 0 to 360`() {
        val instant = Instant.parse("2026-08-18T20:00:00Z")
        val lstWest = SiderealTime.computeLstDegrees(instant, -170.0)
        assertTrue(lstWest in 0.0..360.0)

        val lstEast = SiderealTime.computeLstDegrees(instant, 170.0)
        assertTrue(lstEast in 0.0..360.0)
    }

    @Test
    fun `verifies Sidereal day rate is approximately 1_0027379 times solar day`() {
        val t0 = Instant.parse("2026-08-18T00:00:00Z")
        val t1 = Instant.parse("2026-08-19T00:00:00Z") // exactly 24 solar hours later

        val gmst0 = SiderealTime.computeGmstDegrees(t0)
        val gmst1 = SiderealTime.computeGmstDegrees(t1)

        var diffDeg = gmst1 - gmst0
        if (diffDeg < 0) diffDeg += 360.0

        // In 24 solar hours, Earth rotates 360° + ~0.9856° (modulo 360° = ~0.9856°)
        assertEquals(0.9856, diffDeg, 0.05)
    }

    @Test
    fun `verifies LST formatting`() {
        val formatted = SiderealTime.formatLstHours(18.5) // 18h 30m 00s
        assertEquals("18h 30m 00s", formatted)

        val formatted2 = SiderealTime.formatLstHours(0.0)
        assertEquals("00h 00m 00s", formatted2)

        val formatted3 = SiderealTime.formatLstHours(23.999)
        assertEquals("23h 59m 56s", formatted3)
    }
}

package com.dazix.caelis.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SolarDayTimesTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val pragueZone = ZoneId.of("Europe/Prague")

    // Tromsø (Norway, Arctic Circle: 69.6492° N, 18.9553° E)
    private val tromso = GeoLocation(69.6492, 18.9553)
    private val osloZone = ZoneId.of("Europe/Oslo")

    @Test
    fun `calculates summer solstice sunrise and sunset in Prague`() {
        val date = LocalDate.of(2026, 6, 21)
        val times = SolarDayTimesCalculator.calculate(date, prague, pragueZone)

        assertNotNull(times.sunriseUtc)
        assertNotNull(times.sunsetUtc)
        assertFalse(times.isPolarDay)
        assertFalse(times.isPolarNight)

        val sunriseLocal = times.sunriseUtc!!.atZone(pragueZone).toLocalTime()
        val sunsetLocal = times.sunsetUtc!!.atZone(pragueZone).toLocalTime()

        // Prague Summer Solstice (CEST = UTC+2): Sunrise ~04:52, Sunset ~21:16 (Day length ~16h 24m)
        assertEquals(4, sunriseLocal.hour)
        assertTrue("Sunrise minute approx 50, got ${sunriseLocal.minute}", sunriseLocal.minute in 45..58)

        assertEquals(21, sunsetLocal.hour)
        assertTrue("Sunset minute approx 15, got ${sunsetLocal.minute}", sunsetLocal.minute in 10..22)

        // Day length in seconds (~59000 s = ~16.4h)
        val dayLengthHours = times.dayLengthSeconds / 3600.0
        assertTrue("Day length should be ~16.4h, got $dayLengthHours", dayLengthHours in 16.0..16.8)

        // Sunrise azimuth in Prague at summer solstice is North-East (~50°), sunset North-West (~310°)
        assertNotNull(times.sunriseAzimuth)
        assertNotNull(times.sunsetAzimuth)
        assertTrue("Sunrise azimuth ~50°, got ${times.sunriseAzimuth}", times.sunriseAzimuth!! in 45.0..58.0)
        assertTrue("Sunset azimuth ~310°, got ${times.sunsetAzimuth}", times.sunsetAzimuth!! in 302.0..315.0)
    }

    @Test
    fun `calculates winter solstice sunrise and sunset in Prague`() {
        val date = LocalDate.of(2026, 12, 21)
        val times = SolarDayTimesCalculator.calculate(date, prague, pragueZone)

        assertNotNull(times.sunriseUtc)
        assertNotNull(times.sunsetUtc)

        val sunriseLocal = times.sunriseUtc!!.atZone(pragueZone).toLocalTime()
        val sunsetLocal = times.sunsetUtc!!.atZone(pragueZone).toLocalTime()

        // Prague Winter Solstice (CET = UTC+1): Sunrise ~08:00, Sunset ~16:02 (Day length ~8h 03m)
        assertEquals(8, sunriseLocal.hour)
        assertTrue("Sunrise minute approx 00, got ${sunriseLocal.minute}", sunriseLocal.minute in 0..5)

        assertEquals(16, sunsetLocal.hour)
        assertTrue("Sunset minute approx 02, got ${sunsetLocal.minute}", sunsetLocal.minute in 0..6)

        val dayLengthHours = times.dayLengthSeconds / 3600.0
        assertTrue("Day length should be ~8.0h, got $dayLengthHours", dayLengthHours in 7.8..8.3)

        // Sunrise azimuth in winter is South-East (~128°), sunset South-West (~232°)
        assertTrue("Sunrise azimuth ~128°, got ${times.sunriseAzimuth}", times.sunriseAzimuth!! in 124.0..132.0)
        assertTrue("Sunset azimuth ~232°, got ${times.sunsetAzimuth}", times.sunsetAzimuth!! in 228.0..236.0)
    }

    @Test
    fun `detects polar day in Arctic circle in June`() {
        val date = LocalDate.of(2026, 6, 21)
        val times = SolarDayTimesCalculator.calculate(date, tromso, osloZone)

        assertTrue(times.isPolarDay)
        assertFalse(times.isPolarNight)
        assertEquals(86400L, times.dayLengthSeconds)
    }

    @Test
    fun `detects polar night in Arctic circle in December`() {
        val date = LocalDate.of(2026, 12, 21)
        val times = SolarDayTimesCalculator.calculate(date, tromso, osloZone)

        assertTrue(times.isPolarNight)
        assertFalse(times.isPolarDay)
        assertEquals(0L, times.dayLengthSeconds)
    }

    @Test
    fun `accurately calculates solar noon in extreme timezone offset location (Kashgar, Xinjiang)`() {
        // Kashgar: 39.47° N, 75.99° E. Officially uses China Standard Time (UTC+8 / Asia/Shanghai).
        // Longitude 76°E corresponds to ~5h 04m UTC. In UTC+8, solar noon occurs around ~14:56 to 15:05 local time!
        val kashgar = GeoLocation(39.47, 75.99)
        val chinaZone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 6, 21)

        val times = SolarDayTimesCalculator.calculate(date, kashgar, chinaZone)
        val noonLocal = times.solarNoonUtc.atZone(chinaZone).toLocalTime()

        assertEquals(14, noonLocal.hour)
        assertTrue("Solar noon in Kashgar should be ~14:58 local time, was $noonLocal", noonLocal.minute in 50..60)
    }

    @Test
    fun `calculates golden hour intervals in Prague`() {
        val date = LocalDate.of(2026, 6, 21)
        val times = SolarDayTimesCalculator.calculate(date, prague, pragueZone)

        assertNotNull("Sunrise", times.sunriseUtc)
        assertNotNull("Sunset", times.sunsetUtc)
        assertNotNull("Morning golden hour start", times.goldenHourMorningStartUtc)
        assertNotNull("Morning golden hour end", times.goldenHourMorningEndUtc)
        assertNotNull("Evening golden hour start", times.goldenHourEveningStartUtc)
        assertNotNull("Evening golden hour end", times.goldenHourEveningEndUtc)

        val sunrise = times.sunriseUtc ?: error("Sunrise was null")
        val sunset = times.sunsetUtc ?: error("Sunset was null")
        val morningStart = times.goldenHourMorningStartUtc ?: error("Morning start was null")
        val morningEnd = times.goldenHourMorningEndUtc ?: error("Morning end was null")
        val eveningStart = times.goldenHourEveningStartUtc ?: error("Evening start was null")
        val eveningEnd = times.goldenHourEveningEndUtc ?: error("Evening end was null")

        // Morning golden hour starts before sunrise and ends after sunrise
        assertTrue(morningStart < sunrise)
        assertTrue(morningEnd > sunrise)

        // Evening golden hour starts before sunset and ends after sunset
        assertTrue(eveningStart < sunset)
        assertTrue(eveningEnd > sunset)
    }

    @Test
    fun `calculates solar events on spring DST changeover day`() {
        // Europe/Prague DST starts on 2026-03-29 (23-hour day)
        val date = LocalDate.of(2026, 3, 29)
        val times = SolarDayTimesCalculator.calculate(date, prague, pragueZone)

        assertNotNull(times.sunriseUtc)
        assertNotNull(times.sunsetUtc)
        assertTrue(times.sunsetUtc!! > times.sunriseUtc!!)
    }
}

package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class SolarPositionCalculatorTest {

    // Prague: 50.0755° N, 14.4378° E
    private val prague = GeoLocation(50.0755, 14.4378)

    // Equator: 0° N, 0° E
    private val equator = GeoLocation(0.0, 0.0)

    @Test
    fun `verifies solar position at solar noon in Prague on summer solstice`() {
        // 2026-06-21 approx 11:02 UTC is Solar Noon in Prague (14.44° E = approx 58 min before 12:00 UTC)
        val instant = Instant.parse("2026-06-21T11:02:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, prague)

        // At solar noon in northern hemisphere mid-latitudes, Azimuth is ~180° (due South)
        assertEquals(180.0, coords.azimuthDegrees, 1.5)
        // On summer solstice at 50°N: max elevation is approx 90° - 50.08° + 23.44° = 63.36°
        assertEquals(63.36, coords.apparentElevationDegrees, 1.0)
        assertTrue(coords.isAboveHorizon)
    }

    @Test
    fun `verifies solar position at solar noon in Prague on winter solstice`() {
        // 2026-12-21 approx 11:02 UTC is Solar Noon in Prague
        val instant = Instant.parse("2026-12-21T11:02:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, prague)

        assertEquals(180.0, coords.azimuthDegrees, 1.5)
        // On winter solstice at 50°N: max elevation is approx 90° - 50.08° - 23.44° = 16.48°
        assertEquals(16.5, coords.apparentElevationDegrees, 1.0)
        assertTrue(coords.isAboveHorizon)
    }

    @Test
    fun `verifies solar position directly overhead at Equator on Equinox noon`() {
        // 2026-03-20 12:00 UTC at 0°N, 0°E
        val instant = Instant.parse("2026-03-20T12:07:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, equator)

        // Sun should be nearly at zenith (90° elevation)
        assertTrue("Expected elevation > 89°, got ${coords.apparentElevationDegrees}", coords.apparentElevationDegrees > 88.5)
    }

    @Test
    fun `verifies sun is below horizon at local midnight`() {
        // 2026-06-21 23:02 UTC (approx midnight in Prague)
        val instant = Instant.parse("2026-06-21T23:02:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, prague)

        assertTrue(coords.apparentElevationDegrees < -10.0)
        assertTrue(!coords.isAboveHorizon)
        // At midnight, azimuth is near North (~0° / 360°)
        val diffFromNorth = minOf(coords.azimuthDegrees, 360.0 - coords.azimuthDegrees)
        assertTrue("Azimuth at midnight should be near North, was ${coords.azimuthDegrees}", diffFromNorth < 15.0)
    }

    @Test
    fun `verifies cardinal directions formatting`() {
        assertEquals("N", SolarCoordinates(0.0, 10.0).cardinalDirection())
        assertEquals("N", SolarCoordinates(350.0, 10.0).cardinalDirection())
        assertEquals("E", SolarCoordinates(90.0, 10.0).cardinalDirection())
        assertEquals("SE", SolarCoordinates(135.0, 10.0).cardinalDirection())
        assertEquals("S", SolarCoordinates(180.0, 10.0).cardinalDirection())
        assertEquals("SW", SolarCoordinates(225.0, 10.0).cardinalDirection())
        assertEquals("W", SolarCoordinates(270.0, 10.0).cardinalDirection())
        assertEquals("NW", SolarCoordinates(315.0, 10.0).cardinalDirection())
    }

    @Test
    fun `verifies atmospheric refraction numerical stability at zenith and near-zenith`() {
        // At Equator noon on Equinox: sun is near 90° elevation
        val instant = Instant.parse("2026-03-20T12:07:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, equator)

        assertTrue("Apparent elevation must be finite and not NaN", !coords.apparentElevationDegrees.isNaN())
        assertTrue("Apparent elevation must be <= 90.0°", coords.apparentElevationDegrees <= 90.0)
        assertTrue("Apparent elevation must equal or exceed geometric elevation", coords.apparentElevationDegrees >= coords.elevationDegrees)
    }

    @Test
    fun `verifies solar position calculation in western hemisphere (New York)`() {
        // New York: 40.7128° N, -74.0060° W. Solar noon is ~16:56 UTC (11:56 EST / 12:56 EDT)
        val newYork = GeoLocation(40.7128, -74.0060)
        val instant = Instant.parse("2026-06-21T16:56:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, newYork)

        // At solar noon, azimuth is approx 180° (due South)
        assertEquals(180.0, coords.azimuthDegrees, 2.0)
        // Peak elevation in NY on summer solstice: 90 - 40.71 + 23.44 = 72.73°
        assertEquals(72.7, coords.apparentElevationDegrees, 1.5)
        assertTrue(coords.isAboveHorizon)
    }

    @Test
    fun `verifies solar position calculation at North Pole`() {
        val northPole = GeoLocation(90.0, 0.0)
        // On summer solstice at North Pole, sun circles the sky at elevation ~23.44° for 24 hours
        val instant = Instant.parse("2026-06-21T12:00:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, northPole)

        assertEquals(23.44, coords.elevationDegrees, 0.5)
        assertTrue(coords.isAboveHorizon)
    }

    @Test
    fun `verifies solar position calculation at South Pole on December solstice`() {
        val southPole = GeoLocation(-90.0, 0.0)
        // On December 21 at South Pole, sun is at elevation ~23.44° (midnight sun)
        val instant = Instant.parse("2026-12-21T12:00:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, southPole)

        assertEquals(23.44, coords.elevationDegrees, 0.5)
        assertTrue(coords.isAboveHorizon)
    }

    @Test
    fun `verifies solar position calculation at South Pole on June solstice`() {
        val southPole = GeoLocation(-90.0, 0.0)
        // On June 21 at South Pole, sun is at elevation ~ -23.44° (polar night)
        val instant = Instant.parse("2026-06-21T12:00:00Z")
        val coords = SolarPositionCalculator.computePosition(instant, southPole)

        assertEquals(-23.44, coords.elevationDegrees, 0.5)
        assertTrue(!coords.isAboveHorizon)
    }

    @Test
    fun `verifies sunrise due East and sunset due West at Equator on Equinox`() {
        // Equator on 2026-03-20: Sunrise approx 06:07 UTC, Sunset approx 18:07 UTC
        val sunriseInstant = Instant.parse("2026-03-20T06:07:00Z")
        val sunsetInstant = Instant.parse("2026-03-20T18:07:00Z")

        val sunriseCoords = SolarPositionCalculator.computePosition(sunriseInstant, equator)
        val sunsetCoords = SolarPositionCalculator.computePosition(sunsetInstant, equator)

        // Sunrise azimuth is due East (~90°)
        assertEquals(90.0, sunriseCoords.azimuthDegrees, 1.0)
        // Sunset azimuth is due West (~270°)
        assertEquals(270.0, sunsetCoords.azimuthDegrees, 1.0)
    }
}

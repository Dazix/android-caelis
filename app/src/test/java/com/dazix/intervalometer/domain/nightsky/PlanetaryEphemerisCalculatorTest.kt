package com.dazix.intervalometer.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanetaryEphemerisCalculatorTest {

    @Test
    fun `computeAll returns all 8 celestial bodies with valid astronomical coordinates`() {
        val now = Instant.parse("2026-08-26T00:00:00Z")
        val planets = PlanetaryEphemerisCalculator.computeAll(now)

        assertEquals("Must return 8 bodies (Moon + 7 planets)", 8, planets.size)

        val ids = planets.map { it.id }
        assertTrue(ids.contains("Moon"))
        assertTrue(ids.contains("Mercury"))
        assertTrue(ids.contains("Venus"))
        assertTrue(ids.contains("Mars"))
        assertTrue(ids.contains("Jupiter"))
        assertTrue(ids.contains("Saturn"))
        assertTrue(ids.contains("Uranus"))
        assertTrue(ids.contains("Neptune"))

        for (p in planets) {
            assertTrue("${p.id} RA must be in [0, 360)", p.raDegrees in 0.0..<360.0)
            assertTrue("${p.id} Dec must be in [-90, +90]", p.decDegrees in -90.0..90.0)
            assertTrue("${p.id} angular diameter must be > 0", p.angularDiameterDeg > 0f)
            assertTrue("${p.id} illumination must be in [0, 1]", p.illuminationFraction in 0f..1f)
            assertNotNull("${p.id} distance string must not be null", p.distanceFormatted)
        }
    }

    @Test
    fun `verifies lunar ephemeris values and physical characteristics`() {
        val now = Instant.parse("2026-08-26T00:00:00Z")
        val planets = PlanetaryEphemerisCalculator.computeAll(now)
        val moon = planets.first { it.id == "Moon" }

        assertTrue(moon.isMoon)
        assertEquals("Měsíc", moon.localizedName(isCzech = true))
        assertEquals("Moon", moon.localizedName(isCzech = false))

        // Lunar physical disk angular diameter is ~0.48° to ~0.57° (29.3' to 33.5')
        assertTrue("Moon physical disk angular diameter should be ~0.52°", moon.angularDiameterDeg in 0.45f..0.60f)

        // Moon visual magnitude is between -12.8 (Full Moon) and -2.0 (New Moon / Crescent)
        assertTrue("Moon magnitude should be in realistic visual range", moon.magnitude in -12.8f..-2.0f)

        // Moon distance in km
        assertTrue("Moon distance string should contain km", moon.distanceFormatted.contains("km"))
    }

    @Test
    fun `verifies major planetary visual magnitudes and physical sizes`() {
        val j2000 = Instant.parse("2000-01-01T12:00:00Z")
        val planets = PlanetaryEphemerisCalculator.computeAll(j2000)

        val venus = planets.first { it.id == "Venus" }
        val jupiter = planets.first { it.id == "Jupiter" }
        val mars = planets.first { it.id == "Mars" }
        val saturn = planets.first { it.id == "Saturn" }

        // Venus is the brightest planet (-4.9 to -3.8)
        assertTrue("Venus should be very bright (mag < -3.0)", venus.magnitude < -3.0f)

        // Jupiter is very bright (-2.9 to -1.6)
        assertTrue("Jupiter should be bright (mag < -1.5)", jupiter.magnitude < -1.5f)

        // Jupiter has largest planetary angular diameter (~30 to 50 arcsec = 0.008° to 0.014° body)
        assertTrue("Jupiter physical body angular diameter should be ~0.01°", jupiter.angularDiameterDeg in 0.006f..0.020f)

        // Mars magnitude
        assertTrue("Mars magnitude in normal range", mars.magnitude in -3.0f..3.0f)

        // Saturn magnitude and physical body diameter (~14 to 20 arcsec = 0.004° to 0.006°)
        assertTrue("Saturn magnitude in normal range", saturn.magnitude in -1.0f..2.5f)
        assertTrue("Saturn physical body angular diameter should be ~0.005°", saturn.angularDiameterDeg in 0.003f..0.010f)
    }

    @Test
    fun `verifies Kepler solver convergence for high-eccentricity Mercury orbit across centuries`() {
        val epochs = listOf(
            Instant.parse("1900-01-01T00:00:00Z"),
            Instant.parse("2000-01-01T12:00:00Z"),
            Instant.parse("2050-06-15T18:30:00Z"),
            Instant.parse("2100-12-31T23:59:59Z"),
        )

        for (epoch in epochs) {
            val planets = PlanetaryEphemerisCalculator.computeAll(epoch)
            val mercury = planets.first { it.id == "Mercury" }
            assertTrue("Mercury RA valid at $epoch", mercury.raDegrees in 0.0..<360.0)
            assertTrue("Mercury Dec valid at $epoch", mercury.decDegrees in -90.0..90.0)
            assertTrue("Mercury diameter valid at $epoch", mercury.angularDiameterDeg > 0f)
        }
    }
}

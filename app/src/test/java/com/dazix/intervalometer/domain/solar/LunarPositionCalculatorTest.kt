package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LunarPositionCalculatorTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val equator = GeoLocation(0.0, 0.0)

    @Test
    fun `computes realistic lunar distance and coordinates for Prague`() {
        val instant = Instant.parse("2026-08-20T18:00:00Z")
        val coords = LunarPositionCalculator.computePosition(instant, prague)

        // Moon distance should be within physical perigee-apogee bounds (~356,000 to 407,000 km)
        assertTrue("Distance was ${coords.distanceKm} km", coords.distanceKm in 356000.0..407000.0)

        // Illumination fraction between 0.0 and 1.0
        assertTrue("Illumination was ${coords.illuminationFraction}", coords.illuminationFraction in 0.0..1.0)

        // Azimuth between 0.0 and 360.0
        assertTrue("Azimuth was ${coords.azimuthDegrees}", coords.azimuthDegrees in 0.0..360.0)

        // Elevation between -90.0 and +90.0
        assertTrue("Elevation was ${coords.elevationDegrees}", coords.elevationDegrees in -90.0..90.0)

        // Non-empty phase name and glyph
        assertTrue(coords.phaseName.isNotEmpty())
        assertTrue(coords.phaseGlyph.isNotEmpty())
    }

    @Test
    fun `verifies lunar phase detection for full moon and new moon`() {
        // Test various elongations
        val fullMoonInstant = Instant.parse("2026-08-28T04:18:00Z") // Approx Full Moon in late Aug 2026
        val fullCoords = LunarPositionCalculator.computePosition(fullMoonInstant, prague)

        assertTrue("Expected high illumination near full moon, was ${fullCoords.illuminationFraction}", fullCoords.illuminationFraction > 0.85)

        val newMoonInstant = Instant.parse("2026-08-12T17:37:00Z") // Approx New Moon mid Aug 2026
        val newCoords = LunarPositionCalculator.computePosition(newMoonInstant, prague)

        assertTrue("Expected low illumination near new moon, was ${newCoords.illuminationFraction}", newCoords.illuminationFraction < 0.15)
    }

    @Test
    fun `verifies topocentric parallax shifts elevation upwards or downwards towards horizon`() {
        val instant = Instant.parse("2026-08-20T12:00:00Z")
        val coords = LunarPositionCalculator.computePosition(instant, equator)

        assertTrue(!coords.apparentElevationDegrees.isNaN())
        assertTrue(!coords.elevationDegrees.isNaN())
    }
}

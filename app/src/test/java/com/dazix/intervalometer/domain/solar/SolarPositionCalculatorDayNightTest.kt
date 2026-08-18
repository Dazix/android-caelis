package com.dazix.intervalometer.domain.solar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SolarPositionCalculatorDayNightTest {

    private val pragueLocation = GeoLocation(50.0755, 14.4378)

    @Test
    fun `verifies solar noon in summer is daytime (not night)`() {
        // 2026-06-21 at 11:00 UTC (~13:00 CEST, solar noon in Prague)
        val noonSummer = Instant.parse("2026-06-21T11:00:00Z")
        val coords = SolarPositionCalculator.computePosition(noonSummer, pragueLocation)

        assertTrue("Sun elevation at solar noon should be > +50°, was ${coords.elevationDegrees}", coords.elevationDegrees > 50.0)
        assertFalse(SolarPositionCalculator.isSunBelowHorizon(noonSummer, pragueLocation))
        assertFalse(SolarPositionCalculator.isNight(noonSummer, pragueLocation))
    }

    @Test
    fun `verifies solar midnight in summer is night`() {
        // 2026-06-21 at 23:00 UTC (~01:00 CEST, midnight in Prague)
        val midnightSummer = Instant.parse("2026-06-21T23:00:00Z")
        val coords = SolarPositionCalculator.computePosition(midnightSummer, pragueLocation)

        assertTrue("Sun elevation at midnight should be < -10°, was ${coords.elevationDegrees}", coords.elevationDegrees < -10.0)
        assertTrue(SolarPositionCalculator.isSunBelowHorizon(midnightSummer, pragueLocation))
        assertTrue(SolarPositionCalculator.isNight(midnightSummer, pragueLocation))
    }

    @Test
    fun `verifies night state after sunset in winter`() {
        // 2026-12-21 at 17:00 UTC (18:00 CET, completely dark in Prague)
        val eveningWinter = Instant.parse("2026-12-21T17:00:00Z")
        val coords = SolarPositionCalculator.computePosition(eveningWinter, pragueLocation)

        assertTrue(coords.elevationDegrees < 0.0)
        assertTrue(SolarPositionCalculator.isNight(eveningWinter, pragueLocation))
    }
}

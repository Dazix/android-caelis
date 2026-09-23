package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CelestialVisibilityCalculatorTest {

    private val prague = GeoLocation(latitude = 50.0755, longitude = 14.4378)

    @Test
    fun `verifies computeTransitInstant yields exact instant where LST equals object RA`() {
        val raDegrees = 101.287 // Sirius RA
        val refInstant = Instant.parse("2026-03-21T18:00:00Z")

        val transitInstant = CelestialVisibilityCalculator.computeTransitInstant(
            raDegrees = raDegrees,
            referenceInstant = refInstant,
            longitudeDegrees = prague.longitude,
        )

        val transitJd = SiderealTime.toJulianDate(transitInstant)
        val calculatedLst = SiderealTime.calculateLST(transitJd, prague.longitude)

        var diff = (calculatedLst - raDegrees) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff < -180.0) diff += 360.0

        // Sub-second accuracy: < 0.001 degrees error
        assertEquals(0.0, diff, 0.001)
    }

    @Test
    fun `verifies circumpolar star like Polaris or Dubhe is detected as AlwaysAboveHorizon`() {
        // Dubhe (Alpha UMa) in Ursa Major: RA ~165.93°, Dec ~+61.75°
        // At Prague (lat 50.0755°), Dec + 61.75° is circumpolar (min elevation > 21°)
        val raDubhe = 165.93
        val decDubhe = 61.75
        val refInstant = Instant.parse("2026-06-21T21:00:00Z")

        val visibility = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raDubhe,
            decDegrees = decDubhe,
            referenceInstant = refInstant,
            location = prague,
            profile = null,
        )

        assertTrue("Expected AlwaysAboveHorizon, got $visibility", visibility is CelestialVisibility.AlwaysAboveHorizon)
        val circumpolar = visibility as CelestialVisibility.AlwaysAboveHorizon
        assertTrue("Max elevation should be > 75°, got ${circumpolar.maxElevationDegrees}", circumpolar.maxElevationDegrees > 75.0)
        assertTrue("Min elevation should be > 20°, got ${circumpolar.minElevationDegrees}", circumpolar.minElevationDegrees > 20.0)
    }

    @Test
    fun `verifies southern star Alpha Centauri never rises at observer latitude`() {
        // Alpha Centauri: RA 219.9°, Dec -60.83°
        // At Prague (lat 50.0755°), maximum possible elevation is 90 - (50.0755 - (-60.83)) = -20.9°
        val raAlphaCen = 219.9
        val decAlphaCen = -60.83
        val refInstant = Instant.parse("2026-05-01T20:00:00Z")

        val visibility = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raAlphaCen,
            decDegrees = decAlphaCen,
            referenceInstant = refInstant,
            location = prague,
            profile = null,
        )

        assertTrue("Expected AlwaysBelowHorizon, got $visibility", visibility is CelestialVisibility.AlwaysBelowHorizon)
        val neverRises = visibility as CelestialVisibility.AlwaysBelowHorizon
        assertTrue("Max elevation should be < 0°, got ${neverRises.maxElevationDegrees}", neverRises.maxElevationDegrees < 0.0)
    }

    @Test
    fun `verifies Sirius rises, transits, and sets in correct chronological order on flat horizon`() {
        // Sirius: RA 101.287°, Dec -16.716°
        val raSirius = 101.287
        val decSirius = -16.716
        val refInstant = Instant.parse("2026-01-15T20:00:00Z")

        val visibility = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raSirius,
            decDegrees = decSirius,
            referenceInstant = refInstant,
            location = prague,
            profile = null,
        )

        assertTrue("Expected RisesAndSets, got $visibility", visibility is CelestialVisibility.RisesAndSets)
        val schedule = visibility as CelestialVisibility.RisesAndSets

        // Check chronological ordering: Rise < Transit < Set
        assertTrue("Rise (${schedule.riseInstant}) must be before Transit (${schedule.transitInstant})",
            schedule.riseInstant.isBefore(schedule.transitInstant))
        assertTrue("Transit (${schedule.transitInstant}) must be before Set (${schedule.setInstant})",
            schedule.transitInstant.isBefore(schedule.setInstant))

        // Peak culmination elevation for Sirius at lat 50.0755° is ~23.2°
        assertEquals(23.2, schedule.maxElevationDegrees, 0.5)

        // Verify that at riseInstant and setInstant, the apparent elevation is ~0° (astronomical horizon crossing)
        val riseLst = SiderealTime.calculateLST(SiderealTime.toJulianDate(schedule.riseInstant), prague.longitude)
        val (_, riseEl) = EquatorialCoordinates.equatorialToHorizontal(raSirius, decSirius, riseLst, prague.latitude)
        val riseAppEl = CelestialVisibilityCalculator.applyRefraction(riseEl)
        assertEquals(0.0, riseAppEl, 0.05)

        val setLst = SiderealTime.calculateLST(SiderealTime.toJulianDate(schedule.setInstant), prague.longitude)
        val (_, setEl) = EquatorialCoordinates.equatorialToHorizontal(raSirius, decSirius, setLst, prague.latitude)
        val setAppEl = CelestialVisibilityCalculator.applyRefraction(setEl)
        assertEquals(0.0, setAppEl, 0.05)
    }

    @Test
    fun `verifies physical terrain ridge delays star rise time accurately`() {
        val raSirius = 101.287
        val decSirius = -16.716
        val refInstant = Instant.parse("2026-01-15T20:00:00Z")

        // Create a synthetic 1440-sample horizon profile with a 10.0° mountain ridge in the south-east (azimuth 110°..150°)
        val samples = FloatArray(1440) { 0f }
        for (i in (110 * 4)..(150 * 4)) {
            samples[i] = 10.0f // 10 degrees elevation
        }
        val terrainProfile = HorizonProfile(
            elevationsDegrees = samples,
            observer = prague,
            observerGroundElevationMeters = 250.0,
            eyeHeightMeters = 1.7,
            maxRangeMeters = 50000.0,
            generatedAtEpochMillis = System.currentTimeMillis(),
        )

        val visibility = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raSirius,
            decDegrees = decSirius,
            referenceInstant = refInstant,
            location = prague,
            profile = terrainProfile,
        )

        assertTrue("Expected RisesAndSets, got $visibility", visibility is CelestialVisibility.RisesAndSets)
        val schedule = visibility as CelestialVisibility.RisesAndSets

        // The star rises into the 10° ridge, so terrain rise is delayed relative to astronomical rise
        assertNotNull(schedule.riseDelayFromAstronomical)
        assertTrue("Rise delay should be positive (delayed by mountain), got ${schedule.riseDelayFromAstronomical?.toMinutes()} min",
            schedule.riseDelayFromAstronomical!!.toMinutes() > 10)
        assertTrue("Should be marked as obscured by terrain", schedule.isObscuredByTerrain)
    }

    @Test
    fun `verifies calculateVisibility with LocalDate overload calculates exact day visibility`() {
        val raSirius = 101.287
        val decSirius = -16.716
        val targetDate = java.time.LocalDate.of(2026, 1, 15)

        val visibility = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raSirius,
            decDegrees = decSirius,
            targetDate = targetDate,
            location = prague,
            profile = null,
            zoneId = java.time.ZoneOffset.UTC,
        )

        assertTrue(visibility is CelestialVisibility.RisesAndSets)
        val schedule = visibility as CelestialVisibility.RisesAndSets
        assertTrue(schedule.riseInstant.isBefore(schedule.transitInstant))
        assertTrue(schedule.transitInstant.isBefore(schedule.setInstant))
    }

    @Test
    fun `verifies sidereal seasonal progression shifts rise and transit times earlier by ~4 minutes per day`() {
        val raVega = 279.234
        val decVega = 38.783
        val date1 = java.time.LocalDate.of(2026, 6, 1)
        val date2 = java.time.LocalDate.of(2026, 6, 31 - 1) // +29 days later

        val vis1 = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raVega,
            decDegrees = decVega,
            targetDate = date1,
            location = prague,
            profile = null,
            zoneId = java.time.ZoneOffset.UTC,
        ) as CelestialVisibility.RisesAndSets

        val vis2 = CelestialVisibilityCalculator.calculateVisibility(
            raDegrees = raVega,
            decDegrees = decVega,
            targetDate = date2,
            location = prague,
            profile = null,
            zoneId = java.time.ZoneOffset.UTC,
        ) as CelestialVisibility.RisesAndSets

        // In 29 days, transit time should advance by 29 * (24h / 365.25d) ~ 1.9 hours earlier (in UTC time of day)
        val time1Seconds = vis1.transitInstant.atZone(java.time.ZoneOffset.UTC).toLocalTime().toSecondOfDay()
        val time2Seconds = vis2.transitInstant.atZone(java.time.ZoneOffset.UTC).toLocalTime().toSecondOfDay()

        var diffSeconds = time1Seconds - time2Seconds
        if (diffSeconds < 0) diffSeconds += 86400
        val diffMinutes = diffSeconds / 60

        // 29 days * ~3.93 min/day = ~114 minutes (± 5 min tolerance)
        assertTrue("Expected ~114 min shift earlier, got $diffMinutes min", diffMinutes in 105..125)
    }
}

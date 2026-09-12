package com.dazix.caelis.domain.solar

import com.dazix.caelis.domain.horizon.HorizonProfile
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRiseSetCalculatorTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val zone = ZoneId.of("Europe/Prague")
    private val summerDay: LocalDate = LocalDate.of(2026, 6, 21)

    private val trajectory = SolarTrajectoryPlanner.planTrajectory(summerDay, prague, zoneId = zone)

    /** A profile built from a function of azimuth, so the answer can be worked out by hand. */
    private fun profile(elevationAt: (Double) -> Float) = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) {
            elevationAt(HorizonProfile.azimuthForIndex(it))
        },
        observer = prague,
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
    )

    /** The dip of a flat Earth seen from a person's height: what "no terrain" actually looks like. */
    private val flatProfile = profile { -0.0379f }

    @Test
    fun `a flat skyline reproduces the astronomical sunset within a minute`() {
        val astronomical = trajectory.sunsetMarker
        assertNotNull("fixture must have an astronomical sunset", astronomical)

        val event = TerrainRiseSetCalculator.terrainSunset(trajectory, flatProfile, zone)
        assertNotNull("a flat horizon must still produce a sunset", event)

        val difference = Duration.between(astronomical!!.instant, event!!.instant).abs()
        assertTrue(
            "flat terrain sunset drifted by ${difference.toSeconds()} s from the astronomical one",
            difference.toMinutes() <= 1,
        )
    }

    @Test
    fun `a ridge to the west brings the sunset forward`() {
        // A 10 degree wall across the whole western half of the sky.
        val ridge = profile { azimuth -> if (azimuth in 180.0..359.99) 10f else -0.0379f }

        val flat = TerrainRiseSetCalculator.terrainSunset(trajectory, flatProfile, zone)!!
        val behindRidge = TerrainRiseSetCalculator.terrainSunset(trajectory, ridge, zone)!!

        assertTrue(
            "the Sun must disappear earlier behind a ridge: flat ${flat.localTime}, ridge ${behindRidge.localTime}",
            behindRidge.instant.isBefore(flat.instant),
        )
        // It sets when it reaches 10 degrees, which on a June day in Prague is well over half an hour early.
        val gained = Duration.between(behindRidge.instant, flat.instant)
        assertTrue("expected at least 30 minutes earlier, got ${gained.toMinutes()}", gained.toMinutes() >= 30)
        assertEquals(TerrainEventType.SET, behindRidge.type)
    }

    @Test
    fun `the reported delay is measured against the astronomical time`() {
        val ridge = profile { azimuth -> if (azimuth in 180.0..359.99) 10f else -0.0379f }
        val event = TerrainRiseSetCalculator.terrainSunset(trajectory, ridge, zone)!!

        val delay = event.delayFromAstronomical
        assertNotNull("the comparison to the almanac is the headline number", delay)
        assertTrue("a ridge can only make sunset earlier, got $delay", delay!!.isNegative)
        assertEquals(
            Duration.between(trajectory.sunsetMarker!!.instant, event.instant),
            delay,
        )
    }

    @Test
    fun `the crossing is refined well past the two minute sampling step`() {
        val ridge = profile { azimuth -> if (azimuth in 180.0..359.99) 10f else -0.0379f }
        val event = TerrainRiseSetCalculator.terrainSunset(trajectory, ridge, zone)!!

        val coords = SolarPositionCalculator.computePosition(event.instant, prague)
        // At the returned instant the Sun must be sitting on the ridge, not two minutes past it.
        assertEquals(10.0, coords.apparentElevationDegrees, 0.05)
    }

    @Test
    fun `a bowl the sun never clears is reported as hidden all day rather than as no sunrise`() {
        val bowl = profile { 80f }

        assertTrue(TerrainRiseSetCalculator.isSunHiddenAllDay(trajectory, bowl))
        assertTrue(TerrainRiseSetCalculator.solarEvents(trajectory, bowl, zone).isEmpty())
        assertNull(TerrainRiseSetCalculator.terrainSunset(trajectory, bowl, zone))
        assertNull(TerrainRiseSetCalculator.terrainSunrise(trajectory, bowl, zone))
    }

    @Test
    fun `an open sky is not hidden all day`() {
        assertFalse(TerrainRiseSetCalculator.isSunHiddenAllDay(trajectory, flatProfile))
    }

    /**
     * From a valley floor the Sun really can set behind one ridge, reappear through a saddle and set
     * again. Collapsing that to a single sunset is a lie the user only discovers on location.
     */
    /**
     * From a valley floor the Sun really can set behind one ridge, reappear through a saddle and set
     * again. Collapsing that to a single sunset is a lie the user only discovers on location.
     *
     * The gap is derived from the fixture's own trajectory rather than written in by hand: a saddle
     * at a guessed azimuth is a saddle the Sun of that particular date may never pass behind, and
     * the test would then be asserting nothing while looking like it asserts something.
     */
    @Test
    fun `a saddle in the ridge produces a second sunrise and a second sunset`() {
        val ridgeHeight = 12f
        val afternoonBelowRidge = trajectory.pathPoints
            .filter { it.instant.isAfter(trajectory.solarNoonMarker.instant) }
            .filter { it.coordinates.apparentElevationDegrees in 3.0..7.0 }
        assertTrue("fixture must descend through the ridge height", afternoonBelowRidge.isNotEmpty())

        val gapFrom = afternoonBelowRidge.minOf { it.coordinates.azimuthDegrees } - 1.0
        val gapTo = afternoonBelowRidge.maxOf { it.coordinates.azimuthDegrees } + 1.0

        val ridgeWithSaddle = profile { azimuth ->
            when {
                azimuth in gapFrom..gapTo -> -0.0379f // the gap
                azimuth in 180.0..359.99 -> ridgeHeight
                else -> -0.0379f
            }
        }

        val events = TerrainRiseSetCalculator.solarEvents(trajectory, ridgeWithSaddle, zone)

        assertTrue("expected four crossings through a saddle, got ${events.map { it.type }}", events.size >= 4)
        assertEquals(
            listOf(
                TerrainEventType.RISE,
                TerrainEventType.SET,
                TerrainEventType.RISE,
                TerrainEventType.SET,
            ),
            events.take(4).map { it.type },
        )
        // Events must come back in chronological order, whatever their type.
        for (i in 1 until events.size) {
            assertTrue("events out of order at $i", !events[i].instant.isBefore(events[i - 1].instant))
        }
    }

    @Test
    fun `sunrise over an eastern ridge comes later than the astronomical one`() {
        val easternRidge = profile { azimuth -> if (azimuth in 0.0..180.0) 8f else -0.0379f }

        val event = TerrainRiseSetCalculator.terrainSunrise(trajectory, easternRidge, zone)
        assertNotNull(event)
        assertEquals(TerrainEventType.RISE, event!!.type)
        assertTrue(
            "a ridge can only delay sunrise, got ${event.delayFromAstronomical}",
            event.delayFromAstronomical!!.toMinutes() > 0,
        )
    }

    @Test
    fun `the moon is crossed against the same skyline`() {
        val lunar = LunarTrajectoryPlanner.planTrajectory(summerDay, prague, zoneId = zone)
        val ridge = profile { azimuth -> if (azimuth in 180.0..359.99) 10f else -0.0379f }

        val events = TerrainRiseSetCalculator.lunarEvents(lunar, ridge, zone)

        events.forEach { event ->
            val coords = LunarPositionCalculator.computePosition(event.instant, prague)
            assertEquals(
                "the Moon must be on the skyline at a reported crossing",
                ridge.elevationAt(coords.azimuthDegrees),
                coords.apparentElevationDegrees,
                0.1,
            )
        }
    }

    @Test
    fun `an empty trajectory yields no events instead of throwing`() {
        val empty = trajectory.copy(pathPoints = emptyList())

        assertTrue(TerrainRiseSetCalculator.solarEvents(empty, flatProfile, zone).isEmpty())
        assertFalse(TerrainRiseSetCalculator.isSunHiddenAllDay(empty, flatProfile))
    }
}

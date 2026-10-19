package com.dazix.caelis.domain.solar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The apparent elevation along a day is a curve: Sun AR draws it as the day arc, and a step in it shows
 * as a kink in the line where the body meets the horizon. Refraction is the only term that can make one,
 * since the geometric elevation moves a quarter of a degree a minute at most.
 */
class RefractionContinuityTest {

    private val prague = GeoLocation(50.0755, 14.4378)

    // 5 s of sky is at most ~0.02°, plus the refraction slope near the horizon (~0.15° per degree).
    private val maxStepDegrees = 0.05

    private fun largestStep(elevations: (Instant) -> Double, from: Instant): Pair<Double, Instant> {
        var previous = elevations(from)
        var largest = 0.0
        var at = from
        var t = from
        for (i in 1..(24 * 3600 / 5)) {
            t = from.plusSeconds(5L * i)
            val current = elevations(t)
            val step = kotlin.math.abs(current - previous)
            if (step > largest) {
                largest = step
                at = t
            }
            previous = current
        }
        return largest to at
    }

    @Test
    fun `the moon's apparent elevation has no step as it crosses the horizon`() {
        for (day in listOf("2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z", "2026-11-02T00:00:00Z")) {
            val (largest, at) = largestStep({ LunarPositionCalculator.computePosition(it, prague).apparentElevationDegrees }, Instant.parse(day))
            assertTrue("moon: step of $largest° at $at", largest < maxStepDegrees)
        }
    }

    @Test
    fun `the sun's apparent elevation has no step as it crosses the horizon`() {
        for (day in listOf("2026-10-19T00:00:00Z", "2026-06-21T00:00:00Z")) {
            val (largest, at) = largestStep({ SolarPositionCalculator.computePosition(it, prague).apparentElevationDegrees }, Instant.parse(day))
            assertTrue("sun: step of $largest° at $at", largest < maxStepDegrees)
        }
    }
}

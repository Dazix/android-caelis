package com.dazix.caelis.domain.solar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * The day arc is a polyline of apparent positions, drawn in Sun AR as one line. Its direction in the
 * sky (azimuth shortened by the elevation, against elevation) must turn smoothly: a body moves on a
 * great circle and refraction is a smooth function of elevation, so a visible bend in the line at the
 * horizon is a fault in the model. The turn between neighbouring 2-minute steps is measured in degrees.
 */
class ArcBendTest {

    private val prague = GeoLocation(50.0755, 14.4378)
    private val zone = ZoneId.of("Europe/Prague")
    private val days = listOf("2026-10-19", "2026-10-20", "2026-12-21", "2027-03-20", "2027-06-21").map(LocalDate::parse)

    private class Bend(val maxNearHorizon: Double, val at: String, val maxElsewhere: Double)

    private fun measure(points: List<Triple<Double, Double, String>>): Bend {
        var nearMax = 0.0
        var nearAt = ""
        var elseMax = 0.0
        for (i in 1 until points.size - 1) {
            val (az0, el0, _) = points[i - 1]
            val (az1, el1, label) = points[i]
            val (az2, el2, _) = points[i + 1]
            if (el1 < -15.0) continue
            val a = direction(az0, el0, az1, el1)
            val b = direction(az1, el1, az2, el2)
            var turn = abs(Math.toDegrees(b - a))
            if (turn > 180.0) turn = 360.0 - turn
            if (el1 in -6.0..6.0) {
                if (turn > nearMax) {
                    nearMax = turn
                    nearAt = "$label el=${"%.2f".format(el1)}"
                }
            } else if (el1 in 6.0..60.0) {
                elseMax = maxOf(elseMax, turn)
            }
        }
        return Bend(nearMax, nearAt, elseMax)
    }

    private fun direction(az0: Double, el0: Double, az1: Double, el1: Double): Double {
        var dAz = az1 - az0
        if (dAz > 180.0) dAz -= 360.0
        if (dAz < -180.0) dAz += 360.0
        return atan2(el1 - el0, dAz * cos(Math.toRadians((el0 + el1) * 0.5)))
    }

    @Test
    fun `the sun's day arc does not bend at the horizon`() {
        for (day in days) {
            val path = SolarTrajectoryPlanner.planTrajectory(day, prague, zoneId = zone).pathPoints
            val bend = measure(path.map { Triple(it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, it.localTime.toString()) })
            println("BEND sun $day: near horizon ${"%.3f".format(bend.maxNearHorizon)}° at ${bend.at}, elsewhere ${"%.3f".format(bend.maxElsewhere)}°")
            assertTrue("sun $day: turn ${bend.maxNearHorizon}° at ${bend.at}, elsewhere ${bend.maxElsewhere}°", bend.maxNearHorizon < MAX_TURN_DEGREES)
        }
    }

    @Test
    fun `the moon's day arc does not bend at the horizon`() {
        for (day in days) {
            val path = LunarTrajectoryPlanner.planTrajectory(day, prague, zoneId = zone).pathPoints
            val bend = measure(path.map { Triple(it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, it.localTime.toString()) })
            println("BEND moon $day: near horizon ${"%.3f".format(bend.maxNearHorizon)}° at ${bend.at}, elsewhere ${"%.3f".format(bend.maxElsewhere)}°")
            assertTrue("moon $day: turn ${bend.maxNearHorizon}° at ${bend.at}, elsewhere ${bend.maxElsewhere}°", bend.maxNearHorizon < MAX_TURN_DEGREES)
        }
    }

    private companion object {
        // Measured: 1.5-2.5° at the horizon while refraction was taken away below it, 0.55-0.67° with it kept;
        // away from the horizon an arc turns 0.3-0.5° per step. The limit sits between the two cases.
        const val MAX_TURN_DEGREES = 1.0
    }
}

package com.dazix.intervalometer.domain.solar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LunarDayTimes(
    val date: LocalDate,
    val location: GeoLocation,
    val moonriseUtc: Instant?,
    val moonsetUtc: Instant?,
    val moonTransitUtc: Instant?,
    val moonriseAzimuth: Double?,
    val moonsetAzimuth: Double?,
    val maxElevationDegrees: Double,
    val phaseName: String,
    val phaseGlyph: String,
    val illuminationPercent: Int,
)

object LunarDayTimesCalculator {

    fun calculate(date: LocalDate, location: GeoLocation, zoneId: ZoneId = ZoneId.systemDefault()): LunarDayTimes {
        val startLocal = date.atStartOfDay(zoneId).toInstant()
        val endLocal = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        val startSec = startLocal.epochSecond
        val endSec = endLocal.epochSecond

        var moonriseUtc: Instant? = null
        var moonriseAz: Double? = null
        var moonsetUtc: Instant? = null
        var moonsetAz: Double? = null

        var maxElevation = -999.0
        var bestTransitSec = startSec

        // 5-minute sampling to find crossings and peak
        val step = 300L
        var prevSec = startSec
        var prevCoords = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(prevSec), location)
        if (prevCoords.apparentElevationDegrees > maxElevation) {
            maxElevation = prevCoords.apparentElevationDegrees
            bestTransitSec = prevSec
        }

        var curSec = startSec + step
        while (curSec <= endSec) {
            val curCoords = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(curSec), location)
            if (curCoords.apparentElevationDegrees > maxElevation) {
                maxElevation = curCoords.apparentElevationDegrees
                bestTransitSec = curSec
            }

            // Check moonrise (apparent elev crosses -0.8333° upwards)
            if (moonriseUtc == null && prevCoords.apparentElevationDegrees <= -0.8333 && curCoords.apparentElevationDegrees >= -0.8333) {
                val crossingSec = refineCrossing(prevSec, curSec, location, target = -0.8333, isRising = true)
                val crossingInstant = Instant.ofEpochSecond(crossingSec)
                val cCoords = LunarPositionCalculator.computePosition(crossingInstant, location)
                moonriseUtc = crossingInstant
                moonriseAz = cCoords.azimuthDegrees
            }

            // Check moonset (apparent elev crosses -0.8333° downwards)
            if (moonsetUtc == null && prevCoords.apparentElevationDegrees >= -0.8333 && curCoords.apparentElevationDegrees <= -0.8333) {
                val crossingSec = refineCrossing(prevSec, curSec, location, target = -0.8333, isRising = false)
                val crossingInstant = Instant.ofEpochSecond(crossingSec)
                val cCoords = LunarPositionCalculator.computePosition(crossingInstant, location)
                moonsetUtc = crossingInstant
                moonsetAz = cCoords.azimuthDegrees
            }

            prevSec = curSec
            prevCoords = curCoords
            curSec += step
        }

        // Refine peak transit second
        var lowSec = maxOf(startSec, bestTransitSec - 300L)
        var highSec = minOf(endSec, bestTransitSec + 300L)
        while (highSec - lowSec > 2) {
            val m1 = lowSec + (highSec - lowSec) / 3
            val m2 = highSec - (highSec - lowSec) / 3
            val e1 = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(m1), location).apparentElevationDegrees
            val e2 = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(m2), location).apparentElevationDegrees
            if (e1 < e2) {
                lowSec = m1
            } else {
                highSec = m2
            }
        }
        val transitInstant = Instant.ofEpochSecond((lowSec + highSec) / 2)
        val noonApprox = date.atTime(12, 0).atZone(zoneId).toInstant()
        val noonLunar = LunarPositionCalculator.computePosition(noonApprox, location)

        return LunarDayTimes(
            date = date,
            location = location,
            moonriseUtc = moonriseUtc,
            moonsetUtc = moonsetUtc,
            moonTransitUtc = transitInstant,
            moonriseAzimuth = moonriseAz,
            moonsetAzimuth = moonsetAz,
            maxElevationDegrees = maxElevation,
            phaseName = noonLunar.phaseName,
            phaseGlyph = noonLunar.phaseGlyph,
            illuminationPercent = (noonLunar.illuminationFraction * 100.0).toInt().coerceIn(0, 100),
        )
    }

    private fun refineCrossing(lowSec: Long, highSec: Long, location: GeoLocation, target: Double, isRising: Boolean): Long {
        var low = lowSec
        var high = highSec
        while (high - low > 1) {
            val mid = (low + high) / 2
            val elev = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(mid), location).apparentElevationDegrees
            if (isRising) {
                if (elev < target) low = mid else high = mid
            } else {
                if (elev > target) low = mid else high = mid
            }
        }
        return high
    }
}

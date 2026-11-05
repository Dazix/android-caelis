package com.dazix.caelis.domain.solar

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
    val phase: LunarPhase,
    val illuminationPercent: Int,
) {
    val phaseGlyph: String get() = phase.glyph
}

object LunarDayTimesCalculator {

    fun calculate(date: LocalDate, location: GeoLocation, zoneId: ZoneId = ZoneId.systemDefault()): LunarDayTimes {
        val startLocal = date.atStartOfDay(zoneId).toInstant()
        val endLocal = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        val startSec = startLocal.epochSecond
        val endSec = endLocal.epochSecond

        var maxElevation = -999.0
        var bestTransitSec = startSec

        // 1. 5-minute sampling across the calendar day to find the peak culmination (transit)
        val step = 300L
        var curSec = startSec
        while (curSec <= endSec) {
            val coords = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(curSec), location)
            if (coords.apparentElevationDegrees > maxElevation) {
                maxElevation = coords.apparentElevationDegrees
                bestTransitSec = curSec
            }
            curSec += step
        }

        // 2. Refine peak transit second
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
        val transitSec = transitInstant.epochSecond

        // 3. Search backwards from transit (up to 14h) for Moonrise belonging to this passage
        var moonriseUtc: Instant? = null
        var moonriseAz: Double? = null
        val riseSearchLimitSec = transitSec - (14 * 3600L)
        var searchSec = transitSec
        var nextCoords = LunarPositionCalculator.computePosition(transitInstant, location)

        while (searchSec > riseSearchLimitSec) {
            val prevS = searchSec - step
            val prevC = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(prevS), location)
            if (prevC.apparentElevationDegrees <= -0.8333 && nextCoords.apparentElevationDegrees >= -0.8333) {
                val crossingSec = refineCrossing(prevS, searchSec, location, target = -0.8333, isRising = true)
                val crossingInstant = Instant.ofEpochSecond(crossingSec)
                moonriseUtc = crossingInstant
                moonriseAz = LunarPositionCalculator.computePosition(crossingInstant, location).azimuthDegrees
                break
            }
            nextCoords = prevC
            searchSec = prevS
        }

        // 4. Search forwards from transit (up to 14h) for Moonset belonging to this passage
        var moonsetUtc: Instant? = null
        var moonsetAz: Double? = null
        val setSearchLimitSec = transitSec + (14 * 3600L)
        searchSec = transitSec
        var prevCoords = LunarPositionCalculator.computePosition(transitInstant, location)

        while (searchSec < setSearchLimitSec) {
            val nextS = searchSec + step
            val nextC = LunarPositionCalculator.computePosition(Instant.ofEpochSecond(nextS), location)
            if (prevCoords.apparentElevationDegrees >= -0.8333 && nextC.apparentElevationDegrees <= -0.8333) {
                val crossingSec = refineCrossing(searchSec, nextS, location, target = -0.8333, isRising = false)
                val crossingInstant = Instant.ofEpochSecond(crossingSec)
                moonsetUtc = crossingInstant
                moonsetAz = LunarPositionCalculator.computePosition(crossingInstant, location).azimuthDegrees
                break
            }
            prevCoords = nextC
            searchSec = nextS
        }

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
            phase = noonLunar.phase,
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

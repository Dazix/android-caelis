package com.dazix.caelis.domain.solar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Key astronomical events and times for a given day at a specific geographic location.
 */
data class SolarDayTimes(
    val date: LocalDate,
    val location: GeoLocation,
    val sunriseUtc: Instant?,
    val sunsetUtc: Instant?,
    val solarNoonUtc: Instant,
    val sunriseAzimuth: Double?,
    val sunsetAzimuth: Double?,
    val maxElevationDegrees: Double,
    val isPolarDay: Boolean,
    val isPolarNight: Boolean,
    val dayLengthSeconds: Long,
    val goldenHourMorningStartUtc: Instant?,
    val goldenHourMorningEndUtc: Instant?,
    val goldenHourEveningStartUtc: Instant?,
    val goldenHourEveningEndUtc: Instant?,
)

object SolarDayTimesCalculator {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI
    private const val HORIZON_ZENITH = 90.8333 // 90°50' for standard sunrise/sunset
    private const val GOLDEN_HOUR_TOP_ALTITUDE = 6.0
    private const val GOLDEN_HOUR_BOTTOM_ALTITUDE = -4.0

    /**
     * Calculates solar events for the specified [date], [location], and [zoneId].
     */
    fun calculate(date: LocalDate, location: GeoLocation, zoneId: ZoneId = ZoneId.systemDefault()): SolarDayTimes {
        val midnightLocal = date.atStartOfDay(zoneId).toInstant()
        val noonApproxUtc = date.atTime(12, 0).atZone(zoneId).toInstant()

        // Find Solar Noon by finding peak elevation near noon
        val solarNoonUtc = findMeridianTransit(date, location, zoneId)
        val noonCoords = SolarPositionCalculator.computePosition(solarNoonUtc, location)

        // Find Sunrise and Sunset
        val sunriseResult = findAltitudeCrossing(date, location, zoneId, targetAltitude = 0.0, isRising = true)
        val sunsetResult = findAltitudeCrossing(date, location, zoneId, targetAltitude = 0.0, isRising = false)

        val isPolarDay = noonCoords.apparentElevationDegrees > 0.0 && sunriseResult == null && sunsetResult == null
        val isPolarNight = noonCoords.apparentElevationDegrees <= 0.0 && sunriseResult == null && sunsetResult == null

        val sunriseUtc = sunriseResult?.first
        val sunsetUtc = sunsetResult?.first
        val sunriseAzimuth = sunriseResult?.second?.azimuthDegrees
        val sunsetAzimuth = sunsetResult?.second?.azimuthDegrees

        val dayLength = if (sunriseUtc != null && sunsetUtc != null) {
            max(0L, sunsetUtc.epochSecond - sunriseUtc.epochSecond)
        } else if (isPolarDay) {
            86400L
        } else {
            0L
        }

        // Golden Hour crossings (-4° to +6°)
        val ghMorningStart = findAltitudeCrossing(date, location, zoneId, GOLDEN_HOUR_BOTTOM_ALTITUDE, isRising = true)?.first
        val ghMorningEnd = findAltitudeCrossing(date, location, zoneId, GOLDEN_HOUR_TOP_ALTITUDE, isRising = true)?.first
        val ghEveningStart = findAltitudeCrossing(date, location, zoneId, GOLDEN_HOUR_TOP_ALTITUDE, isRising = false)?.first
        val ghEveningEnd = findAltitudeCrossing(date, location, zoneId, GOLDEN_HOUR_BOTTOM_ALTITUDE, isRising = false)?.first

        return SolarDayTimes(
            date = date,
            location = location,
            sunriseUtc = sunriseUtc,
            sunsetUtc = sunsetUtc,
            solarNoonUtc = solarNoonUtc,
            sunriseAzimuth = sunriseAzimuth,
            sunsetAzimuth = sunsetAzimuth,
            maxElevationDegrees = noonCoords.apparentElevationDegrees,
            isPolarDay = isPolarDay,
            isPolarNight = isPolarNight,
            dayLengthSeconds = dayLength,
            goldenHourMorningStartUtc = ghMorningStart,
            goldenHourMorningEndUtc = ghMorningEnd,
            goldenHourEveningStartUtc = ghEveningStart,
            goldenHourEveningEndUtc = ghEveningEnd,
        )
    }

    private fun findMeridianTransit(date: LocalDate, location: GeoLocation, zoneId: ZoneId): Instant {
        // Approximate UTC epoch second for solar noon based on longitude (4 minutes / 240s per degree)
        val utcMidnightSec = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val approxNoonSec = utcMidnightSec + 43200L - (location.longitude * 240.0).toLong()

        // Scan in a +/- 2-hour window around approximate solar noon with 5-minute sampling
        val windowStart = approxNoonSec - 7200L
        val windowEnd = approxNoonSec + 7200L

        var bestInstantSec = approxNoonSec
        var maxElevation = -999.0

        var currentSec = windowStart
        while (currentSec <= windowEnd) {
            val coords = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(currentSec), location)
            if (coords.elevationDegrees > maxElevation) {
                maxElevation = coords.elevationDegrees
                bestInstantSec = currentSec
            }
            currentSec += 300L
        }

        // Refine with ternary search / 1-second step within +/- 5 minutes
        var lowSec = bestInstantSec - 300L
        var highSec = bestInstantSec + 300L
        while (highSec - lowSec > 2) {
            val m1 = lowSec + (highSec - lowSec) / 3
            val m2 = highSec - (highSec - lowSec) / 3
            val e1 = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(m1), location).elevationDegrees
            val e2 = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(m2), location).elevationDegrees
            if (e1 < e2) {
                lowSec = m1
            } else {
                highSec = m2
            }
        }

        return Instant.ofEpochSecond((lowSec + highSec) / 2)
    }

    /**
     * Finds the instant when the sun crosses the given [targetAltitude] (e.g. 0.0° for sunrise/sunset).
     */
    private fun findAltitudeCrossing(
        date: LocalDate,
        location: GeoLocation,
        zoneId: ZoneId,
        targetAltitude: Double,
        isRising: Boolean,
    ): Pair<Instant, SolarCoordinates>? {
        val startLocal = date.atStartOfDay(zoneId).toInstant()
        val endLocal = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        val noonUtc = findMeridianTransit(date, location, zoneId)

        val windowStart = if (isRising) startLocal.epochSecond else noonUtc.epochSecond
        val windowEnd = if (isRising) noonUtc.epochSecond else endLocal.epochSecond

        if (windowStart >= windowEnd) return null

        var prevSec = windowStart
        var prevElev = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(prevSec), location).apparentElevationDegrees - targetAltitude

        val step = 300L // 5-minute sampling
        var foundInterval: Pair<Long, Long>? = null

        var currentSec = minOf(windowStart + step, windowEnd)
        while (currentSec <= windowEnd) {
            val currentElev = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(currentSec), location).apparentElevationDegrees - targetAltitude
            if (isRising && prevElev <= 0.0 && currentElev >= 0.0) {
                foundInterval = Pair(prevSec, currentSec)
                break
            } else if (!isRising && prevElev >= 0.0 && currentElev <= 0.0) {
                foundInterval = Pair(prevSec, currentSec)
                break
            }
            if (currentSec == windowEnd) break
            prevSec = currentSec
            prevElev = currentElev
            currentSec = minOf(currentSec + step, windowEnd)
        }

        val interval = foundInterval ?: return null

        // Binary search for exact crossing second
        var low = interval.first
        var high = interval.second
        while (high - low > 1) {
            val mid = (low + high) / 2
            val midElev = SolarPositionCalculator.computePosition(Instant.ofEpochSecond(mid), location).apparentElevationDegrees - targetAltitude
            if (isRising) {
                if (midElev < 0.0) low = mid else high = mid
            } else {
                if (midElev > 0.0) low = mid else high = mid
            }
        }

        val crossingInstant = Instant.ofEpochSecond(high)
        val finalCoords = SolarPositionCalculator.computePosition(crossingInstant, location)
        return Pair(crossingInstant, finalCoords)
    }
}

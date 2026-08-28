package com.dazix.caelis.domain.solar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LunarTrajectoryPoint(
    val instant: Instant,
    val localTime: LocalTime,
    val coordinates: LunarCoordinates,
    val isAboveHorizon: Boolean,
)

data class LunarTimeBubble(
    val instant: Instant,
    val formattedTime: String,
    val coordinates: LunarCoordinates,
    val isAboveHorizon: Boolean,
)

enum class LunarMarkerType {
    MOONRISE,
    MOONSET,
    MOON_TRANSIT,
    CURRENT_MOON,
}

data class LunarMarkerPoint(
    val type: LunarMarkerType,
    val instant: Instant,
    val formattedTime: String,
    val coordinates: LunarCoordinates,
)

data class LunarTrajectory(
    val date: LocalDate,
    val location: GeoLocation,
    val bubbleInterval: BubbleInterval,
    val pathPoints: List<LunarTrajectoryPoint>,
    val timeBubbles: List<LunarTimeBubble>,
    val moonriseMarker: LunarMarkerPoint?,
    val moonsetMarker: LunarMarkerPoint?,
    val moonTransitMarker: LunarMarkerPoint?,
    val dayTimes: LunarDayTimes,
)

object LunarTrajectoryPlanner {

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * Generates a complete 24-hour lunar trajectory, sampled curve points, time bubbles,
     * and moonrise/moonset markers for the given [date], [location], [bubbleInterval], and [zoneId].
     */
    fun planTrajectory(
        date: LocalDate,
        location: GeoLocation,
        bubbleInterval: BubbleInterval = BubbleInterval.ONE_HOUR,
        zoneId: ZoneId = ZoneId.systemDefault(),
        samplingStepMinutes: Int = 2,
    ): LunarTrajectory {
        val dayTimes = LunarDayTimesCalculator.calculate(date, location, zoneId)
        val transitInstant = dayTimes.moonTransitUtc ?: date.atTime(12, 0).atZone(zoneId).toInstant()

        // 1. Generate path vertices across the 24-hour lunar passage centered on transit
        // Sampling 12 hours before transit to 12 hours after transit (total 24 hours = 720 points with step = 2 min).
        // Endpoints fall into lunar nadir (deep underground, ~ -60° elevation), so the above-horizon passage
        // (Rise -> Culmination -> Set) is completely continuous, smooth, and free of midnight discontinuities.
        val pathPoints = mutableListOf<LunarTrajectoryPoint>()
        val startInstant = transitInstant.minusSeconds(12L * 3600L)
        val endInstant = transitInstant.plusSeconds(12L * 3600L)
        val stepSec = samplingStepMinutes * 60L

        var curSec = startInstant.epochSecond
        val endSec = endInstant.epochSecond
        while (curSec < endSec) {
            val instant = Instant.ofEpochSecond(curSec)
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = LunarPositionCalculator.computePosition(instant, location)

            pathPoints.add(
                LunarTrajectoryPoint(
                    instant = instant,
                    localTime = localTime,
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            curSec += stepSec
        }

        // 2. Generate time bubbles at configured interval along the path
        val timeBubbles = mutableListOf<LunarTimeBubble>()
        val startZdt = startInstant.atZone(zoneId)
        val remMin = startZdt.minute % bubbleInterval.minutes
        val bubbleOffsetMin = if (remMin > 0) bubbleInterval.minutes - remMin else 0
        var bubbleZdt = startZdt.plusMinutes(bubbleOffsetMin.toLong()).withSecond(0).withNano(0)
        val endZdt = endInstant.atZone(zoneId)

        while (!bubbleZdt.isAfter(endZdt)) {
            val bInstant = bubbleZdt.toInstant()
            val bLocalTime = bubbleZdt.toLocalTime()
            val coords = LunarPositionCalculator.computePosition(bInstant, location)

            timeBubbles.add(
                LunarTimeBubble(
                    instant = bInstant,
                    formattedTime = bLocalTime.format(TIME_FORMATTER),
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            bubbleZdt = bubbleZdt.plusMinutes(bubbleInterval.minutes.toLong())
        }

        // 3. Create markers for Moonrise, Moonset, and Moon Transit
        val moonriseMarker = dayTimes.moonriseUtc?.let { instant ->
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = LunarPositionCalculator.computePosition(instant, location)
            LunarMarkerPoint(
                type = LunarMarkerType.MOONRISE,
                instant = instant,
                formattedTime = localTime.format(TIME_FORMATTER),
                coordinates = coords,
            )
        }

        val moonsetMarker = dayTimes.moonsetUtc?.let { instant ->
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = LunarPositionCalculator.computePosition(instant, location)
            LunarMarkerPoint(
                type = LunarMarkerType.MOONSET,
                instant = instant,
                formattedTime = localTime.format(TIME_FORMATTER),
                coordinates = coords,
            )
        }

        val transitMarker = dayTimes.moonTransitUtc?.let { instant ->
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = LunarPositionCalculator.computePosition(instant, location)
            LunarMarkerPoint(
                type = LunarMarkerType.MOON_TRANSIT,
                instant = instant,
                formattedTime = localTime.format(TIME_FORMATTER),
                coordinates = coords,
            )
        }

        return LunarTrajectory(
            date = date,
            location = location,
            bubbleInterval = bubbleInterval,
            pathPoints = pathPoints,
            timeBubbles = timeBubbles,
            moonriseMarker = moonriseMarker,
            moonsetMarker = moonsetMarker,
            moonTransitMarker = transitMarker,
            dayTimes = dayTimes,
        )
    }
}

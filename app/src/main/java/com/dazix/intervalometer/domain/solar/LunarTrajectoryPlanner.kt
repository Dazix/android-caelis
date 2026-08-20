package com.dazix.intervalometer.domain.solar

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

        // 1. Generate path vertices across the 24-hour day (sampled every 2 minutes)
        val pathPoints = mutableListOf<LunarTrajectoryPoint>()
        var currentMinutes = 0
        while (currentMinutes < 1440) {
            val hour = currentMinutes / 60
            val minute = currentMinutes % 60
            val localTime = LocalTime.of(hour, minute)
            val instant = date.atTime(localTime).atZone(zoneId).toInstant()
            val coords = LunarPositionCalculator.computePosition(instant, location)

            pathPoints.add(
                LunarTrajectoryPoint(
                    instant = instant,
                    localTime = localTime,
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            currentMinutes += samplingStepMinutes
        }

        // 2. Generate time bubbles at configured interval along the path
        val timeBubbles = mutableListOf<LunarTimeBubble>()
        var bubbleMin = 0
        while (bubbleMin < 1440) {
            val hour = bubbleMin / 60
            val minute = bubbleMin % 60
            val localTime = LocalTime.of(hour, minute)
            val instant = date.atTime(localTime).atZone(zoneId).toInstant()
            val coords = LunarPositionCalculator.computePosition(instant, location)

            timeBubbles.add(
                LunarTimeBubble(
                    instant = instant,
                    formattedTime = localTime.format(TIME_FORMATTER),
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            bubbleMin += bubbleInterval.minutes
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

package com.dazix.caelis.domain.solar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BubbleInterval(val minutes: Int) {
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30),
    ONE_HOUR(60),
    TWO_HOURS(120),
    ;

    fun formatLabel(): String = when (this) {
        FIFTEEN_MINUTES -> "15 min"
        THIRTY_MINUTES -> "30 min"
        ONE_HOUR -> "1 h"
        TWO_HOURS -> "2 h"
    }
}

data class SolarTrajectoryPoint(
    val instant: Instant,
    val localTime: LocalTime,
    val coordinates: SolarCoordinates,
    val isAboveHorizon: Boolean,
)

data class SolarTimeBubble(
    val instant: Instant,
    val formattedTime: String,
    val coordinates: SolarCoordinates,
    val isAboveHorizon: Boolean,
)

enum class SolarMarkerType {
    SUNRISE,
    SUNSET,
    SOLAR_NOON,
    CURRENT_SUN,
}

data class SolarMarkerPoint(
    val type: SolarMarkerType,
    val instant: Instant,
    val formattedTime: String,
    val coordinates: SolarCoordinates,
)

data class SolarTrajectory(
    val date: LocalDate,
    val location: GeoLocation,
    val bubbleInterval: BubbleInterval,
    val pathPoints: List<SolarTrajectoryPoint>,
    val timeBubbles: List<SolarTimeBubble>,
    val sunriseMarker: SolarMarkerPoint?,
    val sunsetMarker: SolarMarkerPoint?,
    val solarNoonMarker: SolarMarkerPoint,
    val dayTimes: SolarDayTimes,
)

object SolarTrajectoryPlanner {

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * Generates a complete solar trajectory, sampled curve points, and time bubbles for the given [date],
     * [location], [bubbleInterval], and [zoneId].
     *
     * @param samplingStepMinutes Step between curve vertices in minutes (default 2 minutes = 720 points for smooth curve).
     */
    fun planTrajectory(
        date: LocalDate,
        location: GeoLocation,
        bubbleInterval: BubbleInterval = BubbleInterval.ONE_HOUR,
        zoneId: ZoneId = ZoneId.systemDefault(),
        samplingStepMinutes: Int = 2,
    ): SolarTrajectory {
        val dayTimes = SolarDayTimesCalculator.calculate(date, location, zoneId)

        // 1. Generate path vertices across the 24-hour day
        val pathPoints = mutableListOf<SolarTrajectoryPoint>()
        var currentMinutes = 0
        while (currentMinutes < 1440) {
            val hour = currentMinutes / 60
            val minute = currentMinutes % 60
            val localTime = LocalTime.of(hour, minute)
            val instant = date.atTime(localTime).atZone(zoneId).toInstant()
            val coords = SolarPositionCalculator.computePosition(instant, location)

            pathPoints.add(
                SolarTrajectoryPoint(
                    instant = instant,
                    localTime = localTime,
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            currentMinutes += samplingStepMinutes
        }

        // 2. Generate time bubbles at the configured interval along the path
        val timeBubbles = mutableListOf<SolarTimeBubble>()
        var bubbleMin = 0
        while (bubbleMin < 1440) {
            val hour = bubbleMin / 60
            val minute = bubbleMin % 60
            val localTime = LocalTime.of(hour, minute)
            val instant = date.atTime(localTime).atZone(zoneId).toInstant()
            val coords = SolarPositionCalculator.computePosition(instant, location)

            timeBubbles.add(
                SolarTimeBubble(
                    instant = instant,
                    formattedTime = localTime.format(TIME_FORMATTER),
                    coordinates = coords,
                    isAboveHorizon = coords.isAboveHorizon,
                )
            )
            bubbleMin += bubbleInterval.minutes
        }

        // 3. Create distinct markers for Sunrise, Sunset, and Solar Noon
        val sunriseMarker = dayTimes.sunriseUtc?.let { instant ->
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = SolarPositionCalculator.computePosition(instant, location)
            SolarMarkerPoint(
                type = SolarMarkerType.SUNRISE,
                instant = instant,
                formattedTime = localTime.format(TIME_FORMATTER),
                coordinates = coords,
            )
        }

        val sunsetMarker = dayTimes.sunsetUtc?.let { instant ->
            val localTime = instant.atZone(zoneId).toLocalTime()
            val coords = SolarPositionCalculator.computePosition(instant, location)
            SolarMarkerPoint(
                type = SolarMarkerType.SUNSET,
                instant = instant,
                formattedTime = localTime.format(TIME_FORMATTER),
                coordinates = coords,
            )
        }

        val noonInstant = dayTimes.solarNoonUtc
        val noonLocalTime = noonInstant.atZone(zoneId).toLocalTime()
        val noonCoords = SolarPositionCalculator.computePosition(noonInstant, location)
        val solarNoonMarker = SolarMarkerPoint(
            type = SolarMarkerType.SOLAR_NOON,
            instant = noonInstant,
            formattedTime = noonLocalTime.format(TIME_FORMATTER),
            coordinates = noonCoords,
        )

        return SolarTrajectory(
            date = date,
            location = location,
            bubbleInterval = bubbleInterval,
            pathPoints = pathPoints,
            timeBubbles = timeBubbles,
            sunriseMarker = sunriseMarker,
            sunsetMarker = sunsetMarker,
            solarNoonMarker = solarNoonMarker,
            dayTimes = dayTimes,
        )
    }
}

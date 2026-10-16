package com.dazix.caelis.domain.solar

import com.dazix.caelis.domain.horizon.HorizonProfile
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Whether the body came out from behind the terrain, or went behind it. */
enum class TerrainEventType { RISE, SET }

/**
 * A moment when the Sun or the Moon crosses the real skyline rather than the mathematical horizon.
 *
 * [delayFromAstronomical] is the honest headline number: "the Sun sets 25 minutes earlier than the
 * almanac says, because of that ridge". Null when there is no astronomical counterpart to compare
 * against — a polar day, or a body that never clears the terrain at all.
 */
data class TerrainEvent(
    val type: TerrainEventType,
    val instant: Instant,
    val localTime: LocalTime,
    val coordinates: SolarCoordinates,
    val delayFromAstronomical: java.time.Duration?,
)

/**
 * Finds when a body disappears behind the terrain, instead of when it would reach elevation zero
 * over a flat Earth.
 *
 * This is the number the whole terrain layer exists for. An almanac sunset at 19:07 is useless for
 * planning a time-lapse from a valley where the Sun actually goes behind the ridge at 18:42, and the
 * difference is not a constant — it depends on the azimuth the Sun happens to be setting at on that
 * particular date.
 *
 * Pure Kotlin, no Android imports, deterministic: the whole thing is checkable against a synthetic
 * profile whose answer can be worked out by hand.
 */
object TerrainRiseSetCalculator {

    /**
     * Crossings of the terrain skyline over the sampled day.
     *
     * Every sign change of `elevation - terrain(azimuth)` along [SolarTrajectory.pathPoints] is
     * refined by bisection down to [REFINEMENT_SECONDS]. All of them are returned, not just the
     * first and the last: from a valley floor the Sun genuinely can set behind one ridge, reappear
     * in a saddle and set again, and collapsing that to a single "sunset" would be a lie the user
     * finds out about on location.
     */
    fun solarEvents(
        trajectory: SolarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<TerrainEvent> {
        val points = trajectory.pathPoints
        if (points.size < 2) return emptyList()

        val events = mutableListOf<TerrainEvent>()
        var previousAbove = isAbove(points.first().coordinates, profile)

        for (index in 1 until points.size) {
            val above = isAbove(points[index].coordinates, profile)
            if (above == previousAbove) continue

            val type = if (above) TerrainEventType.RISE else TerrainEventType.SET
            val crossing = refine(
                earlier = points[index - 1].instant,
                later = points[index].instant,
                location = trajectory.location,
                profile = profile,
            )
            events += buildEvent(type, crossing, trajectory, profile, zoneId)
            previousAbove = above
        }
        return events
    }

    /** The same for the Moon. Sampled from its own trajectory, which moves far faster across the sky. */
    fun lunarEvents(
        trajectory: LunarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<TerrainEvent> {
        val points = trajectory.pathPoints
        if (points.size < 2) return emptyList()

        val events = mutableListOf<TerrainEvent>()
        var previousAbove = isAboveLunar(points.first().coordinates, profile)

        for (index in 1 until points.size) {
            val above = isAboveLunar(points[index].coordinates, profile)
            if (above == previousAbove) continue

            val type = if (above) TerrainEventType.RISE else TerrainEventType.SET
            val crossing = refineLunar(
                earlier = points[index - 1].instant,
                later = points[index].instant,
                location = trajectory.location,
                profile = profile,
            )
            val coords = LunarPositionCalculator.computePosition(crossing, trajectory.location)
            events += TerrainEvent(
                type = type,
                instant = crossing,
                localTime = crossing.atZone(zoneId).toLocalTime(),
                coordinates = SolarCoordinates(
                    azimuthDegrees = coords.azimuthDegrees,
                    elevationDegrees = coords.elevationDegrees,
                    apparentElevationDegrees = coords.apparentElevationDegrees,
                ),
                delayFromAstronomical = null,
            )
            previousAbove = above
        }
        return events
    }

    /** The last time the Sun goes behind the terrain and does not come back that day. */
    fun terrainSunset(
        trajectory: SolarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TerrainEvent? = solarEvents(trajectory, profile, zoneId).lastOrNull { it.type == TerrainEventType.SET }

    /** The first time the Sun clears the terrain that day. */
    fun terrainSunrise(
        trajectory: SolarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TerrainEvent? = solarEvents(trajectory, profile, zoneId).firstOrNull { it.type == TerrainEventType.RISE }

    /**
     * True when the Sun never clears the terrain at all on this day — a north-facing gorge in winter.
     *
     * Worth reporting as its own state rather than as "no sunrise found", which looks like a bug.
     */
    fun isSunHiddenAllDay(trajectory: SolarTrajectory, profile: HorizonProfile): Boolean =
        trajectory.pathPoints.isNotEmpty() && trajectory.pathPoints.none { isAbove(it.coordinates, profile) }

    /** The first time the Moon clears the terrain over its sampled trajectory. */
    fun terrainMoonrise(
        trajectory: LunarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TerrainEvent? = lunarEvents(trajectory, profile, zoneId).firstOrNull { it.type == TerrainEventType.RISE }

    /** The last time the Moon goes behind the terrain over its sampled trajectory. */
    fun terrainMoonset(
        trajectory: LunarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TerrainEvent? = lunarEvents(trajectory, profile, zoneId).lastOrNull { it.type == TerrainEventType.SET }

    /** True when the Moon never clears the terrain over its sampled trajectory. */
    fun isMoonHiddenAllDay(trajectory: LunarTrajectory, profile: HorizonProfile): Boolean =
        trajectory.pathPoints.isNotEmpty() && trajectory.pathPoints.none { isAboveLunar(it.coordinates, profile) }

    private fun buildEvent(
        type: TerrainEventType,
        instant: Instant,
        trajectory: SolarTrajectory,
        profile: HorizonProfile,
        zoneId: ZoneId,
    ): TerrainEvent {
        val coords = SolarPositionCalculator.computePosition(instant, trajectory.location)
        val astronomical = when (type) {
            TerrainEventType.RISE -> trajectory.sunriseMarker?.instant
            TerrainEventType.SET -> trajectory.sunsetMarker?.instant
        }
        return TerrainEvent(
            type = type,
            instant = instant,
            localTime = instant.atZone(zoneId).toLocalTime(),
            coordinates = coords,
            delayFromAstronomical = astronomical?.let { java.time.Duration.between(it, instant) },
        )
    }

    /**
     * Bisects the bracket down to [REFINEMENT_SECONDS].
     *
     * The trajectory is sampled every two minutes, which is far too coarse to quote a sunset time
     * from: near the horizon the Sun moves about a quarter of its own diameter in that step.
     */
    private fun refine(
        earlier: Instant,
        later: Instant,
        location: GeoLocation,
        profile: HorizonProfile,
    ): Instant {
        var low = earlier
        var high = later
        val startAbove = isAbove(SolarPositionCalculator.computePosition(low, location), profile)

        while (java.time.Duration.between(low, high).seconds > REFINEMENT_SECONDS) {
            val middle = low.plusSeconds(java.time.Duration.between(low, high).seconds / 2)
            val midAbove = isAbove(SolarPositionCalculator.computePosition(middle, location), profile)
            if (midAbove == startAbove) low = middle else high = middle
        }
        return high
    }

    private fun refineLunar(
        earlier: Instant,
        later: Instant,
        location: GeoLocation,
        profile: HorizonProfile,
    ): Instant {
        var low = earlier
        var high = later
        val startAbove = isAboveLunar(LunarPositionCalculator.computePosition(low, location), profile)

        while (java.time.Duration.between(low, high).seconds > REFINEMENT_SECONDS) {
            val middle = low.plusSeconds(java.time.Duration.between(low, high).seconds / 2)
            val midAbove = isAboveLunar(LunarPositionCalculator.computePosition(middle, location), profile)
            if (midAbove == startAbove) low = middle else high = middle
        }
        return high
    }

    /**
     * Compared against the *apparent* elevation, the one refraction has already lifted, because the
     * terrain profile itself is built with refraction folded into the Earth's radius. Mixing the
     * geometric elevation in here would double-count it in one direction and not the other.
     */
    private fun isAbove(coordinates: SolarCoordinates, profile: HorizonProfile): Boolean =
        coordinates.apparentElevationDegrees >= profile.elevationAt(coordinates.azimuthDegrees)

    private fun isAboveLunar(coordinates: LunarCoordinates, profile: HorizonProfile): Boolean =
        coordinates.apparentElevationDegrees >= profile.elevationAt(coordinates.azimuthDegrees)

    /** Bisection floor. One second is finer than the profile's own angular resolution justifies. */
    private const val REFINEMENT_SECONDS = 1L
}

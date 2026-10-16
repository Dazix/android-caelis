package com.dazix.caelis.domain.solar

import java.time.format.DateTimeFormatter
import java.util.Locale

/** What one rise or set badge in Sun AR says, and the sky direction its dot sits at. */
data class RiseSetBadge(
    val formattedTime: String,
    val azimuthDegrees: Double,
    val apparentElevationDegrees: Double,
    /** True when the time and the place are where the body crosses the real skyline. */
    val onTerrain: Boolean,
)

/** The four badges of a day; a null one is not drawn. */
data class RiseSetBadges(
    val sunrise: RiseSetBadge?,
    val sunset: RiseSetBadge?,
    val moonrise: RiseSetBadge?,
    val moonset: RiseSetBadge?,
)

/**
 * Decides what the rise and set badges show: with the real skyline drawn, the moment and the point
 * at which the body clears or meets *that* skyline, otherwise the almanac's flat horizon.
 *
 * An almanac badge under a drawn ridge says the Sun sets at a time and a place where it has already
 * been behind the hill for twenty minutes, which is exactly what the terrain layer is there to
 * correct. When the terrain hides the body for the whole day there is no badge at all rather than an
 * almanac one sitting behind the ridge. When the terrain was consulted but no crossing was found
 * (the body stays above the skyline through the sampled day while the almanac still has an event)
 * the almanac badge stays, so a missing crossing never hides a real one.
 */
object RiseSetBadgePolicy {

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * [terrainActive] is whether a terrain skyline is drawn; [terrain] and [hiddenAllDay] are only
     * read when it is.
     */
    fun sun(marker: SolarMarkerPoint?, terrain: TerrainEvent?, terrainActive: Boolean, hiddenAllDay: Boolean): RiseSetBadge? =
        pick(
            astronomical = marker?.let {
                RiseSetBadge(it.formattedTime, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, onTerrain = false)
            },
            terrain = terrain,
            terrainActive = terrainActive,
            hiddenAllDay = hiddenAllDay,
        )

    fun moon(marker: LunarMarkerPoint?, terrain: TerrainEvent?, terrainActive: Boolean, hiddenAllDay: Boolean): RiseSetBadge? =
        pick(
            astronomical = marker?.let {
                RiseSetBadge(it.formattedTime, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, onTerrain = false)
            },
            terrain = terrain,
            terrainActive = terrainActive,
            hiddenAllDay = hiddenAllDay,
        )

    private fun pick(astronomical: RiseSetBadge?, terrain: TerrainEvent?, terrainActive: Boolean, hiddenAllDay: Boolean): RiseSetBadge? {
        if (!terrainActive) return astronomical
        if (terrain != null) {
            return RiseSetBadge(
                formattedTime = terrain.localTime.format(TIME_FORMATTER),
                azimuthDegrees = terrain.coordinates.azimuthDegrees,
                apparentElevationDegrees = terrain.coordinates.apparentElevationDegrees,
                onTerrain = true,
            )
        }
        return if (hiddenAllDay) null else astronomical
    }
}

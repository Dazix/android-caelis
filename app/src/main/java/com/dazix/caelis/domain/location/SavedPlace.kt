package com.dazix.caelis.domain.location

import com.dazix.caelis.domain.solar.GeoLocation
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A place the user is not standing at, saved to plan the Sun and Moon against its own skyline.
 *
 * The zone is the place's own: Android cannot tell a zone from a coordinate offline, so the user
 * picks it once when saving, and every time shown for the place is its local time, not the phone's.
 * No altitude is kept — the observer's height comes from the DEM plus eye height, as it does for GPS.
 */
data class SavedPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val createdAtMs: Long,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be in [-90, 90]" }
        require(longitude in -180.0..180.0) { "Longitude must be in [-180, 180]" }
    }

    fun toGeoLocation(): GeoLocation = GeoLocation(latitude, longitude)

    /** The place's zone; an id this runtime does not know (a stale tz database) reads as UTC. */
    fun zone(): ZoneId = parseZone(zoneId)

    companion object {
        fun parseZone(id: String): ZoneId = try {
            ZoneId.of(id)
        } catch (_: DateTimeException) {
            ZoneOffset.UTC
        }
    }
}

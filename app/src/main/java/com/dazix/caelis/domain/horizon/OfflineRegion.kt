package com.dazix.caelis.domain.horizon

import kotlin.math.cos

/**
 * A user-defined named geographic region pre-downloaded for offline terrain horizon calculations.
 *
 * Stored in permanent app files (`filesDir/dem_offline/`) so that the Android cache manager will
 * never delete them under storage pressure. Overlapping regions share physical `.hgt` files to
 * guarantee zero data duplication on disk.
 *
 * @param id Unique identifier (UUID).
 * @param name User-visible label, e.g. "Senja - Norsko".
 * @param targetLat Center point latitude in degrees [-90..90].
 * @param targetLon Center point longitude in degrees [-180..180].
 * @param radiusKm Search and download radius in kilometers.
 * @param requiredTiles List of 1°×1° DEM tile keys that cover the circular region.
 * @param totalSizeBytes Total byte size of the required tiles on disk.
 * @param createdAtMs Timestamp when the offline region was saved.
 */
data class OfflineRegion(
    val id: String,
    val name: String,
    val targetLat: Double,
    val targetLon: Double,
    val radiusKm: Int,
    val requiredTiles: List<DemTileKey>,
    val totalSizeBytes: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MIN_COS_LATITUDE = 0.05

        /**
         * Calculates all [DemTileKey]s required to cover a circle of [radiusKm] around
         * ([latitude], [longitude]).
         */
        fun tilesFor(latitude: Double, longitude: Double, radiusKm: Int): List<DemTileKey> {
            val rangeMeters = radiusKm * 1000.0
            val latSpan = rangeMeters / EARTH_RADIUS_METERS * (180.0 / Math.PI)
            val cosLat = cos(latitude * Math.PI / 180.0).coerceAtLeast(MIN_COS_LATITUDE)
            val lonSpan = latSpan / cosLat

            return DemTileKey.coveringBox(
                minLatitude = (latitude - latSpan).coerceIn(-90.0, 89.999),
                maxLatitude = (latitude + latSpan).coerceIn(-90.0, 89.999),
                minLongitude = longitude - lonSpan,
                maxLongitude = longitude + lonSpan,
            )
        }
    }
}

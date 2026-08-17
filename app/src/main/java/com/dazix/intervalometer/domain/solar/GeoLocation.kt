package com.dazix.intervalometer.domain.solar

/**
 * Geographic observer location on Earth. Pure Kotlin, no Android imports.
 *
 * @param latitude Latitude in degrees [-90.0, 90.0], positive North, negative South.
 * @param longitude Longitude in degrees [-180.0, 180.0], positive East, negative West.
 * @param altitudeMeters Height above sea level in meters.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be in [-90, 90], got $latitude" }
        require(longitude in -180.0..180.0) { "Longitude must be in [-180, 180], got $longitude" }
    }

    /** Formats coordinates as a human-readable string, e.g. "50.08° N, 14.44° E". */
    fun formatShort(): String {
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return String.format(java.util.Locale.US, "%.2f° %s, %.2f° %s", Math.abs(latitude), latDir, Math.abs(longitude), lonDir)
    }

    companion object {
        /** Default fallback location (Prague, Czech Republic) if location permission is not granted. */
        val DEFAULT = GeoLocation(50.0755, 14.4378)
    }
}

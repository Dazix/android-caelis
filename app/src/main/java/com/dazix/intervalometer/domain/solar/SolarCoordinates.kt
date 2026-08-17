package com.dazix.intervalometer.domain.solar

/**
 * Position of the Sun in local horizontal coordinates (Azimuth, Elevation).
 *
 * @param azimuthDegrees Compass bearing from North (0° = North, 90° = East, 180° = South, 270° = West) [0.0, 360.0).
 * @param elevationDegrees Geometric angle above the true horizon in degrees [-90.0, 90.0].
 * @param apparentElevationDegrees Elevation angle corrected for atmospheric refraction.
 */
data class SolarCoordinates(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val apparentElevationDegrees: Double = elevationDegrees,
) {
    /** True if the solar disk center is above the visual horizon (zenith angle <= 90.8333°). */
    val isAboveHorizon: Boolean get() = apparentElevationDegrees >= -0.8333

    /** Returns azimuth cardinal direction name, e.g. "N", "NE", "E", "SE", "S", "SW", "W", "NW". */
    fun cardinalDirection(): String {
        val norm = (azimuthDegrees % 360.0 + 360.0) % 360.0
        return when {
            norm < 22.5 || norm >= 337.5 -> "N"
            norm < 67.5 -> "NE"
            norm < 112.5 -> "E"
            norm < 157.5 -> "SE"
            norm < 202.5 -> "S"
            norm < 247.5 -> "SW"
            norm < 292.5 -> "W"
            else -> "NW"
        }
    }
}

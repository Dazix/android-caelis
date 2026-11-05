package com.dazix.caelis.domain.solar

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

    /** Compass point of the azimuth; the UI names it in the user's language. */
    fun cardinalDirection(): CardinalDirection = CardinalDirection.of(azimuthDegrees)
}

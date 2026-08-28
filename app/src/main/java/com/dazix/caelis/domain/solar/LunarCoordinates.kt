package com.dazix.caelis.domain.solar

/**
 * Position and astronomical phase state of the Moon in local horizontal coordinates.
 *
 * @param azimuthDegrees Compass bearing from North (0° = North, 90° = East, 180° = South, 270° = West) [0.0, 360.0).
 * @param elevationDegrees Geometric angle above the true horizon in degrees [-90.0, 90.0] with topocentric parallax.
 * @param apparentElevationDegrees Elevation angle corrected for topocentric parallax and atmospheric refraction.
 * @param distanceKm Instantaneous distance from observer to Moon center in kilometers (~356,000 to 406,000 km).
 * @param illuminationFraction Fraction of lunar disk illuminated by sunlight [0.0, 1.0].
 * @param elongationDegrees Angular separation between Moon and Sun [0.0, 360.0).
 * @param phaseName Localized name of current lunar phase (e.g. "Úplněk", "První čtvrť").
 * @param phaseGlyph Emoji/Glyph representation (e.g. "🌕", "🌓", "🌒").
 */
data class LunarCoordinates(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val apparentElevationDegrees: Double = elevationDegrees,
    val distanceKm: Double = 384400.0,
    val illuminationFraction: Double = 0.5,
    val elongationDegrees: Double = 90.0,
    val phaseName: String = "",
    val phaseGlyph: String = "🌕",
) {
    /** True if the lunar disk center is above the visual horizon (apparent elevation >= -0.8333°). */
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

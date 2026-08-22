package com.dazix.intervalometer.domain.nightsky

/**
 * Immutable presentation model representing a selected celestial body for display in the detail sheet.
 * Agnostic of data source (FlatBuffers star catalog vs solar system dynamic ephemeris).
 *
 * @param name Common or catalog designation of the object (e.g., "Sirius", "HIP 32349").
 * @param type Classification of the body (e.g., "Binary Star", "Red Supergiant", "Planet").
 * @param constellation Name of the containing constellation, if applicable (e.g., "Canis Major").
 * @param distance Pre-formatted distance string (e.g., "8.6 ly", "1.52 AU", "—").
 * @param magnitude Apparent astronomical visual magnitude (V band).
 * @param raDegrees Right Ascension in degrees J2000.0 [0.0, 360.0).
 * @param decDegrees Declination in degrees J2000.0 [-90.0, +90.0].
 */
data class CelestialBodyDetails(
    val name: String,
    val type: String,
    val constellation: String?,
    val distance: String,
    val magnitude: Float,
    val raDegrees: Double? = null,
    val decDegrees: Double? = null,
)

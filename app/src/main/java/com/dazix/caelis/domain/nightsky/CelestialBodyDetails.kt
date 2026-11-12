package com.dazix.caelis.domain.nightsky

/**
 * Immutable presentation model representing a selected celestial body (Star or Constellation) for display in the detail sheet.
 * Agnostic of data source (FlatBuffers star catalog, constellation network, or ephemeris).
 *
 * @param name Common or catalog designation of the object (e.g., "Sirius", "Ursa Major", "HIP 32349").
 * @param subtitle Classification of the body (e.g., "Binary Star", "Red Supergiant", "Souhvězdí", "Constellation").
 * @param property1 Primary label-value attribute pair (e.g., "Constellation" -> "Ursa Major" or "Brightest star" -> "Alioth").
 * @param property2 Secondary label-value attribute pair (e.g., "Distance" -> "8.6 ly" or "Family" -> "Ursa Major Family").
 * @param magnitude Apparent astronomical visual magnitude (V band), null for constellations.
 * @param raDegrees Right Ascension in degrees J2000.0 [0.0, 360.0).
 * @param decDegrees Declination in degrees J2000.0 [-90.0, +90.0].
 * @param figures The constellation and asterisms the body belongs to (or, for a figure, its
 *   brightest star and, for an asterism, the constellations it is drawn across), each name
 *   selectable from the sheet.
 */
data class CelestialBodyDetails(
    val name: String,
    val subtitle: String,
    val property1: Pair<String, String>? = null,
    val property2: Pair<String, String>? = null,
    val property3: Pair<String, String>? = null,
    val magnitude: Float? = null,
    val raDegrees: Double? = null,
    val decDegrees: Double? = null,
    val figures: List<DetailLinkGroup> = emptyList(),
)

/** One labelled row of selectable names in the detail sheet ("Asterisms: Winter Hexagon, …"). */
data class DetailLinkGroup(val label: String, val links: List<DetailLink>)

/** A name in the detail sheet that selects what it names. */
data class DetailLink(val name: String, val target: DetailLinkTarget)

/** What a [DetailLink] selects, by catalog index. */
sealed interface DetailLinkTarget {
    /** A constellation or asterism. */
    data class Figure(val constellationIndex: Int) : DetailLinkTarget

    /** A star. */
    data class Star(val starIndex: Int) : DetailLinkTarget
}

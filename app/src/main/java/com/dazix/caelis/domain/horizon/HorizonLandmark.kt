package com.dazix.caelis.domain.horizon

import java.util.Locale

/**
 * A named peak as the data pipeline wrote it: a place on the Earth, before anyone asked whether an
 * observer can see it.
 *
 * Names are empty when missing rather than null, which is what the `.peaks` file stores. [name] is
 * the local name and is never empty: it is the last fallback of [HorizonLandmark.displayName], so a
 * peak with no translation shows its own name instead of nothing.
 */
data class LandmarkPeak(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int,
    val name: String,
    val nameCs: String,
    val nameEn: String,
)

/**
 * A peak that [LandmarkVisibility] decided is on the skyline, with where it sits in the observer's sky.
 *
 * All three names are kept, so switching the app language redraws the labels without a download.
 * [apparentElevationDegrees] is the altitude of the summit with Earth curvature and refraction
 * folded in, the same quantity the horizon profile stores, which is what lets the label sit on the
 * ridge it names.
 */
data class HorizonLandmark(
    val name: String,
    val nameCs: String,
    val nameEn: String,
    val elevationMeters: Int,
    val distanceMeters: Double,
    val azimuthDegrees: Double,
    val apparentElevationDegrees: Double,
) {

    /**
     * The name to print for an app language such as `cs`, `cs-CZ` or `en`.
     *
     * Czech wants the Czech name, every other language the English one, and the local [name] is the
     * fallback for both: "Śnieżka" without a `name:cs` stays Polish rather than blank. An empty or
     * blank entry counts as missing.
     */
    fun displayName(languageTag: String): String {
        val language = languageTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        val preferred = if (language == "cs") nameCs else nameEn
        return preferred.takeIf { it.isNotBlank() } ?: name
    }

    /**
     * What is printed on the skyline: the [displayName] and the summit's height, "Sněžka · 1603 m".
     *
     * The height is the OSM `ele` as it is, in metres and without a thousands separator: a label is
     * read at arm's length and the unit is the same in every language the app speaks.
     */
    fun label(languageTag: String): String = "${displayName(languageTag)} · $elevationMeters m"
}

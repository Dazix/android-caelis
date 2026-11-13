package com.dazix.caelis.domain.horizon

import java.util.Locale

/**
 * A named peak as the data pipeline wrote it: a place on the Earth, before anyone asked whether an
 * observer can see it.
 *
 * [name] is the local name and is never empty: it is the last fallback of
 * [HorizonLandmark.displayName], so a peak with no translation shows its own name instead of
 * nothing. [names] holds the others the tile carries, keyed by language (`cs`, `de`, `en`, `pl`,
 * `sk`) or [LandmarkNames.LATIN] for a Latin-script spelling of a name in another script; a missing
 * key is a missing name.
 */
data class LandmarkPeak(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int,
    val name: String,
    val names: Map<String, String> = emptyMap(),
)

/**
 * A peak that [LandmarkVisibility] decided is on the skyline, with where it sits in the observer's sky.
 *
 * All the names are kept, so switching the app language redraws the labels without a download.
 * [apparentElevationDegrees] is the altitude of the summit with Earth curvature and refraction
 * folded in, the same quantity the horizon profile stores, which is what lets the label sit on the
 * ridge it names.
 */
data class HorizonLandmark(
    val name: String,
    val names: Map<String, String> = emptyMap(),
    val elevationMeters: Int,
    val distanceMeters: Double,
    val azimuthDegrees: Double,
    val apparentElevationDegrees: Double,
) {

    /** The name to print for an app language such as `cs`, `cs-CZ` or `en`; see [LandmarkNames.pick]. */
    fun displayName(languageTag: String): String = LandmarkNames.pick(name, names, languageTag)

    /**
     * What is printed on the skyline: the [displayName] and the summit's height, "Sněžka · 1603 m".
     *
     * The height is the OSM `ele` as it is, in metres and without a thousands separator: a label is
     * read at arm's length and the unit is the same in every language the app speaks.
     */
    fun label(languageTag: String): String = "${displayName(languageTag)} · $elevationMeters m"
}

/** Which of a peak's names a user reads. */
object LandmarkNames {

    /** The key of a Latin-script spelling of a local name written in another script. */
    const val LATIN = "Latn"

    /**
     * The name for [languageTag], in this order:
     *
     * 1. the name in the app's language;
     * 2. for Czech the Slovak one and for Slovak the Czech one, which either reader takes in at a glance;
     * 3. the local name when it is in Latin script, for every language but English: a German in the
     *    Krkonoše reads "Śnieżka" on the sign, not the English "Sniezka";
     * 4. the English name, then the Latin spelling, so a peak in Japan or Greece is not printed in a
     *    script the user may not read;
     * 5. the local name.
     *
     * An empty or blank entry counts as missing.
     */
    fun pick(name: String, names: Map<String, String>, languageTag: String): String {
        val language = languageTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        names.usable(language)?.let { return it }
        SIBLINGS[language]?.let { sibling -> names.usable(sibling)?.let { return it } }
        if (language != "en" && isLatin(name)) return name
        return names.usable("en") ?: names.usable(LATIN) ?: name
    }

    /** True when every letter of [text] is Latin script and it has at least one, as the pipeline decides. */
    fun isLatin(text: String): Boolean {
        var letters = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetter(codePoint)) {
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) return false
                letters++
            }
            i += Character.charCount(codePoint)
        }
        return letters > 0
    }

    private val SIBLINGS = mapOf("cs" to "sk", "sk" to "cs")

    private fun Map<String, String>.usable(key: String): String? = get(key)?.takeIf { it.isNotBlank() }
}

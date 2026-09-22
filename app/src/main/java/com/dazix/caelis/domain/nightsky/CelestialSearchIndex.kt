package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.data.nightsky.StarMetadata
import java.text.Normalizer

private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()

val CZECH_DSO_NAMES: Map<String, String> = mapOf(
    "M1" to "Krabí mlhovina",
    "M6" to "Motýlí hvězdokupa",
    "M7" to "Ptolemaiova hvězdokupa",
    "M8" to "Mlhovina Lagun",
    "M11" to "Hvězdokupa Divoká kachna",
    "M13" to "Velká kulová hvězdokupa v Herkulovi",
    "M16" to "Orlí mlhovina",
    "M17" to "Mlhovina Omega / Labuť",
    "M20" to "Mlhovina Trifid",
    "M22" to "Velká kulová hvězdokupa ve Střelci",
    "M24" to "Hvězdný oblak ve Střelci",
    "M27" to "Mlhovina Činka",
    "M31" to "Galaxie v Andromedě",
    "M33" to "Galaxie v Trojúhelníku",
    "M42" to "Velká mlhovina v Orionu",
    "M43" to "De Mairanova mlhovina",
    "M44" to "Jesličky / Praesepe",
    "M45" to "Plejády / Kuřátka",
    "M51" to "Vírová galaxie",
    "M57" to "Prstencová mlhovina",
    "M64" to "Galaxie Černé oko",
    "M81" to "Bodeova galaxie",
    "M82" to "Doutníková galaxie",
    "M97" to "Soví mlhovina",
    "M101" to "Galaxie Větrník",
    "M104" to "Galaxie Sombrero",
)

/**
 * Strips diacritics, converts to lowercase, and trims whitespace.
 * Example: "Velká medvědice" -> "velka medvedice"
 */
fun String.normalizeForSearch(): String {
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
    return DIACRITICS_REGEX.replace(normalized, "").lowercase().trim()
}

enum class CelestialType {
    STAR,
    CONSTELLATION,
    ASTERISM,
    PLANET,
    DSO,
}

data class SearchResultItem(
    val indexId: Int, // Index in FlatBuffers catalog (starIndex for STAR, constellationIndex for CONSTELLATION/ASTERISM)
    val primaryName: String,
    val secondaryName: String?,
    val type: CelestialType,
    val ra: Float,
    val dec: Float,
    val magnitude: Float? = null,
    val planetId: String? = null,
    val searchableText: String = "$primaryName ${secondaryName ?: ""}".normalizeForSearch(),
)

object CelestialSearchIndex {

    /**
     * Builds an in-memory search index of all named stars, constellations, asterisms, and dynamic planets.
     * Pre-computes normalized diacritic-free [SearchResultItem.searchableText] for zero-allocation query filtering.
     * Zero-allocation access during iteration via reusable FlatBuffers structs/tables.
     */
    fun buildSearchIndex(
        catalog: NightSkyCatalog,
        planets: List<PlanetState> = emptyList(),
    ): List<SearchResultItem> {
        val result = mutableListOf<SearchResultItem>()
        val reusableConst = Constellation()
        val reusableMeta = StarMetadata()
        val reusableStar = Star()

        // 0. Index Dynamic Planets and Moon
        for ((idx, planet) in planets.withIndex()) {
            val primary = planet.nameCs
            val secondary = if (planet.nameCs != planet.nameEn) planet.nameEn else if (planet.isMoon) "Měsíc" else "Planeta"
            val combinedForSearch = "$primary $secondary".normalizeForSearch()
            result.add(
                SearchResultItem(
                    indexId = idx,
                    primaryName = primary,
                    secondaryName = secondary,
                    type = CelestialType.PLANET,
                    ra = planet.raDegrees.toFloat(),
                    dec = planet.decDegrees.toFloat(),
                    magnitude = planet.magnitude,
                    planetId = planet.id,
                    searchableText = combinedForSearch,
                )
            )
            if (planet.nameCs != planet.nameEn) {
                val secondaryEn = planet.nameCs
                val combinedEnForSearch = "${planet.nameEn} $secondaryEn".normalizeForSearch()
                result.add(
                    SearchResultItem(
                        indexId = idx,
                        primaryName = planet.nameEn,
                        secondaryName = secondaryEn,
                        type = CelestialType.PLANET,
                        ra = planet.raDegrees.toFloat(),
                        dec = planet.decDegrees.toFloat(),
                        magnitude = planet.magnitude,
                        planetId = planet.id,
                        searchableText = combinedEnForSearch,
                    )
                )
            }
        }

        // 1. Index Constellations and Asterisms
        val constCount = catalog.constellationsLength()
        for (i in 0 until constCount) {
            catalog.constellations(reusableConst, i)
            val name = reusableConst.name() ?: ""
            val localized = reusableConst.localizedName()
            val primaryName = localized ?: name
            val secondaryName = if (localized != null && localized != name) name else reusableConst.family()
            val type = if (reusableConst.isAsterism()) CelestialType.ASTERISM else CelestialType.CONSTELLATION
            val combinedForSearch = "$primaryName ${secondaryName ?: ""}".normalizeForSearch()

            result.add(
                SearchResultItem(
                    indexId = i,
                    primaryName = primaryName,
                    secondaryName = secondaryName,
                    type = type,
                    ra = reusableConst.centroidRa(),
                    dec = reusableConst.centroidDec(),
                    searchableText = combinedForSearch,
                )
            )
        }

        // 2. Index Named Stars from Metadata table
        val metaCount = catalog.metadataLength()
        val totalStars = catalog.starsLength()
        for (m in 0 until metaCount) {
            catalog.metadata(reusableMeta, m)
            val starIdx = reusableMeta.starIndex()
            if (starIdx in 0 until totalStars) {
                catalog.stars(reusableStar, starIdx)
                val starName = reusableMeta.name() ?: "HIP ${starIdx + 1}"
                val constellation = reusableMeta.constellation()
                val combinedForSearch = "$starName ${constellation ?: ""}".normalizeForSearch()

                result.add(
                    SearchResultItem(
                        indexId = starIdx,
                        primaryName = starName,
                        secondaryName = constellation,
                        type = CelestialType.STAR,
                        ra = reusableStar.ra(),
                        dec = reusableStar.dec(),
                        magnitude = reusableStar.mag(),
                        searchableText = combinedForSearch,
                    )
                )
            }
        }

        // 3. Index Deep Sky Objects (Messier Catalog)
        val dsoCount = catalog.dsosLength()
        val reusableDso = com.dazix.caelis.data.nightsky.DeepSkyObject()
        for (d in 0 until dsoCount) {
            catalog.dsos(reusableDso, d)
            val mNum = reusableDso.messierId()
            val messierId = "M$mNum"
            val name = reusableDso.englishName()
            val ngc = reusableDso.ngcId()
            val czechName = CZECH_DSO_NAMES[messierId] ?: reusableDso.localizedName()

            val primaryName = when {
                !czechName.isNullOrEmpty() -> "$messierId ($czechName)"
                !name.isNullOrEmpty() -> "$messierId ($name)"
                else -> messierId
            }
            val secondaryName = if (ngc != null && name != null) "$name • $ngc" else ngc ?: name

            val spacedMessier = messierId.replace("M", "M ")
            val unspacedNgc = ngc?.replace(" ", "") ?: ""
            val combinedForSearch = "$messierId $spacedMessier $primaryName ${secondaryName ?: ""} ${name ?: ""} ${czechName ?: ""} $unspacedNgc".normalizeForSearch()

            result.add(
                SearchResultItem(
                    indexId = d,
                    primaryName = primaryName,
                    secondaryName = secondaryName,
                    type = CelestialType.DSO,
                    ra = reusableDso.ra(),
                    dec = reusableDso.dec(),
                    magnitude = reusableDso.mag(),
                    searchableText = combinedForSearch,
                )
            )
        }

        return result
    }

    /**
     * Filters the in-memory search index by query substring with relevance sorting.
     * Normalized query is matched against pre-computed diacritic-free [SearchResultItem.searchableText].
     */
    fun filter(
        index: List<SearchResultItem>,
        query: String,
        limit: Int = 15,
    ): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val normalizedQuery = trimmed.normalizeForSearch()
        if (normalizedQuery.isEmpty()) return emptyList()

        val secondaryPrefixPattern = " $normalizedQuery"

        return index
            .filter { item ->
                item.searchableText.contains(normalizedQuery)
            }
            .sortedWith(
                compareBy<SearchResultItem> { item ->
                    // Priority 1: Primary name starts with query
                    if (item.searchableText.startsWith(normalizedQuery)) 0
                    // Priority 2: Secondary name starts with query (word boundary match)
                    else if (item.searchableText.contains(secondaryPrefixPattern)) 1
                    // Priority 3: Contains match
                    else 2
                }.thenBy { it.primaryName.length }
            )
            .take(limit)
    }

    /**
     * Computes the shortest angular distance between [current] and [target] angles on a cyclic domain [0, [maxAngle]).
     * For RA (0..360°), ensures smooth interpolation across the 0°/360° meridian without 360-degree flip glitches.
     *
     * Example: current = 350°, target = 10° -> returns +20°
     * Example: current = 10°, target = 350° -> returns -20°
     */
    fun getShortestAngularDistance(current: Float, target: Float, maxAngle: Float = 360f): Float {
        val halfAngle = maxAngle / 2f
        var diff = (target - current) % maxAngle
        if (diff > halfAngle) diff -= maxAngle
        else if (diff < -halfAngle) diff += maxAngle
        return diff
    }
}

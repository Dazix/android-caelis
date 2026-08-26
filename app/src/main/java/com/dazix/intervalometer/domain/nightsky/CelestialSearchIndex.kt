package com.dazix.intervalometer.domain.nightsky

import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.data.nightsky.StarMetadata
import java.text.Normalizer

private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()

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

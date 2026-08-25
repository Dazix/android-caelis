package com.dazix.intervalometer.domain.nightsky

import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.data.nightsky.StarMetadata

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
)

object CelestialSearchIndex {

    /**
     * Builds an in-memory search index of all named stars, constellations, asterisms, and dynamic planets.
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
                )
            )
            if (planet.nameCs != planet.nameEn) {
                result.add(
                    SearchResultItem(
                        indexId = idx,
                        primaryName = planet.nameEn,
                        secondaryName = planet.nameCs,
                        type = CelestialType.PLANET,
                        ra = planet.raDegrees.toFloat(),
                        dec = planet.decDegrees.toFloat(),
                        magnitude = planet.magnitude,
                        planetId = planet.id,
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

            result.add(
                SearchResultItem(
                    indexId = i,
                    primaryName = primaryName,
                    secondaryName = secondaryName,
                    type = type,
                    ra = reusableConst.centroidRa(),
                    dec = reusableConst.centroidDec(),
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

                result.add(
                    SearchResultItem(
                        indexId = starIdx,
                        primaryName = starName,
                        secondaryName = constellation,
                        type = CelestialType.STAR,
                        ra = reusableStar.ra(),
                        dec = reusableStar.dec(),
                        magnitude = reusableStar.mag(),
                    )
                )
            }
        }

        return result
    }

    /**
     * Filters the in-memory search index by query substring with relevance sorting.
     */
    fun filter(
        index: List<SearchResultItem>,
        query: String,
        limit: Int = 15,
    ): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lowerQuery = trimmed.lowercase()

        return index
            .filter { item ->
                item.primaryName.lowercase().contains(lowerQuery) ||
                    (item.secondaryName?.lowercase()?.contains(lowerQuery) == true)
            }
            .sortedWith(
                compareBy<SearchResultItem> { item ->
                    // Priority 1: Primary name starts with query
                    if (item.primaryName.lowercase().startsWith(lowerQuery)) 0
                    // Priority 2: Secondary name starts with query
                    else if (item.secondaryName?.lowercase()?.startsWith(lowerQuery) == true) 1
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

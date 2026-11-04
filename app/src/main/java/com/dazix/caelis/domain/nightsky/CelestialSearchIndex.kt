package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.data.nightsky.StarMetadata
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
    DSO,
    METEOR_SHOWER,
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
     *
     * Every object is shown under its [names] name (the user's language) and found by that name and
     * by the English one the catalog carries, so "Andromeda" finds M31 in any language. [names]
     * defaults to none, which leaves the English names.
     */
    fun buildSearchIndex(
        catalog: NightSkyCatalog,
        planets: List<PlanetState> = emptyList(),
        names: SkyNames = SkyNames.NONE,
    ): List<SearchResultItem> {
        val result = mutableListOf<SearchResultItem>()
        val reusableConst = Constellation()
        val reusableMeta = StarMetadata()
        val reusableStar = Star()

        // English constellation names by IAU code, for the stars and radiants that name one.
        val englishConstellations = HashMap<String, String>()
        for (i in 0 until catalog.constellationsLength()) {
            catalog.constellations(reusableConst, i)
            val id = reusableConst.id()
            val name = reusableConst.name()
            if (!reusableConst.isAsterism() && id != null && name != null) englishConstellations[id] = name
        }

        // 0. Index Dynamic Planets and Moon
        val planetTerm = names["term.planet"] ?: "Planet"
        for ((idx, planet) in planets.withIndex()) {
            val primary = names.planet(planet.id, planet.nameEn)
            val secondary = if (primary != planet.nameEn) planet.nameEn else null
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
                    searchableText = "$primary ${planet.nameEn} ${if (planet.isMoon) "" else planetTerm}".normalizeForSearch(),
                )
            )
        }

        // 1. Index Constellations and Asterisms
        val constCount = catalog.constellationsLength()
        for (i in 0 until constCount) {
            catalog.constellations(reusableConst, i)
            val name = reusableConst.name() ?: ""
            val isAsterism = reusableConst.isAsterism()
            val primaryName = names.constellation(reusableConst.id(), isAsterism, name) ?: name
            val secondaryName = if (primaryName != name) name else reusableConst.family()
            val type = if (isAsterism) CelestialType.ASTERISM else CelestialType.CONSTELLATION
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
                val code = reusableMeta.constellation()
                val constellation = names.constellationOfCode(code)
                val constellationEn = code?.let { englishConstellations[it] } ?: ""
                val combinedForSearch = "$starName ${constellation ?: ""} $constellationEn".normalizeForSearch()

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
            val mNum = reusableDso.messierId().toInt()
            val messierId = "M$mNum"
            val name = reusableDso.englishName()
            val ngc = reusableDso.ngcId()
            val localName = names.messier(mNum, name)

            val primaryName = if (!localName.isNullOrEmpty()) "$messierId ($localName)" else messierId
            val secondaryName = if (ngc != null && name != null) "$name • $ngc" else ngc ?: name

            val spacedMessier = messierId.replace("M", "M ")
            val unspacedNgc = ngc?.replace(" ", "") ?: ""
            val combinedForSearch = "$messierId $spacedMessier $primaryName ${secondaryName ?: ""} ${name ?: ""} $unspacedNgc".normalizeForSearch()

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

        // 4. Index Meteor Shower Radiants
        val meteorCount = catalog.meteorShowersLength()
        val reusableMeteor = com.dazix.caelis.data.nightsky.MeteorShower()
        val radiantTerm = names["term.radiant"] ?: "Radiant"
        val meteorTerm = names["term.meteor_shower"] ?: "Meteor shower"
        for (m in 0 until meteorCount) {
            catalog.meteorShowers(reusableMeteor, m)
            val enName = reusableMeteor.name() ?: "Meteor Shower"
            val name = names.meteorShower(reusableMeteor.id().toInt(), enName) ?: enName
            val conId = reusableMeteor.constellationId() ?: ""
            val conName = names.constellationOfCode(conId) ?: ""
            val conNameEn = englishConstellations[conId] ?: ""
            val secondaryName = if (conId.isNotEmpty()) "$radiantTerm • $conId" else radiantTerm
            val combinedForSearch = "$name $enName $secondaryName $conId $conName $conNameEn $meteorTerm radiant meteor shower".normalizeForSearch()

            result.add(
                SearchResultItem(
                    indexId = m,
                    primaryName = name,
                    secondaryName = secondaryName,
                    type = CelestialType.METEOR_SHOWER,
                    ra = reusableMeteor.ra(),
                    dec = reusableMeteor.dec(),
                    magnitude = null,
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

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

val IAU_CONSTELLATION_NAMES_EN: Map<String, String> = mapOf(
    "And" to "Andromeda",
    "Ant" to "Antlia",
    "Aps" to "Apus",
    "Aqr" to "Aquarius",
    "Aql" to "Aquila",
    "Ara" to "Ara",
    "Ari" to "Aries",
    "Aur" to "Auriga",
    "Boo" to "Boötes",
    "Cae" to "Caelum",
    "Cam" to "Camelopardalis",
    "Cnc" to "Cancer",
    "CVn" to "Canes Venatici",
    "CMa" to "Canis Major",
    "CMi" to "Canis Minor",
    "Cap" to "Capricornus",
    "Car" to "Carina",
    "Cas" to "Cassiopeia",
    "Cen" to "Centaurus",
    "Cep" to "Cepheus",
    "Cet" to "Cetus",
    "Cha" to "Chamaeleon",
    "Cir" to "Circinus",
    "Col" to "Columba",
    "Com" to "Coma Berenices",
    "CrA" to "Corona Australis",
    "CrB" to "Corona Borealis",
    "Crv" to "Corvus",
    "Crt" to "Crater",
    "Cru" to "Crux",
    "Cyg" to "Cygnus",
    "Del" to "Delphinus",
    "Dor" to "Dorado",
    "Dra" to "Draco",
    "Equ" to "Equuleus",
    "Eri" to "Eridanus",
    "For" to "Fornax",
    "Gem" to "Gemini",
    "Gru" to "Grus",
    "Her" to "Hercules",
    "Hor" to "Horologium",
    "Hya" to "Hydra",
    "Hyi" to "Hydrus",
    "Ind" to "Indus",
    "Lac" to "Lacerta",
    "Leo" to "Leo",
    "LMi" to "Leo Minor",
    "Lep" to "Lepus",
    "Lib" to "Libra",
    "Lup" to "Lupus",
    "Lyn" to "Lynx",
    "Lyr" to "Lyra",
    "Men" to "Mensa",
    "Mic" to "Microscopium",
    "Mon" to "Monoceros",
    "Mus" to "Musca",
    "Nor" to "Norma",
    "Oct" to "Octans",
    "Oph" to "Ophiuchus",
    "Ori" to "Orion",
    "Pav" to "Pavo",
    "Peg" to "Pegasus",
    "Per" to "Perseus",
    "Phe" to "Phoenix",
    "Pic" to "Pictor",
    "Psc" to "Pisces",
    "PsA" to "Piscis Austrinus",
    "Pup" to "Puppis",
    "Pyx" to "Pyxis",
    "Ret" to "Reticulum",
    "Sge" to "Sagitta",
    "Sgr" to "Sagittarius",
    "Sco" to "Scorpius",
    "Scl" to "Sculptor",
    "Sct" to "Scutum",
    "Ser" to "Serpens",
    "Sex" to "Sextans",
    "Tau" to "Taurus",
    "Tel" to "Telescopium",
    "Tri" to "Triangulum",
    "TrA" to "Triangulum Australe",
    "Tuc" to "Tucana",
    "UMa" to "Ursa Major",
    "UMi" to "Ursa Minor",
    "Vel" to "Vela",
    "Vir" to "Virgo",
    "Vol" to "Volans",
    "Vul" to "Vulpecula",
)

val IAU_CONSTELLATION_NAMES_CS: Map<String, String> = mapOf(
    "And" to "Andromeda",
    "Ant" to "Vývěva",
    "Aps" to "Rajka",
    "Aqr" to "Vodnář",
    "Aql" to "Orel",
    "Ara" to "Oltář",
    "Ari" to "Beran",
    "Aur" to "Vozka",
    "Boo" to "Pastýř",
    "Cae" to "Rycí rytidlo",
    "Cam" to "Žirafa",
    "Cnc" to "Rak",
    "CVn" to "Honicí psi",
    "CMa" to "Velký pes",
    "CMi" to "Malý pes",
    "Cap" to "Kozoroh",
    "Car" to "Lodní kýl",
    "Cas" to "Kasiopeja",
    "Cen" to "Kentaur",
    "Cep" to "Cefeus",
    "Cet" to "Velryba",
    "Cha" to "Chameleon",
    "Cir" to "Kružítko",
    "Col" to "Holubice",
    "Com" to "Vlasy Bereniky",
    "CrA" to "Jižní koruna",
    "CrB" to "Severní koruna",
    "Crv" to "Havran",
    "Crt" to "Pohár",
    "Cru" to "Jižní kříž",
    "Cyg" to "Labuť",
    "Del" to "Delfín",
    "Dor" to "Mečoun",
    "Dra" to "Drak",
    "Equ" to "Koníček",
    "Eri" to "Eridanus",
    "For" to "Pec",
    "Gem" to "Blíženci",
    "Gru" to "Jeřáb",
    "Her" to "Herkules",
    "Hor" to "Hodiny",
    "Hya" to "Hydra",
    "Hyi" to "Malý vodní had",
    "Ind" to "Indián",
    "Lac" to "Ještěrka",
    "Leo" to "Lev",
    "LMi" to "Malý lev",
    "Lep" to "Zajíc",
    "Lib" to "Váhy",
    "Lup" to "Vlk",
    "Lyn" to "Rys",
    "Lyr" to "Lyra",
    "Men" to "Tabulová hora",
    "Mic" to "Mikroskop",
    "Mon" to "Jednorožec",
    "Mus" to "Moucha",
    "Nor" to "Pravítko",
    "Oct" to "Oktant",
    "Oph" to "Hadonoš",
    "Ori" to "Orion",
    "Pav" to "Páv",
    "Peg" to "Pegas",
    "Per" to "Perseus",
    "Phe" to "Fénix",
    "Pic" to "Malíř",
    "Psc" to "Ryby",
    "PsA" to "Jižní ryba",
    "Pup" to "Kormidlo",
    "Pyx" to "Kompas",
    "Ret" to "Síť",
    "Sge" to "Šíp",
    "Sgr" to "Střelec",
    "Sco" to "Štír",
    "Scl" to "Sochař",
    "Sct" to "Štít",
    "Ser" to "Had",
    "Sex" to "Sextant",
    "Tau" to "Býk",
    "Tel" to "Dalekohled",
    "Tri" to "Trojúhelník",
    "TrA" to "Jižní trojúhelník",
    "Tuc" to "Tukan",
    "UMa" to "Velká medvědice",
    "UMi" to "Malý medvěd",
    "Vel" to "Plachty",
    "Vir" to "Panna",
    "Vol" to "Létající ryba",
    "Vul" to "Lištička",
)

val METEOR_SHOWER_NAMES_EN: Map<String, String> = mapOf(
    "Kvadrantidy" to "Quadrantids",
    "Lyridy" to "Lyrids",
    "Eta Akvaridy" to "Eta Aquariids",
    "Jižní Delta Akvaridy" to "Southern Delta Aquariids",
    "Alfa Kaprikornidy" to "Alpha Capricornids",
    "Perseidy" to "Perseids",
    "Aurigidy" to "Aurigids",
    "Zářijové Epsilon Perseidy" to "September Epsilon Perseids",
    "Drakonidy" to "Draconids",
    "Orionidy" to "Orionids",
    "Jižní Tauridy" to "Southern Taurids",
    "Severní Tauridy" to "Northern Taurids",
    "Leonidy" to "Leonids",
    "Geminidy" to "Geminids",
    "Ursidy" to "Ursids",
    "Puppidy-Velidy" to "Puppid-Velids",
    "Kentauridy" to "Centaurids",
    "Gama Normidy" to "Gamma Normids",
    "Pí Puppidy" to "Pi Puppids",
    "Kamelopardalidy" to "Camelopardalids",
    "Denní Arietidy" to "Daytime Arietids",
    "Červnové Bootidy" to "June Bootids",
    "Piscis Austrinidy" to "Piscis Austrinids",
    "Kappa Cygnidy" to "Kappa Cygnids",
    "Andromedidy" to "Andromedids",
    "Fénixidy" to "Phoenicids",
    "Monocerotidy" to "Monocerotids",
    "Sigma Hydridy" to "Sigma Hydrids",
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

        // 4. Index Meteor Shower Radiants
        val meteorCount = catalog.meteorShowersLength()
        val reusableMeteor = com.dazix.caelis.data.nightsky.MeteorShower()
        for (m in 0 until meteorCount) {
            catalog.meteorShowers(reusableMeteor, m)
            val name = reusableMeteor.localizedName() ?: "Meteor Shower"
            val conId = reusableMeteor.constellationId() ?: ""
            val enName = METEOR_SHOWER_NAMES_EN[name] ?: ""
            val conNameCs = IAU_CONSTELLATION_NAMES_CS[conId] ?: ""
            val conNameEn = IAU_CONSTELLATION_NAMES_EN[conId] ?: ""
            val secondaryName = if (conId.isNotEmpty()) "Radiant • $conId" else "Radiant"
            val combinedForSearch = "$name $enName $secondaryName $conId $conNameCs $conNameEn radiant meteoricky roj meteor shower".normalizeForSearch()

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

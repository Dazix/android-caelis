package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class CelestialSearchIndexTest {

    @Test
    fun `getShortestAngularDistance computes shortest path on sphere across 0 and 360 meridian`() {
        // Forward across 0 meridian: 350 -> 10 = +20
        assertEquals(20f, CelestialSearchIndex.getShortestAngularDistance(350f, 10f), 1e-4f)

        // Backward across 0 meridian: 10 -> 350 = -20
        assertEquals(-20f, CelestialSearchIndex.getShortestAngularDistance(10f, 350f), 1e-4f)

        // Standard positive angle: 100 -> 150 = +50
        assertEquals(50f, CelestialSearchIndex.getShortestAngularDistance(100f, 150f), 1e-4f)

        // Standard negative angle: 150 -> 100 = -50
        assertEquals(-50f, CelestialSearchIndex.getShortestAngularDistance(150f, 100f), 1e-4f)

        // Near meridian edge: 359 -> 1 = +2
        assertEquals(2f, CelestialSearchIndex.getShortestAngularDistance(359f, 1f), 1e-4f)

        // Near meridian edge: 1 -> 359 = -2
        assertEquals(-2f, CelestialSearchIndex.getShortestAngularDistance(1f, 359f), 1e-4f)

        // Same angle: 45 -> 45 = 0
        assertEquals(0f, CelestialSearchIndex.getShortestAngularDistance(45f, 45f), 1e-4f)
    }

    @Test
    fun `filter returns empty list for blank or empty query`() {
        val sampleItems = listOf(
            SearchResultItem(0, "Sirius", "Canis Major", CelestialType.STAR, 101.28f, -16.71f, -1.44f),
            SearchResultItem(1, "Vega", "Lyra", CelestialType.STAR, 279.23f, 38.78f, 0.03f),
        )

        assertTrue(CelestialSearchIndex.filter(sampleItems, "").isEmpty())
        assertTrue(CelestialSearchIndex.filter(sampleItems, "   ").isEmpty())
    }

    @Test
    fun `filter matches primary and secondary names with priority ranking`() {
        val items = listOf(
            SearchResultItem(0, "Sirius", "Canis Major", CelestialType.STAR, 101.28f, -16.71f, -1.44f),
            SearchResultItem(1, "Canis Major", "Velký pes", CelestialType.CONSTELLATION, 101.28f, -22.0f),
            SearchResultItem(2, "Canopus", "Carina", CelestialType.STAR, 95.99f, -52.70f, -0.62f),
            SearchResultItem(3, "Betelgeuse", "Orion", CelestialType.STAR, 88.79f, 7.41f, 0.50f),
        )

        // Search "can":
        // "Canis Major" and "Canopus" start with "can" (priority 0)
        // "Sirius" has secondary "Canis Major" starting with "can" (priority 1)
        val matches = CelestialSearchIndex.filter(items, "can")
        assertEquals(3, matches.size)
        assertEquals("Canopus", matches[0].primaryName) // length 7
        assertEquals("Canis Major", matches[1].primaryName) // length 11
        assertEquals("Sirius", matches[2].primaryName) // secondary match
    }

    @Test
    fun `filter limits results to requested limit`() {
        val items = (0..30).map { i ->
            SearchResultItem(i, "Star $i", "Constellation", CelestialType.STAR, 0f, 0f, 1f)
        }

        val results = CelestialSearchIndex.filter(items, "star", limit = 10)
        assertEquals(10, results.size)
    }

    @Test
    fun `buildSearchIndex correctly indexes catalog bin into stars, constellations and asterisms`() {
        val binFile = File("src/main/res/raw/catalog.bin")
        val targetFile = if (binFile.exists()) binFile else File("app/src/main/res/raw/catalog.bin")
        assertTrue("catalog.bin must exist on disk", targetFile.exists())

        val catalog = FileInputStream(targetFile).use { stream ->
            NightSkyCatalogLoader.loadFromStream(stream)
        }

        val searchIndex = CelestialSearchIndex.buildSearchIndex(catalog)
        assertTrue("Search index should have at least 500 items, got ${searchIndex.size}", searchIndex.size >= 500)

        // Check for Sirius
        val sirius = searchIndex.find { it.primaryName == "Sirius" }
        assertNotNull("Sirius must be in search index", sirius)
        assertEquals(CelestialType.STAR, sirius!!.type)
        assertEquals(0, sirius.indexId)
        assertEquals(101.28f, sirius.ra, 0.5f)
        assertEquals(-16.71f, sirius.dec, 0.5f)

        // Check for Ursa Major (Velká medvědice)
        val ursaMajor = searchIndex.find { it.primaryName == "Velká medvědice" || it.secondaryName == "Ursa Major" }
        assertNotNull("Ursa Major must be in search index", ursaMajor)
        assertEquals(CelestialType.CONSTELLATION, ursaMajor!!.type)

        // Check for Big Dipper (Velký vůz)
        val bigDipper = searchIndex.find { it.primaryName == "Velký vůz" || it.secondaryName == "Big Dipper (Plough)" }
        assertNotNull("Big Dipper must be in search index", bigDipper)
        assertEquals(CelestialType.ASTERISM, bigDipper!!.type)

        // Test filtering on live catalog index
        val vegaResults = CelestialSearchIndex.filter(searchIndex, "vega")
        assertTrue("Should find Vega", vegaResults.any { it.primaryName.contains("Vega", ignoreCase = true) })

        val orionResults = CelestialSearchIndex.filter(searchIndex, "orion")
        assertTrue("Should find Orion constellation and stars", orionResults.size >= 2)
    }

    @Test
    fun `buildSearchIndex includes dynamic planets and Moon with bilingual search entries`() {
        val binFile = File("src/main/res/raw/catalog.bin")
        val targetFile = if (binFile.exists()) binFile else File("app/src/main/res/raw/catalog.bin")
        val catalog = FileInputStream(targetFile).use { stream ->
            NightSkyCatalogLoader.loadFromStream(stream)
        }

        val testInstant = java.time.Instant.parse("2026-08-25T20:00:00Z")
        val planets = PlanetaryEphemerisCalculator.computeAll(testInstant)
        assertEquals(8, planets.size)

        val searchIndex = CelestialSearchIndex.buildSearchIndex(catalog, planets)

        // Verify Moon / Měsíc
        val moonResultsCs = CelestialSearchIndex.filter(searchIndex, "měsíc")
        assertTrue("Should find Moon with Czech query 'měsíc'", moonResultsCs.any { it.type == CelestialType.PLANET && it.planetId.equals("Moon", ignoreCase = true) })

        val moonResultsEn = CelestialSearchIndex.filter(searchIndex, "moon")
        assertTrue("Should find Moon with English query 'moon'", moonResultsEn.any { it.type == CelestialType.PLANET && it.planetId.equals("Moon", ignoreCase = true) })

        // Verify Jupiter / Jupiter
        val jupiterResults = CelestialSearchIndex.filter(searchIndex, "jupiter")
        assertTrue("Should find Jupiter", jupiterResults.any { it.type == CelestialType.PLANET && it.planetId.equals("Jupiter", ignoreCase = true) })

        // Verify Mars / Mars
        val marsResults = CelestialSearchIndex.filter(searchIndex, "mars")
        assertTrue("Should find Mars", marsResults.any { it.type == CelestialType.PLANET && it.planetId.equals("Mars", ignoreCase = true) })

        // Verify Saturn / Saturn
        val saturnResults = CelestialSearchIndex.filter(searchIndex, "saturn")
        assertTrue("Should find Saturn", saturnResults.any { it.type == CelestialType.PLANET && it.planetId.equals("Saturn", ignoreCase = true) })

        // Verify Mercury / Merkur
        val mercuryResultsCs = CelestialSearchIndex.filter(searchIndex, "merkur")
        assertTrue("Should find Mercury with Czech query 'merkur'", mercuryResultsCs.any { it.type == CelestialType.PLANET && it.planetId.equals("Mercury", ignoreCase = true) })

        val mercuryResultsEn = CelestialSearchIndex.filter(searchIndex, "mercury")
        assertTrue("Should find Mercury with English query 'mercury'", mercuryResultsEn.any { it.type == CelestialType.PLANET && it.planetId.equals("Mercury", ignoreCase = true) })
    }

    @Test
    fun `normalizeForSearch strips diacritics, converts to lowercase and trims whitespace`() {
        assertEquals("velka medvedice", "Velká medvědice".normalizeForSearch())
        assertEquals("mesic", "Měsíc".normalizeForSearch())
        assertEquals("strelec", "Střelec".normalizeForSearch())
        assertEquals("kun", "Kůň".normalizeForSearch())
        assertEquals("zirafa", "Žirafa".normalizeForSearch())
        assertEquals("byk", "Býk".normalizeForSearch())
        assertEquals("jest", "Jěst".normalizeForSearch())
        assertEquals("orion", "   ORION   ".normalizeForSearch())
        assertEquals("sirius", "SÍRIUS".normalizeForSearch())
    }

    @Test
    fun `filter performs diacritics-agnostic and case-insensitive matching with priority ranking`() {
        val items = listOf(
            SearchResultItem(0, "Velká medvědice", "Ursa Major", CelestialType.CONSTELLATION, 165.5f, 55.2f),
            SearchResultItem(1, "Velký vůz", "Big Dipper", CelestialType.ASTERISM, 165.5f, 55.2f),
            SearchResultItem(2, "Střelec", "Sagittarius", CelestialType.CONSTELLATION, 285.0f, -25.0f),
            SearchResultItem(3, "Kůň", "Equuleus", CelestialType.CONSTELLATION, 318.0f, 6.0f),
            SearchResultItem(4, "Sirius", "Velký pes", CelestialType.STAR, 101.28f, -16.71f, -1.44f),
        )

        // Query without diacritics matches diacritic-rich items
        val velkaResults = CelestialSearchIndex.filter(items, "velka")
        assertEquals(1, velkaResults.size)
        assertEquals("Velká medvědice", velkaResults[0].primaryName)

        val kunResults = CelestialSearchIndex.filter(items, "kun")
        assertEquals(1, kunResults.size)
        assertEquals("Kůň", kunResults[0].primaryName)

        val strelecResults = CelestialSearchIndex.filter(items, "strelec")
        assertEquals(1, strelecResults.size)
        assertEquals("Střelec", strelecResults[0].primaryName)

        // Uppercase query without diacritics
        val upperKunResults = CelestialSearchIndex.filter(items, "KUN")
        assertEquals(1, upperKunResults.size)
        assertEquals("Kůň", upperKunResults[0].primaryName)

        // Diacritic query on diacritic-free secondary or primary
        val diacriticQueryResults = CelestialSearchIndex.filter(items, "síríus")
        assertEquals(1, diacriticQueryResults.size)
        assertEquals("Sirius", diacriticQueryResults[0].primaryName)

        // Priority ranking: "velk" query matches "Velký vůz" (length 9) before "Velká medvědice" (length 15), then "Sirius" (secondary match)
        val velkResults = CelestialSearchIndex.filter(items, "velk")
        assertEquals(3, velkResults.size)
        assertEquals("Velký vůz", velkResults[0].primaryName)
        assertEquals("Velká medvědice", velkResults[1].primaryName)
        assertEquals("Sirius", velkResults[2].primaryName)
    }

    @Test
    fun `buildSearchIndex correctly indexes 110 Messier DSOs with multilingual matching`() {
        val binFile = File("src/main/res/raw/catalog.bin")
        val targetFile = if (binFile.exists()) binFile else File("app/src/main/res/raw/catalog.bin")
        assertTrue("catalog.bin must exist on disk", targetFile.exists())

        val catalog = FileInputStream(targetFile).use { stream ->
            NightSkyCatalogLoader.loadFromStream(stream)
        }

        val searchIndex = CelestialSearchIndex.buildSearchIndex(catalog)
        val dsoItems = searchIndex.filter { it.type == CelestialType.DSO }
        assertEquals("Must have 110 indexed DSOs", 110, dsoItems.size)

        // 1. Search by exact Messier ID "M31"
        val m31Matches = CelestialSearchIndex.filter(searchIndex, "M31")
        assertTrue("Must find M31", m31Matches.any { it.type == CelestialType.DSO && it.searchableText.contains("m31") })

        // 2. Search with space "M 31"
        val mSpace31Matches = CelestialSearchIndex.filter(searchIndex, "M 31")
        assertTrue("Must find M 31 with space", mSpace31Matches.any { it.type == CelestialType.DSO && it.searchableText.contains("m31") })

        // 3. Search by NGC "NGC 224"
        val ngcMatches = CelestialSearchIndex.filter(searchIndex, "NGC 224")
        assertTrue("Must find by NGC 224", ngcMatches.any { it.type == CelestialType.DSO && it.searchableText.contains("ngc 224") })

        // 4. Search by Czech name "Plejády" / "plejady"
        val pleiadesMatches = CelestialSearchIndex.filter(searchIndex, "plejady")
        assertTrue("Must find Pleiades by Czech name", pleiadesMatches.any { it.type == CelestialType.DSO && it.searchableText.contains("m45") })

        // 5. Search by Czech name "Krabí mlhovina" / "krabi"
        val crabMatches = CelestialSearchIndex.filter(searchIndex, "krabi")
        assertTrue("Must find Crab Nebula by Czech name", crabMatches.any { it.type == CelestialType.DSO && it.searchableText.contains("m1") })

        // 6. Search "Orion" matches constellation and M42
        val orionMatches = CelestialSearchIndex.filter(searchIndex, "Orion", limit = 50)
        assertTrue("Must contain Orion constellation", orionMatches.any { it.type == CelestialType.CONSTELLATION })
        assertTrue("Must contain Orion nebula DSO", orionMatches.any { it.type == CelestialType.DSO && it.searchableText.contains("m42") })

        val orionNebulaMatches = CelestialSearchIndex.filter(searchIndex, "Orion Nebula")
        assertTrue("Must find M42 by Orion Nebula", orionNebulaMatches.any { it.type == CelestialType.DSO && it.searchableText.contains("m42") })
    }

    @Test
    fun `buildSearchIndex correctly indexes 28 meteor showers with bilingual matching`() {
        val binFile = File("src/main/res/raw/catalog.bin")
        val targetFile = if (binFile.exists()) binFile else File("app/src/main/res/raw/catalog.bin")
        assertTrue("catalog.bin must exist on disk", targetFile.exists())

        val catalog = FileInputStream(targetFile).use { stream ->
            NightSkyCatalogLoader.loadFromStream(stream)
        }

        val searchIndex = CelestialSearchIndex.buildSearchIndex(catalog)
        val meteorItems = searchIndex.filter { it.type == CelestialType.METEOR_SHOWER }
        assertEquals("Must have 28 indexed Meteor Showers", 28, meteorItems.size)

        // 1. Search by Czech name "Perseidy"
        val perseidsMatches = CelestialSearchIndex.filter(searchIndex, "perseidy")
        assertTrue("Must find Perseids by Czech name", perseidsMatches.any { it.type == CelestialType.METEOR_SHOWER && it.primaryName.contains("Perseid") })

        // 2. Search by English name "Perseids"
        val perseidsEnMatches = CelestialSearchIndex.filter(searchIndex, "perseids")
        assertTrue("Must find Perseids by English name", perseidsEnMatches.any { it.type == CelestialType.METEOR_SHOWER && it.primaryName.contains("Perseid") })

        // 3. Search "Geminidy"
        val geminidsMatches = CelestialSearchIndex.filter(searchIndex, "geminidy")
        assertTrue("Must find Geminids by Czech name", geminidsMatches.any { it.type == CelestialType.METEOR_SHOWER && it.primaryName.contains("Geminid") })

        // 4. Search "Quadrantidy" (English variant matches Kvadrantidy)
        val quadrantidsMatches = CelestialSearchIndex.filter(searchIndex, "quadrantid")
        assertTrue("Must find Quadrantids", quadrantidsMatches.any { it.type == CelestialType.METEOR_SHOWER && it.primaryName.contains("Kvadrantid") })
    }
}

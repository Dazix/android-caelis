package com.dazix.intervalometer.domain.nightsky

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
}

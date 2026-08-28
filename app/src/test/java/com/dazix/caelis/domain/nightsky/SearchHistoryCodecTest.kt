package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryCodecTest {

    @Test
    fun `empty string decodes to empty list and empty list encodes to empty string`() {
        assertEquals(emptyList<SearchResultItem>(), SearchHistoryCodec.decode(""))
        assertEquals(emptyList<SearchResultItem>(), SearchHistoryCodec.decode("   \n\t  "))
        assertEquals("", SearchHistoryCodec.encode(emptyList()))
    }

    @Test
    fun `round trip encodes and decodes all celestial body types with special characters`() {
        val items = listOf(
            SearchResultItem(
                indexId = 42,
                primaryName = "Sirius\t(Alpha CMa)",
                secondaryName = "Canis\nMajor",
                type = CelestialType.STAR,
                ra = 101.287f,
                dec = -16.716f,
                magnitude = -1.46f,
                planetId = null,
            ),
            SearchResultItem(
                indexId = 3,
                primaryName = "Jupiter",
                secondaryName = "Planeta",
                type = CelestialType.PLANET,
                ra = 45.12f,
                dec = 15.34f,
                magnitude = -2.4f,
                planetId = "Jupiter",
            ),
            SearchResultItem(
                indexId = 12,
                primaryName = "Orion",
                secondaryName = "Lovec",
                type = CelestialType.CONSTELLATION,
                ra = 83.82f,
                dec = -5.38f,
                magnitude = null,
                planetId = null,
            ),
            SearchResultItem(
                indexId = 7,
                primaryName = "Velký vůz",
                secondaryName = "Asterismus",
                type = CelestialType.ASTERISM,
                ra = 165.5f,
                dec = 55.2f,
                magnitude = null,
                planetId = null,
            ),
        )

        val encoded = SearchHistoryCodec.encode(items)
        val decoded = SearchHistoryCodec.decode(encoded)

        assertEquals(4, decoded.size)

        // Verify Star
        assertEquals(42, decoded[0].indexId)
        assertEquals("Sirius\t(Alpha CMa)", decoded[0].primaryName)
        assertEquals("Canis\nMajor", decoded[0].secondaryName)
        assertEquals(CelestialType.STAR, decoded[0].type)
        assertEquals(101.287f, decoded[0].ra, 0.001f)
        assertEquals(-16.716f, decoded[0].dec, 0.001f)
        assertEquals(-1.46f, decoded[0].magnitude!!, 0.001f)
        assertNull(decoded[0].planetId)

        // Verify Planet
        assertEquals("Jupiter", decoded[1].primaryName)
        assertEquals(CelestialType.PLANET, decoded[1].type)
        assertEquals("Jupiter", decoded[1].planetId)

        // Verify Constellation
        assertEquals("Orion", decoded[2].primaryName)
        assertEquals(CelestialType.CONSTELLATION, decoded[2].type)
        assertNull(decoded[2].magnitude)

        // Verify Asterism
        assertEquals("Velký vůz", decoded[3].primaryName)
        assertEquals(CelestialType.ASTERISM, decoded[3].type)
    }

    @Test
    fun `addToHistory moves existing item to the top and caps at 20 items`() {
        var history = emptyList<SearchResultItem>()

        // Add 25 items
        for (i in 0 until 25) {
            val item = SearchResultItem(
                indexId = i,
                primaryName = "Star $i",
                secondaryName = null,
                type = CelestialType.STAR,
                ra = i.toFloat(),
                dec = (i * 2).toFloat(),
                magnitude = i * 0.1f,
                planetId = null,
            )
            history = SearchHistoryCodec.addToHistory(history, item, maxItems = 20)
        }

        // Must be capped at 20
        assertEquals(20, history.size)
        // Most recent item should be at index 0 (Star 24)
        assertEquals("Star 24", history[0].primaryName)
        // Oldest retained should be Star 5
        assertEquals("Star 5", history[19].primaryName)

        // Now re-add Star 10 (which is currently somewhere in the middle)
        val star10 = SearchResultItem(
            indexId = 10,
            primaryName = "Star 10",
            secondaryName = null,
            type = CelestialType.STAR,
            ra = 10f,
            dec = 20f,
            magnitude = 1.0f,
            planetId = null,
        )
        history = SearchHistoryCodec.addToHistory(history, star10, maxItems = 20)

        // Count must still be 20 (deduplicated)
        assertEquals(20, history.size)
        // Star 10 must now be at index 0
        assertEquals("Star 10", history[0].primaryName)
        assertEquals(10, history[0].indexId)
        // Exactly one occurrence of Star 10
        assertEquals(1, history.count { it.primaryName == "Star 10" })
    }

    @Test
    fun `removeFromHistory removes specific item`() {
        val item1 = SearchResultItem(1, "Vega", "Lyra", CelestialType.STAR, 279.2f, 38.7f, 0.03f)
        val item2 = SearchResultItem(2, "Mars", "Planeta", CelestialType.PLANET, 120.0f, 22.0f, -1.2f, "Mars")

        val history = listOf(item1, item2)
        val updated = SearchHistoryCodec.removeFromHistory(history, item1)

        assertEquals(1, updated.size)
        assertEquals("Mars", updated[0].primaryName)
    }

    @Test
    fun `decode ignores corrupted or truncated lines safely`() {
        val raw = "INVALID\nSTAR\tinvalid_id\nSTAR\t1\tName\tSec\t12.0\t34.0\t0.5\t\nCORRUPTED\tline"
        val decoded = SearchHistoryCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("Name", decoded[0].primaryName)
        assertEquals(1, decoded[0].indexId)
    }
}

package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRegionTest {

    @Test
    fun `tilesFor calculates covering box correctly for mid latitudes`() {
        // Center at Prague 50.08, 14.43 with 50 km radius (~0.45 deg lat, ~0.70 deg lon)
        val tiles = OfflineRegion.tilesFor(50.08, 14.43, 50)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.contains(DemTileKey(50, 14)))
    }

    @Test
    fun `tilesFor increases tile count for larger radii`() {
        val tiles50 = OfflineRegion.tilesFor(69.3, 17.5, 50)
        val tiles150 = OfflineRegion.tilesFor(69.3, 17.5, 150)
        val tiles200 = OfflineRegion.tilesFor(69.3, 17.5, 200)

        assertTrue(tiles150.size >= tiles50.size)
        assertTrue(tiles200.size >= tiles150.size)
        assertTrue(tiles50.contains(DemTileKey(69, 17)))
    }

    @Test
    fun `data model stores and exposes all fields faithfully`() {
        val key1 = DemTileKey(69, 17)
        val key2 = DemTileKey(69, 18)
        val region = OfflineRegion(
            id = "test-uuid-123",
            name = "Senja - Norsko",
            targetLat = 69.3,
            targetLon = 17.5,
            radiusKm = 100,
            requiredTiles = listOf(key1, key2),
            totalSizeBytes = 52_000_000L,
            createdAtMs = 123456789L,
        )

        assertEquals("test-uuid-123", region.id)
        assertEquals("Senja - Norsko", region.name)
        assertEquals(69.3, region.targetLat, 0.0001)
        assertEquals(17.5, region.targetLon, 0.0001)
        assertEquals(100, region.radiusKm)
        assertEquals(listOf(key1, key2), region.requiredTiles)
        assertEquals(52_000_000L, region.totalSizeBytes)
        assertEquals(123456789L, region.createdAtMs)
    }
}

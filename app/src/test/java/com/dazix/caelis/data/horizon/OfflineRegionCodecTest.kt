package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.OfflineRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRegionCodecTest {

    @Test
    fun `empty list encodes to empty string and decodes to empty list`() {
        val encoded = OfflineRegionCodec.encode(emptyList())
        assertEquals("", encoded)
        val decoded = OfflineRegionCodec.decode(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `roundtrip preserves multiple regions with special characters`() {
        val region1 = OfflineRegion(
            id = "id-1",
            name = "Senja\t&\nNorsko \\ Test",
            targetLat = 69.3456,
            targetLon = 17.5678,
            radiusKm = 100,
            requiredTiles = listOf(DemTileKey(69, 17), DemTileKey(69, 18)),
            totalSizeBytes = 51_926_402L,
            createdAtMs = 1700000000L,
        )
        val region2 = OfflineRegion(
            id = "id-2",
            name = "Krkonoše - Sněžka",
            targetLat = 50.736,
            targetLon = 15.740,
            radiusKm = 50,
            requiredTiles = listOf(DemTileKey(50, 15)),
            totalSizeBytes = 25_963_201L,
            createdAtMs = 1700000100L,
        )

        val list = listOf(region1, region2)
        val encoded = OfflineRegionCodec.encode(list)
        val decoded = OfflineRegionCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(region1.id, decoded[0].id)
        assertEquals(region1.name, decoded[0].name)
        assertEquals(region1.targetLat, decoded[0].targetLat, 0.0001)
        assertEquals(region1.targetLon, decoded[0].targetLon, 0.0001)
        assertEquals(region1.radiusKm, decoded[0].radiusKm)
        assertEquals(region1.requiredTiles, decoded[0].requiredTiles)
        assertEquals(region1.totalSizeBytes, decoded[0].totalSizeBytes)
        assertEquals(region1.createdAtMs, decoded[0].createdAtMs)

        assertEquals(region2.id, decoded[1].id)
        assertEquals(region2.name, decoded[1].name)
        assertEquals(region2.targetLat, decoded[1].targetLat, 0.0001)
        assertEquals(region2.targetLon, decoded[1].targetLon, 0.0001)
        assertEquals(region2.radiusKm, decoded[1].radiusKm)
        assertEquals(region2.requiredTiles, decoded[1].requiredTiles)
        assertEquals(region2.totalSizeBytes, decoded[1].totalSizeBytes)
        assertEquals(region2.createdAtMs, decoded[1].createdAtMs)
    }

    @Test
    fun `malformed lines are safely ignored during decode`() {
        val corrupted = "corrupted line\n\n\nanother bad\tline"
        val decoded = OfflineRegionCodec.decode(corrupted)
        assertTrue(decoded.isEmpty())
    }
}

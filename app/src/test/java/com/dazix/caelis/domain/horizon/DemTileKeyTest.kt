package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemTileKeyTest {

    @Test
    fun `names tiles the way the source does`() {
        assertEquals("N50E014", DemTileKey(50, 14).name)
        assertEquals("N50", DemTileKey(50, 14).directory)
        assertEquals("N50E014.hgt", DemTileKey(50, 14).fileName)
        assertEquals("skadi/N50/N50E014.hgt.gz", DemTileKey(50, 14).remotePath)

        assertEquals("S34W071", DemTileKey(-34, -71).name)
        assertEquals("S34", DemTileKey(-34, -71).directory)
        assertEquals("N00E010", DemTileKey(0, 10).name)
        assertEquals("N27E086", DemTileKey(27, 86).name)
    }

    @Test
    fun `locates the tile containing a point`() {
        assertEquals(DemTileKey(50, 14), DemTileKey.containing(50.0755, 14.4378))
        // Negative coordinates floor downwards: -0.5 belongs to the tile whose corner is -1.
        assertEquals(DemTileKey(-1, -1), DemTileKey.containing(-0.5, -0.5))
        assertEquals(DemTileKey(-34, -71), DemTileKey.containing(-33.45, -70.66))
    }

    @Test
    fun `parses a file name back into a key, with or without extensions`() {
        assertEquals(DemTileKey(50, 14), DemTileKey.parse("N50E014"))
        assertEquals(DemTileKey(50, 14), DemTileKey.parse("N50E014.hgt"))
        assertEquals(DemTileKey(50, 14), DemTileKey.parse("N50E014.hgt.gz"))
        assertEquals(DemTileKey(50, 14), DemTileKey.parse("/storage/emulated/0/Download/N50E014.hgt.gz"))
        assertEquals(DemTileKey(-34, -71), DemTileKey.parse("s34w071.hgt"))
    }

    @Test
    fun `refuses a file that is not a tile rather than guessing a location`() {
        assertNull(DemTileKey.parse("holiday.jpg"))
        assertNull(DemTileKey.parse("N5E014.hgt"))
        assertNull(DemTileKey.parse("X50E014.hgt"))
        assertNull(DemTileKey.parse(""))
    }

    @Test
    fun `covers a bounding box with every tile it touches`() {
        val keys = DemTileKey.coveringBox(
            minLatitude = 49.6,
            maxLatitude = 50.4,
            minLongitude = 13.8,
            maxLongitude = 14.2,
        )

        assertEquals(4, keys.size)
        assertTrue(keys.contains(DemTileKey(49, 13)))
        assertTrue(keys.contains(DemTileKey(49, 14)))
        assertTrue(keys.contains(DemTileKey(50, 13)))
        assertTrue(keys.contains(DemTileKey(50, 14)))
    }

    @Test
    fun `covers a single tile when the box sits inside one`() {
        val keys = DemTileKey.coveringBox(50.2, 50.8, 14.2, 14.8)

        assertEquals(listOf(DemTileKey(50, 14)), keys)
    }

    @Test
    fun `wraps across the antimeridian instead of walking backwards around the globe`() {
        // An observer at 179.9 E genuinely needs the tile on the far side of the date line.
        val keys = DemTileKey.coveringBox(
            minLatitude = 50.0,
            maxLatitude = 50.5,
            minLongitude = 179.5,
            maxLongitude = -179.5,
        )

        assertEquals(2, keys.size)
        assertTrue(keys.contains(DemTileKey(50, 179)))
        assertTrue(keys.contains(DemTileKey(50, -180)))
    }

    @Test
    fun `rejects a corner off the globe`() {
        assertTrue(runCatching { DemTileKey(90, 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DemTileKey(0, 180) }.exceptionOrNull() is IllegalArgumentException)
    }
}

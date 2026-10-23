package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.zip.GZIPOutputStream

class HgtTileTest {

    private val key = DemTileKey(50, 14)

    /**
     * An 11x11 tile whose every post is distinct: value = row * 10 + column, with row 0 the
     * northernmost. That makes an interpolation mistake show up as a wrong number rather than as a
     * plausible one.
     */
    private fun rampTile(vararg voids: Pair<Int, Int>): HgtTile {
        val n = 11
        val data = ShortArray(n * n) { index ->
            val row = index / n
            val col = index % n
            (row * 10 + col).toShort()
        }
        voids.forEach { (row, col) -> data[row * n + col] = HgtTile.VOID_SAMPLE.toShort() }
        return HgtTile(key, ShortBuffer.wrap(data))
    }

    @Test
    fun `derives grid side from sample count`() {
        assertEquals(11, rampTile().gridSize)
        assertEquals(0.1, rampTile().postSpacingDegrees, 1e-12)
    }

    @Test
    fun `reads the four corners with north at row zero`() {
        val tile = rampTile()

        // North-west corner: row 0, column 0
        assertEquals(0.0, tile.elevationAt(51.0, 14.0)!!, 1e-9)
        // North-east corner: row 0, column 10
        assertEquals(10.0, tile.elevationAt(51.0, 15.0)!!, 1e-9)
        // South-west corner: row 10, column 0
        assertEquals(100.0, tile.elevationAt(50.0, 14.0)!!, 1e-9)
        // South-east corner: row 10, column 10
        assertEquals(110.0, tile.elevationAt(50.0, 15.0)!!, 1e-9)
    }

    @Test
    fun `interpolates bilinearly between posts`() {
        val tile = rampTile()

        // Exactly on post (5, 5)
        assertEquals(55.0, tile.elevationAt(50.5, 14.5)!!, 1e-9)
        // Dead centre of the quad (0,0) (0,1) (1,0) (1,1) = mean of 0, 1, 10, 11
        assertEquals(5.5, tile.elevationAt(50.95, 14.05)!!, 1e-9)
        // Quarter of the way east across the first quad: 0.25 * 1 + 0.75 * 0 on the north edge
        assertEquals(0.25, tile.elevationAt(51.0, 14.025)!!, 1e-9)
    }

    @Test
    fun `drops void posts and renormalises the remaining weights`() {
        // Post (0, 1) is void, so the centre of that quad blends only 0, 10 and 11.
        val tile = rampTile(0 to 1)
        val expected = (0.0 * 0.25 + 10.0 * 0.25 + 11.0 * 0.25) / 0.75

        assertEquals(expected, tile.elevationAt(50.95, 14.05)!!, 1e-9)
    }

    @Test
    fun `returns null when every post of the quad is void`() {
        val tile = rampTile(0 to 0, 0 to 1, 1 to 0, 1 to 1)

        assertNull(tile.elevationAt(50.95, 14.05))
    }

    @Test
    fun `returns null outside the tile instead of clamping to an edge`() {
        val tile = rampTile()

        assertNull("north of the tile", tile.elevationAt(51.5, 14.5))
        assertNull("south of the tile", tile.elevationAt(49.5, 14.5))
        assertNull("west of the tile", tile.elevationAt(50.5, 13.5))
        assertNull("east of the tile", tile.elevationAt(50.5, 15.5))
    }

    @Test
    fun `parses raw big-endian bytes`() {
        val n = 11
        val buffer = ByteBuffer.allocate(n * n * 2).order(ByteOrder.BIG_ENDIAN)
        for (row in 0 until n) {
            for (col in 0 until n) {
                buffer.putShort((row * 10 + col).toShort())
            }
        }

        val tile = HgtTile.parse(key, buffer.array())

        assertEquals(11, tile.gridSize)
        assertEquals(55.0, tile.elevationAt(50.5, 14.5)!!, 1e-9)
    }

    @Test
    fun `parses a gzipped tile so a hand-imported hgt gz needs no unpacking`() {
        val n = 11
        val raw = ByteBuffer.allocate(n * n * 2).order(ByteOrder.BIG_ENDIAN)
        for (row in 0 until n) {
            for (col in 0 until n) {
                raw.putShort((row * 10 + col).toShort())
            }
        }
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(raw.array()) }
        }.toByteArray()

        assertTrue("fixture must actually be gzip", HgtTile.isGzip(gzipped))

        val tile = HgtTile.parse(key, gzipped)

        assertEquals(11, tile.gridSize)
        assertEquals(55.0, tile.elevationAt(50.5, 14.5)!!, 1e-9)
    }

    @Test
    fun `rejects a payload that is not a square grid`() {
        val notSquare = ByteArray(2 * 50)

        val error = runCatching { HgtTile.parse(key, notSquare) }.exceptionOrNull()

        assertNotNull("a 50-post payload must not be accepted as a tile", error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a grid denser than 1 arcsecond is not a plausible tile`() {
        assertTrue(HgtTile.isPlausibleGridLength(3601L * 3601L * 2L))
        assertTrue(HgtTile.isPlausibleGridLength(1201L * 1201L * 2L))
        assertFalse(
            "a perfect square past 3601 posts is a payload grown past any tile",
            HgtTile.isPlausibleGridLength(3603L * 3603L * 2L),
        )
    }

    @Test
    fun `composite grid routes a lookup to the tile that contains it`() {
        val west = HgtTile(DemTileKey(50, 14), ShortBuffer.wrap(ShortArray(11 * 11) { 100 }))
        val east = HgtTile(DemTileKey(50, 15), ShortBuffer.wrap(ShortArray(11 * 11) { 200 }))
        val composite = CompositeElevationGrid(listOf(west, east))

        assertEquals(100.0, composite.elevationAt(50.5, 14.5)!!, 1e-9)
        assertEquals(200.0, composite.elevationAt(50.5, 15.5)!!, 1e-9)
        assertNull("uncovered ground stays null", composite.elevationAt(50.5, 16.5))
    }
}

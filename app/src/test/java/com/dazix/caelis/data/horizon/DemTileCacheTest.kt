package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemTileCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val directory get() = File(folder.root, "dem")
    private val key = DemTileKey.containing(50.0, 15.0)

    /** The smallest grid [DemTileCache.store] accepts: 601 x 601 posts, 722 kB. */
    private val tile = ByteArray(601 * 601 * 2) { (it % 7).toByte() }

    private fun cache(maxBytes: Long = DemTileCache.DEFAULT_MAX_BYTES) =
        DemTileCache(directory, File(folder.root, "dem_offline"), maxBytes)

    @Test
    fun `concurrent stores of one tile do not truncate each others transfer`() = runBlocking {
        // A download and a hand import of the same square, as two caches over one folder.
        val results = listOf(cache(), cache()).map { c ->
            async(Dispatchers.Default) {
                runCatching {
                    c.store(key) { out ->
                        out.write(tile, 0, tile.size / 2)
                        delay(50)
                        out.write(tile, tile.size / 2, tile.size - tile.size / 2)
                    }
                }
            }
        }.awaitAll()

        results.forEach { assertTrue("a store failed: ${it.exceptionOrNull()}", it.isSuccess) }
        assertTrue(tile.contentEquals(cache().fileFor(key).readBytes()))
        assertEquals("no temporary file survives", listOf(key.fileName), directory.list()!!.toList())
    }

    @Test
    fun `a stale part file from a crash is swept by the next store`() = runBlocking {
        directory.mkdirs()
        val stale = File(directory, "N49E014-crash.part").apply {
            writeBytes(ByteArray(5))
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }

        cache().store(key) { it.write(tile) }

        assertFalse(stale.exists())
    }

    @Test
    fun `trim reclaims old tiles but not a transfer still being written`() {
        directory.mkdirs()
        val old = File(directory, "N49E014.hgt").apply {
            writeBytes(tile)
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }
        val inFlight = File(directory, "N50E015-123.part").apply { writeBytes(tile) }

        cache(maxBytes = 1L).trim()

        assertFalse("an old tile is what trim is for", old.exists())
        assertTrue("deleting a live transfer only fails that download", inFlight.exists())
    }
}

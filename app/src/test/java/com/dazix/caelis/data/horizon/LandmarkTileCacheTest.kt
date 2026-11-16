package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.LandmarkPeak
import com.dazix.caelis.domain.horizon.LandmarkTileCodec
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LandmarkTileCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val directory get() = File(folder.root, "landmarks")
    private val key = DemTileKey.containing(50.0, 15.0)
    private val peak = LandmarkPeak(50.7, 15.7, 1603, "Sněžka")

    private val tile = ByteArrayOutputStream().also { LandmarkTileCodec.write(it, listOf(peak)) }.toByteArray()

    @Test
    fun `concurrent stores of one tile do not delete each others transfer`() = runBlocking {
        val cache = LandmarkTileCache(directory)
        // Two caches over one folder, as the repository and the offline downloader hold.
        val other = LandmarkTileCache(directory)

        val results = listOf(cache, other).map { c ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                c.store(key) { out ->
                    out.write(tile, 0, 10)
                    kotlinx.coroutines.delay(50)
                    out.write(tile, 10, tile.size - 10)
                    true
                }
            }
        }.awaitAll()

        assertEquals(listOf(LandmarkTileCache.Stored.STORED, LandmarkTileCache.Stored.STORED), results)
        assertEquals(listOf(peak), cache.read(key))
        assertEquals("no temporary file survives", listOf("${key.name}.peaks"), directory.list()!!.toList())
    }

    @Test
    fun `a stale part file from a crash is swept by the next store`() = runBlocking {
        directory.mkdirs()
        val stale = File(directory, "N49E014-crash.part").apply {
            writeBytes(ByteArray(5))
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }

        LandmarkTileCache(directory).store(key) { it.write(tile); true }

        assertFalse(stale.exists())
    }

    @Test
    fun `a recent part file is a transfer in flight and is left alone`() = runBlocking {
        directory.mkdirs()
        val live = File(directory, "N49E014-live.part").apply { writeBytes(ByteArray(5)) }

        LandmarkTileCache(directory).store(key) { it.write(tile); true }

        assertTrue(live.exists())
    }

    @Test
    fun `clear drops tiles and stale parts but not a transfer in flight`() = runBlocking {
        val cache = LandmarkTileCache(directory)
        cache.store(key) { it.write(tile); true }
        val live = File(directory, "N49E014-live.part").apply { writeBytes(ByteArray(5)) }
        val stale = File(directory, "N49E013-old.part").apply {
            writeBytes(ByteArray(5))
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }

        cache.clear()

        assertEquals(null, cache.read(key))
        assertTrue(live.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `a failing body leaves no temporary file`() = runBlocking {
        val cache = LandmarkTileCache(directory)

        try {
            cache.store(key) { throw java.io.IOException("reset") }
        } catch (_: java.io.IOException) {
        }

        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }
}

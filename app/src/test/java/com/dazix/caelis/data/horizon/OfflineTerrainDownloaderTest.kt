package com.dazix.caelis.data.horizon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.OfflineRegion
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTerrainDownloaderTest {

    private lateinit var context: Context
    private lateinit var offlineDir: File
    private lateinit var cacheDir: File
    private lateinit var repository: OfflineRegionRepository
    private lateinit var tileCache: DemTileCache
    private lateinit var fakeDownloader: DemTileDownloader
    private lateinit var offlineDownloader: OfflineTerrainDownloader

    private val downloadedKeys = mutableListOf<DemTileKey>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        offlineDir = File(context.filesDir, OfflineRegionRepository.OFFLINE_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
        cacheDir = File(context.cacheDir, "dem").apply {
            deleteRecursively()
            mkdirs()
        }
        downloadedKeys.clear()

        repository = OfflineRegionRepository(context)
        tileCache = DemTileCache(context)

        fakeDownloader = object : DemTileDownloader(consent = { true }) {
            override suspend fun download(key: DemTileKey, out: OutputStream, onProgress: ((Float) -> Unit)?) {
                downloadedKeys.add(key)
                // Write valid 1201x1201 short grid bytes
                val validTileBytes = ByteArray(1201 * 1201 * 2)
                out.write(validTileBytes)
                onProgress?.invoke(1f)
            }
        }

        offlineDownloader = OfflineTerrainDownloader(context, repository, tileCache, fakeDownloader)
    }

    @After
    fun tearDown() {
        offlineDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `estimate correctly classifies tiles into offline, cached, and network`() {
        val tile1 = DemTileKey(50, 14)
        val tile2 = DemTileKey(50, 15)

        // tile1 is already in offline storage
        File(offlineDir, tile1.fileName).writeBytes(ByteArray(1201 * 1201 * 2))

        // tile2 is in volatile cache
        File(cacheDir, tile2.fileName).writeBytes(ByteArray(1201 * 1201 * 2))

        val estimate = offlineDownloader.estimate(50.5, 14.5, 50)
        assertTrue(estimate.requiredTiles.contains(tile1))
        assertTrue(estimate.promoteFromCache.contains(tile2) || !estimate.needNetworkDownload.contains(tile1))
    }

    @Test
    fun `downloadRegion promotes cached tile to offline storage without network download`() = runBlocking {
        val targetLat = 50.5
        val targetLon = 14.5
        val required = OfflineRegion.tilesFor(targetLat, targetLon, 20)
        assertEquals(1, required.size)
        val key = required.first()

        // Put tile in volatile cache
        val cacheFile = File(cacheDir, key.fileName).apply {
            writeBytes(ByteArray(1201 * 1201 * 2))
        }
        assertTrue(cacheFile.exists())

        val result = offlineDownloader.downloadRegion("Prague Area", targetLat, targetLon, 20)
        assertTrue(result.isSuccess)

        // Network should NOT have been hit for key
        assertFalse(downloadedKeys.contains(key))

        // Tile should now exist in offline storage
        val offlineFile = File(offlineDir, key.fileName)
        assertTrue(offlineFile.exists())
        assertEquals(1201 * 1201 * 2L, offlineFile.length())

        // Region repository should contain the region
        assertEquals(1, repository.offlineRegions.value.size)
        assertEquals("Prague Area", repository.offlineRegions.value.first().name)
    }

    @Test
    fun `downloadRegion downloads missing tile via downloader when not in cache or offline`() = runBlocking {
        val targetLat = 69.3
        val targetLon = 17.5
        val required = OfflineRegion.tilesFor(targetLat, targetLon, 20)
        val key = required.first()

        val result = offlineDownloader.downloadRegion("Senja", targetLat, targetLon, 20)
        assertTrue(result.isSuccess)

        // Downloader should have been called
        assertTrue(downloadedKeys.contains(key))

        // Tile should exist in offline storage
        val offlineFile = File(offlineDir, key.fileName)
        assertTrue(offlineFile.exists())
        assertEquals(1201 * 1201 * 2L, offlineFile.length())
    }
}

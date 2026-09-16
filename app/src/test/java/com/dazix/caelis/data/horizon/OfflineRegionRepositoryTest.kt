package com.dazix.caelis.data.horizon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.OfflineRegion
import java.io.File
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
class OfflineRegionRepositoryTest {

    private lateinit var context: Context
    private lateinit var offlineDir: File
    private lateinit var repository: OfflineRegionRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        offlineDir = File(context.filesDir, OfflineRegionRepository.OFFLINE_DIR_NAME)
        offlineDir.deleteRecursively()
        offlineDir.mkdirs()
        repository = OfflineRegionRepository(context)
    }

    @After
    fun tearDown() {
        offlineDir.deleteRecursively()
    }

    @Test
    fun `saving a region persists it and updates state flow`() = runBlocking {
        val tile1 = DemTileKey(69, 17)
        val tile2 = DemTileKey(69, 18)
        val region = OfflineRegion(
            id = "region-1",
            name = "Senja",
            targetLat = 69.3,
            targetLon = 17.5,
            radiusKm = 50,
            requiredTiles = listOf(tile1, tile2),
            totalSizeBytes = 52_000_000L,
        )

        repository.saveRegion(region)
        assertEquals(1, repository.offlineRegions.value.size)
        assertEquals(region, repository.offlineRegions.value.first())

        // Recreate repository to verify persistence from disk
        val reloaded = OfflineRegionRepository(context)
        assertEquals(1, reloaded.offlineRegions.value.size)
        assertEquals(region, reloaded.offlineRegions.value.first())
    }

    @Test
    fun `getOrphanedTiles identifies only unreferenced files`() = runBlocking {
        val tileA = DemTileKey(69, 17)
        val tileB = DemTileKey(69, 18)
        val tileC = DemTileKey(69, 19)

        // Create physical files on disk
        val fileA = File(offlineDir, tileA.fileName).apply { writeBytes(ByteArray(100)) }
        val fileB = File(offlineDir, tileB.fileName).apply { writeBytes(ByteArray(100)) }
        val fileC = File(offlineDir, tileC.fileName).apply { writeBytes(ByteArray(100)) }

        // Region 1 uses tileA and tileB
        val region1 = OfflineRegion(
            id = "r1",
            name = "Region 1",
            targetLat = 69.0,
            targetLon = 17.0,
            radiusKm = 50,
            requiredTiles = listOf(tileA, tileB),
            totalSizeBytes = 200L,
        )
        repository.saveRegion(region1)

        val orphans = repository.getOrphanedTiles()
        assertEquals(1, orphans.size)
        assertEquals(fileC.name, orphans.first().name)
    }

    @Test
    fun `deleteRegion prunes only unreferenced tiles and preserves shared tiles`() = runBlocking {
        val tileShared = DemTileKey(69, 17)
        val tileOnly1 = DemTileKey(69, 18)
        val tileOnly2 = DemTileKey(69, 19)

        val fileShared = File(offlineDir, tileShared.fileName).apply { writeBytes(ByteArray(100)) }
        val fileOnly1 = File(offlineDir, tileOnly1.fileName).apply { writeBytes(ByteArray(100)) }
        val fileOnly2 = File(offlineDir, tileOnly2.fileName).apply { writeBytes(ByteArray(100)) }

        val region1 = OfflineRegion(
            id = "r1",
            name = "Region 1",
            targetLat = 69.0,
            targetLon = 17.0,
            radiusKm = 50,
            requiredTiles = listOf(tileShared, tileOnly1),
            totalSizeBytes = 200L,
        )
        val region2 = OfflineRegion(
            id = "r2",
            name = "Region 2",
            targetLat = 69.0,
            targetLon = 19.0,
            radiusKm = 50,
            requiredTiles = listOf(tileShared, tileOnly2),
            totalSizeBytes = 200L,
        )

        repository.saveRegion(region1)
        repository.saveRegion(region2)
        assertEquals(2, repository.offlineRegions.value.size)

        // Delete Region 1
        val deletedNames = repository.deleteRegion("r1")

        assertEquals(1, repository.offlineRegions.value.size)
        assertEquals("r2", repository.offlineRegions.value.first().id)

        // tileOnly1 should be deleted, tileShared and tileOnly2 must remain intact!
        assertEquals(listOf(tileOnly1.fileName), deletedNames)
        assertFalse(fileOnly1.exists())
        assertTrue(fileShared.exists())
        assertTrue(fileOnly2.exists())

        // Now delete Region 2
        val deletedNames2 = repository.deleteRegion("r2")
        assertEquals(2, deletedNames2.size)
        assertTrue(deletedNames2.contains(tileShared.fileName))
        assertTrue(deletedNames2.contains(tileOnly2.fileName))

        assertFalse(fileShared.exists())
        assertFalse(fileOnly2.exists())
        assertEquals(0, repository.offlineRegions.value.size)
    }
}

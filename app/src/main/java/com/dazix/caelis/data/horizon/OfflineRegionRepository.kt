package com.dazix.caelis.data.horizon

import android.content.Context
import android.util.Log
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.OfflineRegion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Repository responsible for persisting and managing user-defined [OfflineRegion]s
 * and physical `.hgt` tile files stored in `filesDir/dem_offline/`.
 *
 * Implements reference counting:
 * - Overlapping regions share physical `.hgt` files (zero duplication).
 * - A physical `.hgt` file is pruned ONLY if it is no longer referenced by any remaining region.
 */
class OfflineRegionRepository(context: Context) {

    private val offlineDirectory = File(context.filesDir, OFFLINE_DIR_NAME)
    private val regionsFile = File(offlineDirectory, REGIONS_FILE_NAME)

    private val _offlineRegions = MutableStateFlow<List<OfflineRegion>>(emptyList())
    val offlineRegions: StateFlow<List<OfflineRegion>> = _offlineRegions.asStateFlow()

    init {
        loadRegions()
    }

    private fun loadRegions() {
        if (!regionsFile.isFile || regionsFile.length() == 0L) {
            _offlineRegions.value = emptyList()
            return
        }
        try {
            val content = regionsFile.readText()
            _offlineRegions.value = OfflineRegionCodec.decode(content)
        } catch (e: Exception) {
            Log.w(TAG, "failed to read offline regions file: ${e.logClass}", e.inDebug())
            _offlineRegions.value = emptyList()
        }
    }

    private suspend fun persistRegions(regions: List<OfflineRegion>) = withContext(Dispatchers.IO) {
        offlineDirectory.mkdirs()
        val tempFile = File(offlineDirectory, "$REGIONS_FILE_NAME.tmp")
        try {
            tempFile.writeText(OfflineRegionCodec.encode(regions))
            if (regionsFile.exists()) regionsFile.delete()
            if (!tempFile.renameTo(regionsFile)) {
                throw IOException("cannot rename $tempFile to $regionsFile")
            }
            _offlineRegions.value = regions
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist offline regions: ${e.logClass}", e.inDebug())
            tempFile.delete()
            throw e
        }
    }

    /**
     * Saves a new or updated [OfflineRegion] to the repository.
     */
    suspend fun saveRegion(region: OfflineRegion) {
        val current = _offlineRegions.value.filterNot { it.id == region.id }
        val updated = listOf(region) + current
        persistRegions(updated)
    }

    /**
     * Deletes a region by [regionId], persists the updated list, and prunes orphaned `.hgt` files.
     *
     * @return List of physical file names that were deleted.
     */
    suspend fun deleteRegion(regionId: String): List<String> = withContext(Dispatchers.IO) {
        val current = _offlineRegions.value
        val updated = current.filterNot { it.id == regionId }
        persistRegions(updated)

        // Prune physical files no longer needed by any saved region
        val orphans = getOrphanedTiles()
        val deletedNames = mutableListOf<String>()
        for (orphan in orphans) {
            val name = orphan.name
            if (orphan.delete()) {
                deletedNames.add(name)
                Log.i(TAG, "pruned orphaned offline tile: ${DemTileKey.parse(name)?.logName() ?: "(file)"}")
            }
        }
        deletedNames
    }

    /**
     * Scans `filesDir/dem_offline/` for `.hgt` files and returns those that are NOT referenced
     * by any existing [OfflineRegion] in the repository.
     */
    fun getOrphanedTiles(): List<File> {
        val files = offlineDirectory.listFiles()?.filter {
            it.isFile && it.extension.equals("hgt", ignoreCase = true)
        } ?: return emptyList()

        val referencedFileNames = _offlineRegions.value
            .flatMap { it.requiredTiles }
            .map { it.fileName }
            .toSet()

        return files.filterNot { it.name in referencedFileNames }
    }

    fun fileFor(key: DemTileKey): File = File(offlineDirectory, key.fileName)

    fun hasOfflineTile(key: DemTileKey): Boolean {
        val file = fileFor(key)
        return file.isFile && file.length() > 0
    }

    /** Total size in bytes of all permanent `.hgt` files in `filesDir/dem_offline/`. */
    fun getOfflineSizeBytes(): Long {
        return offlineDirectory.listFiles()?.filter {
            it.isFile && it.extension.equals("hgt", ignoreCase = true)
        }?.sumOf { it.length() } ?: 0L
    }

    /** Number of saved offline regions. */
    fun getRegionCount(): Int = _offlineRegions.value.size

    companion object {
        private const val TAG = "OfflineRegionRepository"
        const val OFFLINE_DIR_NAME = "dem_offline"
        private const val REGIONS_FILE_NAME = "regions.dat"
    }
}

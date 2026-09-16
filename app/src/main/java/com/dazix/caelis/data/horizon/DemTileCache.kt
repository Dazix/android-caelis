package com.dazix.caelis.data.horizon

import android.content.Context
import android.util.Log
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HgtTile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds decompressed elevation tiles on disk.
 *
 * Volatile tiles live in `cacheDir/dem/` and can be evicted under storage pressure.
 * Permanent offline pre-downloaded tiles live in `filesDir/dem_offline/` and are never evicted.
 *
 * Tiles are read through a memory mapping rather than onto the heap — 26 MB per tile, up to four
 * tiles for one observer, would be an obvious way to be killed for memory on a mid-range phone.
 */
class DemTileCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val directory = File(context.cacheDir, "dem")
    private val offlineDirectory = File(context.filesDir, OfflineRegionRepository.OFFLINE_DIR_NAME)

    fun fileFor(key: DemTileKey): File {
        val offlineFile = File(offlineDirectory, key.fileName)
        if (offlineFile.isFile && offlineFile.length() > 0) return offlineFile
        return File(directory, key.fileName)
    }

    /** Direct reference to volatile cache tile file. */
    fun cacheFileFor(key: DemTileKey): File = File(directory, key.fileName)

    fun contains(key: DemTileKey): Boolean = fileFor(key).let { it.isFile && it.length() > 0 }

    /** Tiles present out of [keys], and the ones still missing, in one pass. */
    fun partition(keys: Collection<DemTileKey>): Pair<List<DemTileKey>, List<DemTileKey>> =
        keys.partition { contains(it) }

    /**
     * Opens [key] for reading, or returns `null` when it is not cached/offline or cannot be mapped.
     *
     * Touches the file's timestamp first if it lives in volatile cache, so the eviction order reflects use.
     */
    fun open(key: DemTileKey): HgtTile? {
        val file = fileFor(key)
        if (!file.isFile || file.length() < 2) return null

        return try {
            file.setLastModified(System.currentTimeMillis())
            FileInputStream(file).use { stream ->
                val buffer = stream.channel
                    .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    .order(ByteOrder.BIG_ENDIAN)
                    .asShortBuffer()
                // The mapping outlives the channel, which is why the stream can be closed here.
                HgtTile(key, buffer)
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot map tile ${key.name}", e)
            null
        } catch (e: IllegalArgumentException) {
            // Not a square grid: a truncated download or somebody's unrelated file. Drop it so the
            // next attempt refetches rather than failing the same way forever.
            Log.w(TAG, "discarding unusable tile ${key.name}", e)
            file.delete()
            null
        }
    }

    /**
     * Writes a tile to volatile cache through [body], atomically: the destination name appears only
     * once the whole payload is on disk, so an interrupted download cannot be mistaken for a complete tile.
     */
    @Throws(IOException::class)
    suspend fun store(key: DemTileKey, body: suspend (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val destination = cacheFileFor(key)
        val temporary = File(directory, "${key.name}.part")

        try {
            temporary.outputStream().use { body(it) }
            val length = temporary.length()
            if (length < MIN_PLAUSIBLE_TILE_BYTES || !HgtTile.isPlausibleGridLength(length)) {
                throw IOException("tile ${key.name} came back as $length bytes, which is not a whole grid")
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                throw IOException("cannot move ${temporary.name} into place")
            }
        } finally {
            temporary.delete()
        }
    }

    /** Copies an already-decompressed tile in from a stream the caller owns. */
    @Throws(IOException::class)
    suspend fun storeFrom(key: DemTileKey, input: InputStream) = store(key) { input.copyTo(it, COPY_BUFFER) }

    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Total size in bytes of all cached DEM tiles and temporary files in `cacheDir/dem/`. */
    fun getDemCacheSizeBytes(): Long = totalBytes()

    fun cachedKeys(): List<DemTileKey> =
        directory.listFiles()?.mapNotNull { DemTileKey.parse(it.name) } ?: emptyList()

    /**
     * Drops the least recently used tiles from volatile `cacheDir/dem/` until the folder is back under [maxBytes].
     * Never touches `filesDir/dem_offline/`.
     */
    fun trim(protect: Set<DemTileKey> = emptySet()) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        val protectedNames = protect.mapTo(HashSet()) { it.fileName }
        files.asSequence()
            .filterNot { it.name in protectedNames }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (total <= maxBytes) return
                val size = file.length()
                if (file.delete()) total -= size
            }
    }

    /** Safely deletes all volatile cached `.hgt` tiles and `.part` temporary download files in `cacheDir/dem/`. */
    fun clear() {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    companion object {
        private const val TAG = "DemTileCache"

        /**
         * Twenty 1 arcsecond tiles.
         *
         * A 60 km radius spans up to six tiles at mid latitudes, and auto-range can extend further.
         * 512 MB leaves plenty of room for multiple locations and high-density terrain grids.
         */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024

        /** Anything smaller than a 3 arcsecond tile is a truncated transfer, not data. */
        const val MIN_PLAUSIBLE_TILE_BYTES = 1201L * 1201L * 2L / 4

        private const val COPY_BUFFER = 64 * 1024
    }
}

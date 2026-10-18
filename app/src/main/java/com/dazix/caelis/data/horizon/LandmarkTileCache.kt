package com.dazix.caelis.data.horizon

import android.content.Context
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.LandmarkPeak
import com.dazix.caelis.domain.horizon.LandmarkTileCodec
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds downloaded `.peaks` tiles in `cacheDir/landmarks/`.
 *
 * Cache, in the same sense as the elevation tiles: Android may reclaim it at any time, and nothing
 * drawn depends on it, because the peaks that matter are stored with the horizon profile. A tile that
 * is gone costs one small download the next time a profile needs filling in.
 *
 * @param directory taken as a parameter so a JVM test can use a temporary folder.
 */
class LandmarkTileCache(private val directory: File) {

    constructor(context: Context) : this(File(context.cacheDir, "landmarks"))

    private fun fileFor(key: DemTileKey) = File(directory, "${key.name}.peaks")

    /**
     * The payload arrived but is not a whole tile. A fault of that one file, not of the connection, so
     * callers skip the tile instead of concluding the network is down.
     */
    class CorruptTileException(key: DemTileKey) : IOException("peak tile ${key.name} is not a whole tile")

    /** What [store] found out about a tile. */
    enum class Stored { STORED, NOT_COVERED }

    /**
     * The peaks of [key], or `null` when the tile is not cached or its file is not a whole tile.
     *
     * An empty list is a real answer — a covered tile with no peaks — and is not `null`. A file that
     * was read and does not decode is deleted (a read that fails is not) so the next attempt refetches instead of failing the same way forever.
     */
    fun read(key: DemTileKey): List<LandmarkPeak>? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val peaks = try {
            file.inputStream().use { LandmarkTileCodec.read(it) }
        } catch (_: IOException) {
            // A transient read error says nothing about the file; deleting would destroy a good tile.
            return null
        }
        if (peaks == null) file.delete()
        return peaks
    }

    /**
     * Writes a tile through [body], atomically: the destination name appears only once the payload
     * has been decoded, so a truncated transfer is never mistaken for a tile with fewer peaks.
     *
     * @param body writes the payload and returns false when the server has no such tile.
     * @throws IOException when the payload is not a well-formed tile or cannot be written.
     */
    @Throws(IOException::class)
    suspend fun store(key: DemTileKey, body: suspend (OutputStream) -> Boolean): Stored = withContext(Dispatchers.IO) {
        directory.mkdirs()
        sweepStaleParts()
        val destination = fileFor(key)
        // A name of its own per call: the repository and the offline downloader each hold a cache over
        // this folder, so two stores of one tile can overlap, and a shared `<tile>.part` let one delete
        // the other's half-written file from under it.
        val temporary = File.createTempFile("${key.name}-", PART_SUFFIX, directory)

        try {
            val found = temporary.outputStream().use { body(it) }
            if (!found) return@withContext Stored.NOT_COVERED

            val decodes = temporary.inputStream().use { LandmarkTileCodec.read(it) } != null
            if (!decodes) throw CorruptTileException(key)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Stored.STORED
        } finally {
            temporary.delete()
        }
    }

    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Drops every cached tile. A recent `.part` file is somebody's transfer in flight and is left
     * alone; deleting it only turned a good download into an [IOException]. Old ones are crash leftovers.
     */
    fun clear() {
        directory.listFiles()?.forEach { if (it.isFile && !it.isLivePart()) it.delete() }
    }

    /** A crash or a killed process leaves its `.part` file behind; nothing else would ever remove it. */
    private fun sweepStaleParts() {
        directory.listFiles()?.forEach { if (it.isFile && it.name.endsWith(PART_SUFFIX) && !it.isLivePart()) it.delete() }
    }

    private fun File.isLivePart() =
        name.endsWith(PART_SUFFIX) && System.currentTimeMillis() - lastModified() < STALE_PART_MS

    internal companion object {
        const val PART_SUFFIX = ".part"

        /** Far longer than a few-kilobyte transfer takes under the downloader's timeouts. */
        const val STALE_PART_MS = 10 * 60 * 1000L
    }
}

package com.dazix.caelis.data.horizon

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HgtTile
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Takes an elevation tile the user picked in the system file chooser and puts it in the cache.
 *
 * This path needs no permission of any kind and exists in every build, including one made from
 * source with `INTERNET` deleted from the manifest. It is the reason the terrain horizon is not a
 * network feature: the download is a convenience on top of it, not the only way in.
 */
class DemTileImporter(private val context: Context) {

    sealed interface Result {
        data class Imported(val key: DemTileKey, val bytes: Long) : Result

        /** The file is not a tile, or is not named like one. */
        data class NotATile(val displayName: String?) : Result

        data class Failed(val cause: Throwable) : Result
    }

    /**
     * Copies the tile at [uri] into the cache under the key its file name declares.
     *
     * The key comes from the name rather than from the content, because an `.hgt` file has no header
     * at all — it is a bare grid of numbers, and nothing inside it says which square of the Earth it
     * covers. A file renamed to the wrong tile would be placed in the wrong part of the world with
     * no way to notice, so the name is treated as the authority it unavoidably is, and anything that
     * does not parse as a tile name is refused rather than guessed at.
     */
    suspend fun import(uri: Uri, cache: DemTileCache): Result {
        val displayName = queryDisplayName(uri)
        val key = DemTileKey.parse(displayName ?: uri.lastPathSegment.orEmpty())
            ?: return Result.NotATile(displayName)

        return try {
            cache.store(key) { out ->
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("cannot open $uri")
                stream.use { input ->
                    val buffered = input.buffered()
                    // The server serves .hgt.gz and that is what a hand-download ends up as; asking
                    // the user to unpack it first is a step that gets skipped and then fails with an
                    // unreadable error about the grid not being square.
                    buffered.mark(2)
                    val magic = ByteArray(2)
                    val read = buffered.read(magic)
                    buffered.reset()

                    if (read == 2 && HgtTile.isGzip(magic)) {
                        GZIPInputStream(buffered, COPY_BUFFER).use { it.copyTo(out, COPY_BUFFER) }
                    } else {
                        buffered.copyTo(out, COPY_BUFFER)
                    }
                }
            }
            Result.Imported(key, cache.fileFor(key).length())
        } catch (e: IOException) {
            Result.Failed(e)
        } catch (e: SecurityException) {
            Result.Failed(e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (e: SecurityException) {
        null
    }

    companion object {
        private const val COPY_BUFFER = 64 * 1024

        /** MIME types to offer in the chooser. `.hgt` has none registered, so this stays broad. */
        val CHOOSER_MIME_TYPES: Array<String> = arrayOf("*/*")
    }
}

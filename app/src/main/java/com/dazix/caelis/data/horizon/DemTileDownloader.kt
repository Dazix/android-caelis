package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HgtTile
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Fetches one elevation tile, or one tile of named peaks, over HTTPS.
 *
 * ## This is the only file in `app/src/main` that is allowed to touch the network
 *
 * The app used to declare no `INTERNET` permission at all, and that was an argument worth something:
 * an app holding the accessibility permission could be shown, by the operating system rather than by
 * its own source, to send nothing anywhere. `INTERNET` has protection level `normal`, so once it is
 * in the manifest the system will not withhold it and the user cannot revoke it.
 *
 * What replaces that guarantee is deliberately narrow and checkable:
 *  - every socket in the app is opened here, and `NetworkIsolationTest` fails the build if a second
 *    place ever appears;
 *  - nothing is sent but a tile number in a URL path — no identifiers, no coordinates finer than the
 *    1° square, no request body at all;
 *  - [consent] is consulted on every single call, not once at construction, so revoking it in
 *    settings takes effect immediately rather than at the next process start;
 *  - a build made from source with the `INTERNET` line deleted from the manifest still works — the
 *    UI hides the download entirely and the hand-import path carries the feature.
 *
 * @param consent answers whether the user has agreed to network use. Checked per call.
 */
open class DemTileDownloader(
    private val consent: suspend () -> Boolean,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val landmarkBaseUrl: String = DEFAULT_LANDMARK_BASE_URL,
) {

    /** Thrown instead of connecting when the user has not agreed, or has said no. */
    class NotPermittedException : IOException("network download not permitted by the user")

    /** Thrown when the server answered, but not with a tile. */
    class ServerException(val status: Int) : IOException("tile server returned HTTP $status")

    /**
     * Downloads [key] and writes the **decompressed** `.hgt` bytes to [out].
     *
     * Decompression happens in the stream rather than on the heap: a 1 arcsecond tile is 26 MB
     * expanded, which is more than an app should hold in memory to read 1440 numbers out of it.
     *
     * @param onProgress compressed bytes received so far and the `Content-Length` they count towards,
     *   [UNKNOWN_LENGTH] when the server sends none. These are the bytes that cross the network, which
     *   is what a user asking "how much is this" wants, not the 26 MB the tile expands to.
     */
    open suspend fun download(
        key: DemTileKey,
        out: OutputStream,
        onProgress: ((receivedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        if (!consent()) throw NotPermittedException()

        val connection = connect("$baseUrl/${key.remotePath}")

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw ServerException(status)

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: UNKNOWN_LENGTH
            var received = 0L

            connection.inputStream.use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            received += n
                            onProgress?.invoke(received, totalBytes)
                        }
                        return n
                    }
                }
                GZIPInputStream(counting, GZIP_BUFFER).use {
                    it.copyTo(BoundedOutputStream(out, HgtTile.MAX_GRID_BYTES), COPY_BUFFER)
                }
            }
            out.flush()
            // Done is done even when the length was never announced.
            onProgress?.invoke(received, received)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The compressed size of [key], from a `HEAD` request, without fetching the tile.
     *
     * This is how a run knows its total before it starts, so the user sees "4 tiles, 62 MB" and can
     * decide, instead of a size that only appears tile by tile while it is already being spent. It is
     * network contact all the same: [consent] is asked on this call exactly as for [download], and the
     * request carries the same URL and nothing more.
     *
     * @return the announced length, or [UNKNOWN_LENGTH] when the server gives none.
     * @throws ServerException when the server answers but not with the tile, as [download] would.
     */
    open suspend fun contentLength(key: DemTileKey): Long {
        if (!consent()) throw NotPermittedException()

        val connection = connect("$baseUrl/${key.remotePath}", method = "HEAD")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw ServerException(status)
            return connection.contentLengthLong.takeIf { it > 0 } ?: UNKNOWN_LENGTH
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the `.peaks` file of [key] — the named summits in that 1° square — into [out], as is.
     *
     * Same rules as [download]: [consent] is asked on this call, and the URL carries the tile number
     * and nothing else. The data lives in a public repository, pinned to a tag, so the format a build
     * can read never changes under it.
     *
     * @return false when the server has no such file. The pipeline writes a file for every tile of a
     *   region it covers, so a 404 means "this part of the world is not covered", which is a normal
     *   answer and not a failure to retry.
     * @throws ServerException for any other status, [NotPermittedException] without consent.
     */
    open suspend fun downloadLandmarks(key: DemTileKey, out: OutputStream): Boolean {
        if (!consent()) throw NotPermittedException()

        val connection = connect("$landmarkBaseUrl/${key.directory}/${key.name}.peaks")
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return false
            if (status != HttpURLConnection.HTTP_OK) throw ServerException(status)

            connection.inputStream.use { raw ->
                var total = 0L
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = raw.read(buffer)
                    if (n < 0) break
                    total += n
                    // A tile is a few kilobytes; anything near this is not one, and must not fill the cache.
                    if (total > MAX_LANDMARK_BYTES) throw IOException("peak tile larger than $MAX_LANDMARK_BYTES bytes")
                    out.write(buffer, 0, n)
                }
            }
            out.flush()
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String, method: String = "GET"): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        // No cookies, no custom identifying headers: the request carries the tile name and nothing else.
        setRequestProperty("Accept-Encoding", "identity")
    }

    companion object {
        /**
         * Named peaks per 1° tile, from OpenStreetMap, in the public `Dazix/caelis-landmarks` repository.
         *
         * Pinned to the `v2` tag rather than a branch: a change of file layout is a new tag and a new
         * version in the code, so an installed app never downloads something it cannot read. Not the
         * private app repository, which cannot be fetched anonymously. Attribution (© OpenStreetMap
         * contributors, ODbL) is shown in Settings.
         */
        const val DEFAULT_LANDMARK_BASE_URL = "https://raw.githubusercontent.com/Dazix/caelis-landmarks/v2/tiles"

        private const val MAX_LANDMARK_BYTES = 4L * 1024 * 1024

        /** The total handed to `onProgress` when the server sent no `Content-Length`. */
        const val UNKNOWN_LENGTH = -1L

        /** 0..1 of a download, or 0 while its length is unknown. */
        fun fraction(receivedBytes: Long, totalBytes: Long): Float =
            if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        /**
         * Tilezen terrain tiles in the AWS Open Data programme. No account, no API key, no SLA.
         *
         * The Skadi format is chosen over the terrarium PNG tiles because it is a plain latitude and
         * longitude grid: the ray march works directly in geographic coordinates with no Web Mercator
         * conversion, and one 1°x1° file replaces several hundred small tiles.
         *
         * Attribution for the underlying sources (USGS, NOAA, Kartverket, Copernicus, Geoscience
         * Australia, LINZ, INEGI and others) is required and is shown in Settings.
         */
        const val DEFAULT_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val GZIP_BUFFER = 64 * 1024
        private const val COPY_BUFFER = 64 * 1024
    }
}

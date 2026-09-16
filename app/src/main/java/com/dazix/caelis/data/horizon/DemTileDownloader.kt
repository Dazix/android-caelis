package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Fetches one elevation tile over HTTPS.
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
     * @param onProgress 0..1, driven by compressed bytes received against `Content-Length`. Absent
     *   when the server does not send a length, in which case it is never called.
     */
    open suspend fun download(
        key: DemTileKey,
        out: OutputStream,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        if (!consent()) throw NotPermittedException()

        val connection = (URL("$baseUrl/${key.remotePath}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // No cookies, no custom identifying headers: the request carries the tile name and nothing else.
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw ServerException(status)

            val totalBytes = connection.contentLengthLong
            var received = 0L

            connection.inputStream.use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            received += n
                            if (onProgress != null && totalBytes > 0) {
                                onProgress((received.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                        return n
                    }
                }
                GZIPInputStream(counting, GZIP_BUFFER).use { it.copyTo(out, COPY_BUFFER) }
            }
            out.flush()
            onProgress?.invoke(1f)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
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

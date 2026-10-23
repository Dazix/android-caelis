package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HgtTile
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real [DemTileDownloader.download] against a server on the loopback interface, which is what a
 * compromised or redirected tile host would look like from the phone.
 */
class DemTileDownloaderTest {

    private val key = DemTileKey.containing(50.0, 15.0)
    private lateinit var server: HttpServer
    private var payload = ByteArray(0)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
    }

    @After
    fun stop() = server.stop(0)

    private fun downloader() = DemTileDownloader(
        consent = { true },
        baseUrl = "http://127.0.0.1:${server.address.port}",
    )

    @Test
    fun `a gzip that inflates past the largest tile stops at that size, not at a full disk`() = runBlocking {
        // Zeros compress about a thousandfold: this is a few tens of kilobytes on the wire.
        payload = gzipZeros(HgtTile.MAX_GRID_BYTES + 1)
        assertTrue("the bomb has to be small to mean anything", payload.size < 100 * 1024)
        val sink = CountingSink()

        val error = runCatching { downloader().download(key, sink) }.exceptionOrNull()

        assertTrue("got $error", error is BoundedOutputStream.LimitExceededException)
        assertTrue("wrote ${sink.count} bytes", sink.count <= HgtTile.MAX_GRID_BYTES)
    }

    @Test
    fun `a 1 arcsecond tile, the largest there is, still goes through whole`() = runBlocking {
        payload = gzipZeros(HgtTile.MAX_GRID_BYTES)
        val sink = CountingSink()

        downloader().download(key, sink)

        assertEquals(HgtTile.MAX_GRID_BYTES, sink.count)
    }

    private fun gzipZeros(count: Long): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { gzip ->
            val chunk = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                gzip.write(chunk, 0, n)
                left -= n
            }
        }
        return compressed.toByteArray()
    }

    /** Counts instead of keeping 26 MB on the heap. */
    private class CountingSink : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }
}

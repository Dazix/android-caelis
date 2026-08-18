package com.dazix.intervalometer.domain.nightsky

import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the FlatBuffers compiled [NightSkyCatalog] from raw binary stream.
 * Direct memory indexing without object creation.
 */
object NightSkyCatalogLoader {

    /**
     * Loads the binary catalog from an input stream into a direct or array-backed [ByteBuffer]
     * and wraps it with [NightSkyCatalog] root table in O(1) time.
     */
    fun loadFromStream(stream: InputStream): NightSkyCatalog {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(16384)
        var nRead: Int
        while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        val bytes = buffer.toByteArray()
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return NightSkyCatalog.getRootAsNightSkyCatalog(byteBuffer)
    }

    /**
     * Loads the binary catalog directly from a [ByteBuffer].
     */
    fun loadFromByteBuffer(byteBuffer: ByteBuffer): NightSkyCatalog {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        return NightSkyCatalog.getRootAsNightSkyCatalog(byteBuffer)
    }
}

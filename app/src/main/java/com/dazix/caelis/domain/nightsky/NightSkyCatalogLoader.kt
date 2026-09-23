package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.data.nightsky.NightSkyCatalog
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
        val bytes = stream.readBytes()
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

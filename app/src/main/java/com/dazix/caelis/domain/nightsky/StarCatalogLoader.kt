package com.dazix.caelis.domain.nightsky

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast binary loader for the raw packed star catalog (`stars_catalog.bin`).
 * Zero data class object allocations.
 */
object StarCatalogLoader {

    /**
     * Loads star catalog from an [InputStream] containing 32-bit Little-Endian floats.
     */
    fun loadFromStream(inputStream: InputStream): StarCatalog {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            baos.write(buffer, 0, read)
        }
        val bytes = baos.toByteArray()
        val floatCount = bytes.size / 4
        val floatArray = FloatArray(floatCount)

        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asFloatBuffer().get(floatArray)

        val starCount = floatCount / 3
        return StarCatalog(floatArray, starCount)
    }
}

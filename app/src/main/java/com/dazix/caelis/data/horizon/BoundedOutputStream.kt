package com.dazix.caelis.data.horizon

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Passes writes through to [out] until [limit] bytes, and throws instead of writing past it.
 *
 * The tile paths decompress gzip straight to disk, and a gzip stream inflates without bound: the
 * length check after the write came too late to stop a small file from filling the storage. This
 * stops the write itself. Closing it does not close [out], which belongs to the caller.
 */
internal class BoundedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {

    /** Thrown when a write would take the stream past [limit]; nothing of that write reaches [out]. */
    class LimitExceededException(limit: Long) : IOException("more than $limit bytes, which is not a tile")

    private var written = 0L

    override fun write(b: Int) {
        reserve(1)
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        reserve(len)
        out.write(b, off, len)
    }

    override fun close() = flush()

    private fun reserve(count: Int) {
        if (written + count > limit) throw LimitExceededException(limit)
        written += count
    }
}

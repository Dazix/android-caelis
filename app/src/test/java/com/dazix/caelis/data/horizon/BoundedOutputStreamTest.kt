package com.dazix.caelis.data.horizon

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputStreamTest {

    @Test
    fun `writes up to the limit pass and nothing of a write past it reaches the stream`() {
        val target = ByteArrayOutputStream()
        val bounded = BoundedOutputStream(target, limit = 4)

        bounded.write(byteArrayOf(1, 2, 3), 0, 3)
        bounded.write(4)
        val error = runCatching { bounded.write(byteArrayOf(5, 6), 0, 2) }.exceptionOrNull()

        assertTrue("got $error", error is BoundedOutputStream.LimitExceededException)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.toByteArray())
    }

    @Test
    fun `closing it leaves the callers stream open`() {
        var closed = false
        val target = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }

        BoundedOutputStream(target, limit = 10).use { it.write(1) }

        assertTrue("the caller still owns the stream", !closed)
    }
}

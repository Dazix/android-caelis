package com.dazix.caelis.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTimestampRingBufferTest {

    @Test
    fun `empty buffer returns false on findBracket`() {
        val buffer = SensorTimestampRingBuffer(16)
        val q1 = FloatArray(4)
        val q2 = FloatArray(4)
        val timestamps = LongArray(2)

        val found = buffer.findBracket(1000L, q1, q2, timestamps)
        assertFalse(found)
    }

    @Test
    fun `single sample returns that sample as both brackets`() {
        val buffer = SensorTimestampRingBuffer(16)
        buffer.push(100L, 0.1f, 0.2f, 0.3f, 0.9f)

        val q1 = FloatArray(4)
        val q2 = FloatArray(4)
        val timestamps = LongArray(2)

        assertTrue(buffer.findBracket(50L, q1, q2, timestamps))
        assertEquals(100L, timestamps[0])
        assertEquals(100L, timestamps[1])
        assertEquals(0.1f, q1[0], 1e-4f)

        assertTrue(buffer.findBracket(150L, q1, q2, timestamps))
        assertEquals(100L, timestamps[0])
        assertEquals(100L, timestamps[1])
    }

    @Test
    fun `bracketing intermediate timestamp finds exact surrounding samples`() {
        val buffer = SensorTimestampRingBuffer(16)
        buffer.push(100L, 0f, 0f, 0f, 1f)
        buffer.push(200L, 0.1f, 0f, 0f, 0.99f)
        buffer.push(300L, 0.2f, 0f, 0f, 0.98f)
        buffer.push(400L, 0.3f, 0f, 0f, 0.95f)

        val q1 = FloatArray(4)
        val q2 = FloatArray(4)
        val timestamps = LongArray(2)

        // Query timestamp 250L (between 200L and 300L)
        assertTrue(buffer.findBracket(250L, q1, q2, timestamps))
        assertEquals(200L, timestamps[0])
        assertEquals(300L, timestamps[1])
        assertEquals(0.1f, q1[0], 1e-4f)
        assertEquals(0.2f, q2[0], 1e-4f)

        // Query exact sample timestamp 300L
        assertTrue(buffer.findBracket(300L, q1, q2, timestamps))
        assertEquals(300L, timestamps[0])
        assertEquals(300L, timestamps[1])
        assertEquals(0.2f, q1[0], 1e-4f)
    }

    @Test
    fun `circular wrap around retains only latest capacity samples`() {
        val capacity = 16
        val buffer = SensorTimestampRingBuffer(capacity)

        // Push 40 samples
        for (i in 1..40) {
            val t = i * 100L
            buffer.push(t, (i * 0.01f), 0f, 0f, 1f)
        }

        val q1 = FloatArray(4)
        val q2 = FloatArray(4)
        val timestamps = LongArray(2)

        // Oldest retained sample should be 40 - 16 + 1 = 25 -> 2500L
        // Newest retained sample should be 40 -> 4000L
        assertTrue(buffer.findBracket(1000L, q1, q2, timestamps))
        assertEquals(2500L, timestamps[0]) // Clamped to oldest
        assertEquals(2500L, timestamps[1])

        assertTrue(buffer.findBracket(5000L, q1, q2, timestamps))
        assertEquals(4000L, timestamps[0]) // Clamped to newest
        assertEquals(4000L, timestamps[1])

        // Bracket at 3350L (between 3300L and 3400L)
        assertTrue(buffer.findBracket(3350L, q1, q2, timestamps))
        assertEquals(3300L, timestamps[0])
        assertEquals(3400L, timestamps[1])
        assertEquals(33 * 0.01f, q1[0], 1e-4f)
        assertEquals(34 * 0.01f, q2[0], 1e-4f)
    }
}

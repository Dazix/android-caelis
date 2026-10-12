package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source is a 3 wide x 2 tall plane, stored with a row stride of 4 (one padding byte per row):
 *
 *     1 2 3 .
 *     4 5 6 .
 *
 * Each expectation is drawn by hand: the buffer is turned clockwise by the rotation, which is what
 * `CameraBackgroundUv` means by "the rotation that makes the buffer upright".
 */
class LumaRotationTest {

    private val src = byteArrayOf(1, 2, 3, 99, 4, 5, 6, 99)

    private fun rotate(degrees: Int): ByteArray {
        val out = ByteArray(6)
        assertTrue(LumaRotation.rotate(src, 3, 2, 4, degrees, out))
        return out
    }

    @Test
    fun `no rotation only drops the row padding`() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), rotate(0))
    }

    @Test
    fun `90 degrees clockwise puts the first row on the right-hand side`() {
        // 4 1
        // 5 2
        // 6 3
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotate(90))
    }

    @Test
    fun `180 degrees reverses everything`() {
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), rotate(180))
    }

    @Test
    fun `270 degrees clockwise puts the first row on the left-hand side, reversed`() {
        // 3 6
        // 2 5
        // 1 4
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), rotate(270))
    }

    @Test
    fun `a negative or wrapped rotation is the same turn`() {
        assertArrayEquals(rotate(270), rotate(-90))
        assertArrayEquals(rotate(90), rotate(450))
    }

    @Test
    fun `turned rotations swap the width and the height`() {
        assertEquals(2, LumaRotation.uprightWidth(3, 2, 90))
        assertEquals(3, LumaRotation.uprightHeight(3, 2, 90))
        assertEquals(3, LumaRotation.uprightWidth(3, 2, 180))
        assertEquals(3, LumaRotation.uprightHeight(3, 2, 270 + 360))
        assertEquals(2, LumaRotation.uprightHeight(3, 2, 180))
    }

    @Test
    fun `nonsense is refused rather than guessed`() {
        val out = ByteArray(6)
        assertFalse(LumaRotation.rotate(src, 3, 2, 4, 45, out))
        assertFalse(LumaRotation.rotate(src, 0, 2, 4, 0, out))
        assertFalse(LumaRotation.rotate(src, 3, 2, 2, 0, out))
        assertFalse(LumaRotation.rotate(src, 3, 2, 4, 0, ByteArray(5)))
        assertFalse(LumaRotation.rotate(ByteArray(4), 3, 2, 4, 0, out))
    }
}

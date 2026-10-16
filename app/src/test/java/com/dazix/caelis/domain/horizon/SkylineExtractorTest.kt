package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary finder is the half of automatic alignment that can be wrong without looking wrong: a
 * column that reports a cloud edge as terrain is a constraint the matcher has no way to recognise as
 * false. Its thresholds are therefore pinned here against synthetic frames with known answers.
 */
class SkylineExtractorTest {

    private val width = 64
    private val height = 96
    private val stride = 80 // wider than the image, as a real camera buffer is

    /** A frame with bright sky above [ridge] in each column and dark ground below it. */
    private fun frame(
        skyLuma: Int = 200,
        groundLuma: Int = 90,
        ridge: (Int) -> Int,
    ): ByteArray {
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (y < ridge(x)) skyLuma else groundLuma
                data[y * stride + x] = value.toByte()
            }
        }
        return data
    }

    private fun extract(data: ByteArray, columnStride: Int = 1): IntArray {
        val out = IntArray((width + columnStride - 1) / columnStride)
        val count = SkylineExtractor.extract(data, width, height, stride, columnStride, out)
        assertEquals(out.size, count)
        return out
    }

    @Test
    fun `a flat horizon is found on the row it is drawn at`() {
        val rows = extract(frame { 48 })

        for ((column, row) in rows.withIndex()) {
            assertEquals("column $column", 48, row)
        }
    }

    @Test
    fun `a sloping ridge is followed column by column`() {
        val rows = extract(frame { x -> 40 + x / 4 })

        for (x in rows.indices) {
            assertEquals("column $x", 40 + x / 4, rows[x])
        }
    }

    @Test
    fun `a frame with no contrast reports no edge rather than a middle row`() {
        val flat = ByteArray(stride * height) { 128.toByte() }

        val rows = extract(flat)

        for ((column, row) in rows.withIndex()) {
            assertEquals("column $column", SkylineExtractor.NO_EDGE, row)
        }
    }

    /** Fog: a real step, but far too shallow to be a skyline. */
    @Test
    fun `a step below the contrast threshold is refused`() {
        val faint = frame(skyLuma = 130, groundLuma = 120) { 48 }

        val rows = extract(faint)

        assertTrue(
            "a 10/255 step is fog, not a ridge",
            rows.all { it == SkylineExtractor.NO_EDGE },
        )
    }

    /**
     * The ground is rarely uniform: a dark treeline over a lighter field produces a second, upward
     * step below the real one. Only the downward step counts, and only the strongest.
     */
    @Test
    fun `a lighter band below the ridge does not move the answer`() {
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = when {
                    y < 40 -> 210 // sky
                    y < 60 -> 70 // trees
                    else -> 150 // sunlit field
                }
                data[y * stride + x] = value.toByte()
            }
        }

        val rows = extract(data)

        for ((column, row) in rows.withIndex()) {
            assertEquals("column $column", 40, row)
        }
    }

    /**
     * Found through a window at Ještěd: a hazy ridge against the sky, and a sunlit wall below it with
     * twice the contrast. The skyline is the first boundary under the sky, not the strongest one in
     * the column; reporting the wall put the fit 25° low and refused it.
     */
    @Test
    fun `a faint ridge above a stronger edge below is still the skyline`() {
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = when {
                    y < 36 -> 200 // sky
                    y < 60 -> 150 // hazy ridge
                    else -> 40 // dark wall below a bright one
                }
                data[y * stride + x] = value.toByte()
            }
        }

        val rows = extract(data)

        for ((column, row) in rows.withIndex()) {
            assertEquals("column $column", 36, row)
        }
    }

    /** The other side of the same rule: a ripple in the sky must not beat a real ridge below it. */
    @Test
    fun `a faint step high in the sky does not outrank a clear ridge`() {
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = when {
                    y < 30 -> 225 // brighter sky
                    y < 55 -> 205 // a cloud base, 20/255 darker
                    else -> 80 // terrain
                }
                data[y * stride + x] = value.toByte()
            }
        }

        val rows = extract(data)

        for ((column, row) in rows.withIndex()) {
            assertEquals("column $column", 55, row)
        }
    }

    @Test
    fun `sampling every n-th column returns every n-th answer`() {
        val rows = extract(frame { x -> 40 + x / 4 }, columnStride = 4)

        assertEquals(16, rows.size)
        for (i in rows.indices) {
            assertEquals("sample $i", 40 + (i * 4) / 4, rows[i])
        }
    }

    @Test
    fun `a frame too short to hold the windows is refused instead of read out of bounds`() {
        val tiny = ByteArray(stride * 8)
        val out = IntArray(width)

        assertEquals(0, SkylineExtractor.extract(tiny, width, 8, stride, 1, out))
    }

    @Test
    fun `a row stride narrower than the image is refused`() {
        val out = IntArray(width)

        assertEquals(0, SkylineExtractor.extract(ByteArray(16), width, height, width - 1, 1, out))
    }
}

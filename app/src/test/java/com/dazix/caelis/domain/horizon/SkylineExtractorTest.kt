package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary finder is the half of automatic alignment that can be wrong without looking wrong: a
 * column that misses the ridge, or reports a step that is not there, is a constraint the matcher has
 * no way to recognise as false. Its thresholds are therefore pinned here against synthetic frames
 * with known answers. Which of several candidates is the skyline is the matcher's question; what is
 * pinned here is that the ridge is always among them, and that nothing else is invented.
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

    /** A frame whose every column is [band]`(y)`. */
    private fun bands(band: (Int) -> Int): ByteArray {
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) data[y * stride + x] = band(y).toByte()
        }
        return data
    }

    /** Each sampled column's candidate rows, top to bottom. */
    private fun extract(data: ByteArray, columnStride: Int = 1): List<List<Int>> {
        val columns = (width + columnStride - 1) / columnStride
        val rows = IntArray(columns * SkylineExtractor.MAX_CANDIDATES)
        val counts = IntArray(columns)
        val count = SkylineExtractor.extractCandidates(data, width, height, stride, columnStride, rows, counts)
        assertEquals(columns, count)
        return List(columns) { c ->
            List(counts[c]) { rows[c * SkylineExtractor.MAX_CANDIDATES + it] }
        }
    }

    @Test
    fun `a flat horizon is found on the row it is drawn at`() {
        val columns = extract(frame { 48 })

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", listOf(48), candidates)
        }
    }

    @Test
    fun `a sloping ridge is followed column by column`() {
        val columns = extract(frame { x -> 40 + x / 4 })

        for (x in columns.indices) {
            assertEquals("column $x", listOf(40 + x / 4), columns[x])
        }
    }

    @Test
    fun `a frame with no contrast reports no edge rather than a middle row`() {
        val flat = ByteArray(stride * height) { 128.toByte() }

        val columns = extract(flat)

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", emptyList<Int>(), candidates)
        }
    }

    /** Fog: a real step, but far too shallow to be a skyline. */
    @Test
    fun `a step below the contrast threshold is refused`() {
        val faint = frame(skyLuma = 130, groundLuma = 120) { 48 }

        val columns = extract(faint)

        assertTrue("a 10/255 step is fog, not a ridge", columns.all { it.isEmpty() })
    }

    /**
     * The ground is rarely uniform: a dark treeline over a lighter field produces a second, upward
     * step below the real one. Only downward steps are candidates, so it adds none.
     */
    @Test
    fun `a lighter band below the ridge does not move the answer`() {
        val columns = extract(
            bands { y ->
                when {
                    y < 40 -> 210 // sky
                    y < 60 -> 70 // trees
                    else -> 150 // sunlit field
                }
            },
        )

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", listOf(40), candidates)
        }
    }

    /**
     * Found through a window at Ještěd: a hazy ridge against the sky, and a sunlit wall below it with
     * twice the contrast. Reporting only the strongest step put the fit 25° low and refused it. The
     * faint ridge must be a candidate, listed above the wall; that the matcher then takes it is
     * pinned in `SkylineMatcherTest`.
     */
    @Test
    fun `a faint ridge above a stronger edge below is still the skyline`() {
        val columns = extract(
            bands { y ->
                when {
                    y < 36 -> 200 // sky
                    y < 60 -> 150 // hazy ridge
                    else -> 40 // dark wall below a bright one
                }
            },
        )

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", listOf(36, 60), candidates)
        }
    }

    /**
     * The other side of the same rule: a ripple in the sky must not crowd out or stand beside a
     * real ridge below it.
     */
    @Test
    fun `a faint step high in the sky does not outrank a clear ridge`() {
        val columns = extract(
            bands { y ->
                when {
                    y < 30 -> 225 // brighter sky
                    y < 55 -> 205 // a cloud base, 20/255 darker
                    else -> 80 // terrain
                }
            },
        )

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", listOf(55), candidates)
        }
    }

    /**
     * The backlit rooftop: a cloud base, the ridge and a parapet, each a clear downward step. None of
     * them may be dropped, and they come top to bottom.
     */
    @Test
    fun `a cloud base, the ridge and a roof edge are all candidates, top to bottom`() {
        val columns = extract(
            bands { y ->
                when {
                    y < 20 -> 240 // cloud
                    y < 44 -> 195 // sky under it
                    y < 66 -> 150 // hazy ridge
                    else -> 50 // shadowed roof below a lit parapet
                }
            },
        )

        for ((column, candidates) in columns.withIndex()) {
            assertEquals("column $column", listOf(20, 44, 66), candidates)
        }
    }

    /** With more clear steps than room, the weakest one goes, and the order stays top to bottom. */
    @Test
    fun `only the strongest candidates are kept when a column has too many`() {
        // MAX_CANDIDATES + 1 drops, far enough apart that no window sees two; the second is the weakest.
        val spacing = 26
        val tall = 20 + spacing * (SkylineExtractor.MAX_CANDIDATES + 1) + 20
        val data = ByteArray(stride * tall)
        val drops = IntArray(SkylineExtractor.MAX_CANDIDATES + 1) { i -> if (i == 1) 20 else 30 }
        val rows = IntArray(drops.size) { 20 + spacing * it }
        for (x in 0 until width) {
            var value = 250
            for (y in 0 until tall) {
                for (i in drops.indices) if (y == rows[i]) value -= drops[i]
                data[y * stride + x] = value.toByte()
            }
        }
        val out = IntArray(width * SkylineExtractor.MAX_CANDIDATES)
        val counts = IntArray(width)

        SkylineExtractor.extractCandidates(data, width, tall, stride, 1, out, counts)

        val kept = List(counts[0]) { out[it] }
        assertEquals(
            "every step but the weakest, top to bottom",
            rows.filterIndexed { i, _ -> i != 1 },
            kept,
        )
    }

    @Test
    fun `sampling every n-th column returns every n-th answer`() {
        val columns = extract(frame { x -> 40 + x / 4 }, columnStride = 4)

        assertEquals(16, columns.size)
        for (i in columns.indices) {
            assertEquals("sample $i", listOf(40 + (i * 4) / 4), columns[i])
        }
    }

    @Test
    fun `a frame too short to hold the windows is refused instead of read out of bounds`() {
        val tiny = ByteArray(stride * 8)

        assertEquals(
            0,
            SkylineExtractor.extractCandidates(
                tiny, width, 8, stride, 1, IntArray(width * SkylineExtractor.MAX_CANDIDATES), IntArray(width),
            ),
        )
    }

    @Test
    fun `a row stride narrower than the image is refused`() {
        assertEquals(
            0,
            SkylineExtractor.extractCandidates(
                ByteArray(16), width, height, width - 1, 1,
                IntArray(width * SkylineExtractor.MAX_CANDIDATES), IntArray(width),
            ),
        )
    }
}

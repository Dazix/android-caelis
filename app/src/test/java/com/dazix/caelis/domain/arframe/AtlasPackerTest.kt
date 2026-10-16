package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasPackerTest {

    private val out = IntArray(2)

    @Test
    fun `sprites go along a shelf with the gutter around each`() {
        val packer = AtlasPacker(100, 100, gutter = 2)
        assertTrue(packer.place(10, 5, out))
        assertArrayEquals(intArrayOf(2, 2), out)
        assertTrue(packer.place(20, 8, out))
        // The first cell is 14 wide (10 + 2 gutters).
        assertArrayEquals(intArrayOf(16, 2), out)
    }

    @Test
    fun `a sprite that does not fit the row starts a shelf below the tallest one`() {
        val packer = AtlasPacker(50, 100, gutter = 1)
        assertTrue(packer.place(30, 10, out))
        assertTrue(packer.place(10, 20, out))
        assertArrayEquals(intArrayOf(33, 1), out)
        assertTrue(packer.place(30, 5, out))
        // The shelf was 22 tall (20 + 2 gutters).
        assertArrayEquals(intArrayOf(1, 23), out)
    }

    @Test
    fun `sprites never overlap, gutters included`() {
        val packer = AtlasPacker(64, 64, gutter = 2)
        val rects = ArrayList<IntArray>()
        var w = 3
        while (packer.place(w, 7, out)) {
            rects.add(intArrayOf(out[0] - 2, out[1] - 2, out[0] + w + 2, out[1] + 7 + 2))
            w = 3 + (w * 7) % 13
        }
        assertTrue(rects.size > 4)
        for (i in rects.indices) {
            val a = rects[i]
            assertTrue(a[0] >= 0 && a[1] >= 0 && a[2] <= 64 && a[3] <= 64)
            for (j in i + 1 until rects.size) {
                val b = rects[j]
                val overlap = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
                assertFalse("cells $i and $j overlap", overlap)
            }
        }
    }

    @Test
    fun `a full atlas refuses instead of clipping, and leaves the output alone`() {
        val packer = AtlasPacker(20, 20, gutter = 0)
        assertTrue(packer.place(20, 15, out))
        out[0] = -7; out[1] = -7
        assertFalse(packer.place(5, 6, out))
        assertArrayEquals(intArrayOf(-7, -7), out)
        assertFalse("wider than the atlas", AtlasPacker(20, 20).place(30, 1, out))
    }

    @Test
    fun `reset packs from the top-left again`() {
        val packer = AtlasPacker(40, 40, gutter = 1)
        assertTrue(packer.place(30, 30, out))
        assertFalse(packer.place(30, 30, out))
        packer.reset()
        assertTrue(packer.place(30, 30, out))
        assertArrayEquals(intArrayOf(1, 1), out)
    }
}

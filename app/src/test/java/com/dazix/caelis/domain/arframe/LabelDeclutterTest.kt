package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.LabelDeclutter.Companion.HIDDEN
import com.dazix.caelis.domain.arframe.LabelDeclutter.Companion.SLOT_ABOVE
import com.dazix.caelis.domain.arframe.LabelDeclutter.Companion.SLOT_BASE
import com.dazix.caelis.domain.arframe.LabelDeclutter.Companion.SLOT_BELOW
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelDeclutterTest {

    private val step = 24f
    private val margin = 4f

    private fun LabelDeclutter.placeAt(cx: Float, cy: Float, previous: Int = SLOT_BASE) =
        place(cx, cy, 60f, 10f, step, previous, margin)

    @Test
    fun `a name on a free spot stays where it is`() {
        val d = LabelDeclutter(8)
        assertEquals(SLOT_BASE, d.placeAt(100f, 100f))
        assertEquals(SLOT_BASE, d.placeAt(300f, 100f))
    }

    @Test
    fun `a name on a taken spot moves one line down`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        assertEquals(SLOT_BELOW, d.placeAt(110f, 104f))
    }

    @Test
    fun `with its spot and the line below taken a name moves one line up`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        d.placeAt(100f, 100f + step)
        assertEquals(SLOT_ABOVE, d.placeAt(100f, 100f))
    }

    @Test
    fun `with all three spots taken a name is hidden and reserves nothing`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        d.placeAt(100f, 100f + step)
        d.placeAt(100f, 100f - step)
        assertEquals(HIDDEN, d.placeAt(130f, 100f))
        // 149 px from the taken column, free of it; had the hidden name at x = 130 reserved its
        // spot, this one (119 px from it) would be taken.
        assertEquals(SLOT_BASE, d.placeAt(249f, 100f))
    }

    @Test
    fun `a reserved selection outranks every placed name`() {
        val d = LabelDeclutter(8)
        d.reserve(100f, 100f, 60f, 10f)
        assertEquals(SLOT_BELOW, d.placeAt(100f, 100f))
    }

    @Test
    fun `a moved name returns to its spot only once it is free by the margin`() {
        // The other name ends 2 px short of this one's box: free, but within the margin.
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        assertEquals(SLOT_BELOW, d.placeAt(222f, 100f, previous = SLOT_BELOW))

        // 6 px clear of it: past the margin, so the name goes home.
        d.clear()
        d.placeAt(100f, 100f)
        assertEquals(SLOT_BASE, d.placeAt(226f, 100f, previous = SLOT_BELOW))
    }

    @Test
    fun `a name that stayed home keeps it without any margin`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        assertEquals(SLOT_BASE, d.placeAt(222f, 100f, previous = SLOT_BASE))
    }

    @Test
    fun `a hidden name shows again only with the margin`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        d.placeAt(100f, 100f + step)
        d.placeAt(100f, 100f - step)
        // Just touching the taken column (2 px gap): stays hidden.
        assertEquals(HIDDEN, d.placeAt(222f, 100f, previous = HIDDEN))
        assertEquals(SLOT_BASE, d.placeAt(226f, 100f, previous = HIDDEN))
    }

    @Test
    fun `a moved name whose old spot was taken by now tries the others`() {
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f)
        d.placeAt(100f, 100f + step)
        assertEquals(SLOT_ABOVE, d.placeAt(100f, 100f, previous = SLOT_BELOW))
    }

    @Test
    fun `a name moves off its own figure's stars`() {
        // Three belt stars in a row right through the name's own spot.
        val xs = floatArrayOf(80f, 100f, 120f)
        val ys = floatArrayOf(100f, 100f, 100f)
        val d = LabelDeclutter(8)
        assertEquals(SLOT_BELOW, d.place(100f, 100f, 60f, 10f, step, SLOT_BASE, margin, xs, ys, 3, 4f))
    }

    @Test
    fun `a name takes the spot that covers the fewest of its stars`() {
        // Two stars on its own spot, one a line below, none a line above.
        val xs = floatArrayOf(90f, 110f, 100f)
        val ys = floatArrayOf(100f, 100f, 100f + step)
        val d = LabelDeclutter(8)
        assertEquals(SLOT_ABOVE, d.place(100f, 100f, 60f, 10f, step, SLOT_BASE, margin, xs, ys, 3, 4f))
    }

    @Test
    fun `a star trembling on the edge of the name's own spot does not swap it every frame`() {
        // A star right on the top edge of the home box (half height 10 + radius 4 = 14 px above the
        // centre) shifts half a pixel back and forth with the hand. The line below is clear. Seen
        // on the device with a selected Vulpecula: the name jumped a line up and down again and again.
        val xs = floatArrayOf(100f)
        val ys = FloatArray(1)
        val d = LabelDeclutter(8)
        var previous = SLOT_BASE
        val slots = (0 until 10).map { frame ->
            ys[0] = if (frame % 2 == 0) 86.5f else 85.5f
            d.clear()
            previous = d.place(100f, 100f, 60f, 10f, step, previous, margin, xs, ys, 1, 4f)
            previous
        }
        assertEquals(List(10) { SLOT_BELOW }, slots)
    }

    @Test
    fun `a name moved off a star goes home once the star is clear by the margin`() {
        val xs = floatArrayOf(100f)
        val ys = floatArrayOf(100f - 14f - margin - 0.5f)
        val d = LabelDeclutter(8)
        assertEquals(SLOT_BASE, d.place(100f, 100f, 60f, 10f, step, SLOT_BELOW, margin, xs, ys, 1, 4f))
    }

    @Test
    fun `a selection keeps the spot it had and the names around it keep theirs`() {
        // Cygnus at home, the Northern Cross a line below it, the Emu a line above: all three share
        // one spot. Selected, the Emu is placed first; sent home it took Cygnus's spot, Cygnus took
        // the line below and the Northern Cross had none left.
        val d = LabelDeclutter(8)
        fun frame(selected: Boolean): List<Int> {
            d.clear()
            val emu = if (selected) d.place(100f, 100f, 60f, 10f, step, SLOT_ABOVE, margin, pinned = true) else -9
            val cygnus = d.placeAt(100f, 100f, previous = SLOT_BASE)
            val cross = d.placeAt(100f, 100f, previous = SLOT_BELOW)
            val emuLate = if (selected) emu else d.placeAt(100f, 100f, previous = SLOT_ABOVE)
            return listOf(emuLate, cygnus, cross)
        }
        val expected = listOf(SLOT_ABOVE, SLOT_BASE, SLOT_BELOW)
        assertEquals(expected, frame(selected = false))
        repeat(3) { assertEquals(expected, frame(selected = true)) }
        assertEquals(expected, frame(selected = false))
    }

    @Test
    fun `a pinned name whose spot an earlier mark took is placed as any other`() {
        val d = LabelDeclutter(8)
        d.reserve(100f, 100f - step, 60f, 10f)
        assertEquals(SLOT_BASE, d.place(100f, 100f, 60f, 10f, step, SLOT_ABOVE, margin, pinned = true))
    }

    @Test
    fun `stars never hide a name`() {
        // A star in each of the three spots: the name stays home, over one star.
        val xs = floatArrayOf(100f, 100f, 100f)
        val ys = floatArrayOf(100f, 100f + step, 100f - step)
        val d = LabelDeclutter(8)
        assertEquals(SLOT_BASE, d.place(100f, 100f, 60f, 10f, step, SLOT_BASE, margin, xs, ys, 3, 4f))
    }

    @Test
    fun `another name outranks the stars`() {
        // The line below is free of stars but taken by a name; above has a star, home has two.
        val xs = floatArrayOf(90f, 110f, 100f)
        val ys = floatArrayOf(100f, 100f, 100f - step)
        val d = LabelDeclutter(8)
        d.placeAt(100f, 100f + step)
        assertEquals(SLOT_ABOVE, d.place(100f, 100f, 60f, 10f, step, SLOT_BASE, margin, xs, ys, 3, 4f))
    }

    @Test
    fun `a star outside the box does not count`() {
        val xs = floatArrayOf(100f)
        val ys = floatArrayOf(100f + 15f) // 15 px below the centre, half height 10 plus radius 4 is 14
        val d = LabelDeclutter(8)
        assertEquals(SLOT_BASE, d.place(100f, 100f, 60f, 10f, step, SLOT_BASE, margin, xs, ys, 1, 4f))
    }

    @Test
    fun `offsets move a line down and up in screen pixels`() {
        assertEquals(0f, LabelDeclutter.offsetOf(SLOT_BASE, step))
        assertEquals(step, LabelDeclutter.offsetOf(SLOT_BELOW, step))
        assertEquals(-step, LabelDeclutter.offsetOf(SLOT_ABOVE, step))
    }
}

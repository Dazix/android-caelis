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
    fun `offsets move a line down and up in screen pixels`() {
        assertEquals(0f, LabelDeclutter.offsetOf(SLOT_BASE, step))
        assertEquals(step, LabelDeclutter.offsetOf(SLOT_BELOW, step))
        assertEquals(-step, LabelDeclutter.offsetOf(SLOT_ABOVE, step))
    }
}

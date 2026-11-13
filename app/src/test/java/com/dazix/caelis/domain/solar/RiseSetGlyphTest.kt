package com.dazix.caelis.domain.solar

import com.dazix.caelis.domain.solar.RiseSetGlyph.Body
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiseSetGlyphTest {

    private val variants = Body.entries.flatMap { body -> listOf(body to true, body to false) }

    private fun ys(points: FloatArray) = points.filterIndexed { i, _ -> i % 2 == 1 }
    private fun xs(points: FloatArray) = points.filterIndexed { i, _ -> i % 2 == 0 }

    /** Everything of the body: the sun's disc and rays (strokes between the horizon and the arrow), the moon's fill. */
    private fun bodyPoints(d: RiseSetGlyph.Drawing): List<Float> =
        d.strokes.subList(1, d.strokes.size - 2).flatMap { ys(it) } + d.fills.flatMap { ys(it) }

    @Test
    fun `everything stays inside the box, with room for half the stroke width`() {
        val margin = RiseSetGlyph.STROKE / 2
        for ((body, rising) in variants) {
            val d = RiseSetGlyph.drawing(body, rising)
            for (shape in d.strokes + d.fills) {
                assertTrue("$body rising=$rising: a shape needs two points", shape.size >= 4 && shape.size % 2 == 0)
                for (v in shape) {
                    assertTrue("$body rising=$rising: $v out of the box", v - margin >= 0f && v + margin <= RiseSetGlyph.SIZE)
                }
            }
        }
    }

    @Test
    fun `nothing reaches below the horizon`() {
        for ((body, rising) in variants) {
            val d = RiseSetGlyph.drawing(body, rising)
            val horizon = d.strokes.first()
            assertEquals(RiseSetGlyph.HORIZON_Y, horizon[1], 0f)
            assertEquals(RiseSetGlyph.HORIZON_Y, horizon[3], 0f)
            for (y in bodyPoints(d)) {
                assertTrue("$body rising=$rising: y $y below the horizon", y <= RiseSetGlyph.HORIZON_Y + 1e-4f)
            }
        }
    }

    @Test
    fun `only the sun's disc stands on the horizon - rays and the moon keep clear of the line`() {
        // A ray within a stroke width of the line merged with it into a bump at pill size.
        for ((body, rising) in variants) {
            val d = RiseSetGlyph.drawing(body, rising)
            val rays = if (body == Body.SUN) d.strokes.subList(2, d.strokes.size - 2).flatMap { ys(it) } else emptyList()
            for (y in rays) {
                assertTrue("$body rising=$rising: ray y $y within a stroke of the horizon", RiseSetGlyph.HORIZON_Y - y > RiseSetGlyph.STROKE)
            }
            // A fill has no stroke of its own: it clears the horizon's half stroke by a visible gap.
            for (y in d.fills.flatMap { ys(it) }) {
                assertTrue("$body rising=$rising: fill y $y touches the horizon", RiseSetGlyph.HORIZON_Y - y > RiseSetGlyph.STROKE / 2 + 0.5f)
            }
            // Nor does the arrow's own end reach the line.
            val arrow = ys(d.strokes[d.strokes.size - 2]) + ys(d.strokes.last())
            assertTrue("$body rising=$rising: the arrow touches the horizon", RiseSetGlyph.HORIZON_Y - arrow.max() > RiseSetGlyph.STROKE + 0.5f)
        }
    }

    @Test
    fun `rise and set differ only in the arrow's head, which points up at a rise and down at a set`() {
        for (body in Body.entries) {
            val rise = RiseSetGlyph.drawing(body, rising = true)
            val set = RiseSetGlyph.drawing(body, rising = false)
            assertEquals(rise.strokes.size, set.strokes.size)
            for (i in 0 until rise.strokes.size - 1) assertArrayEquals("$body stroke $i", rise.strokes[i], set.strokes[i], 0f)
            assertEquals(rise.fills.size, set.fills.size)
            for (i in rise.fills.indices) assertArrayEquals("$body fill $i", rise.fills[i], set.fills[i], 0f)

            val shaft = rise.strokes[rise.strokes.size - 2]
            val shaftTop = minOf(shaft[1], shaft[3])
            val shaftBottom = maxOf(shaft[1], shaft[3])
            // The head is a chevron: its middle point is the tip, the arms trail behind it.
            val riseHead = rise.strokes.last()
            val setHead = set.strokes.last()
            assertEquals("$body: a rise points up", shaftTop, riseHead[3], 1e-4f)
            assertTrue("$body: a rise's arms trail below the tip", riseHead[1] > riseHead[3] && riseHead[5] > riseHead[3])
            assertEquals("$body: a set points down", shaftBottom, setHead[3], 1e-4f)
            assertTrue("$body: a set's arms trail above the tip", setHead[1] < setHead[3] && setHead[5] < setHead[3])
        }
    }

    @Test
    fun `the sun's arrow stands above it and never touches it`() {
        for (rising in listOf(true, false)) {
            val d = RiseSetGlyph.drawing(Body.SUN, rising)
            val arrowBottom = (ys(d.strokes[d.strokes.size - 2]) + ys(d.strokes.last())).max()
            val bodyTop = bodyPoints(d).min()
            assertTrue("rising=$rising: arrow ends at $arrowBottom, sun starts at $bodyTop", bodyTop - arrowBottom > RiseSetGlyph.STROKE)
        }
    }

    @Test
    fun `the moon's arrow stands beside it, on the right, and never touches it`() {
        // Under the arrow the crescent was too small to read in a pill; beside it both get the full height.
        for (rising in listOf(true, false)) {
            val d = RiseSetGlyph.drawing(Body.MOON, rising)
            val arrowLeft = (xs(d.strokes[d.strokes.size - 2]) + xs(d.strokes.last())).min()
            val moonRight = d.fills.flatMap { xs(it) }.max()
            // The arrow's stroke spreads half a width; the fill does not. Leave a visible gap on top.
            assertTrue("rising=$rising: moon ends at $moonRight, arrow starts at $arrowLeft", arrowLeft - moonRight > RiseSetGlyph.STROKE / 2 + 0.5f)
            val moonHeight = d.fills.flatMap { ys(it) }.let { it.max() - it.min() }
            assertTrue("the crescent fills most of the height: $moonHeight", moonHeight > RiseSetGlyph.SIZE * 0.4f)
        }
    }

    @Test
    fun `the sun is an outline with rays, the moon a filled crescent without`() {
        assertEquals(4, RiseSetGlyph.rayCount(Body.SUN))
        assertEquals(0, RiseSetGlyph.rayCount(Body.MOON))
        // horizon + disc + rays + shaft + head
        assertEquals(1 + 1 + 4 + 2, RiseSetGlyph.drawing(Body.SUN, true).strokes.size)
        assertEquals(0, RiseSetGlyph.drawing(Body.SUN, true).fills.size)
        // horizon + shaft + head, and the crescent as a fill
        assertEquals(1 + 2, RiseSetGlyph.drawing(Body.MOON, true).strokes.size)
        assertEquals(1, RiseSetGlyph.drawing(Body.MOON, true).fills.size)
    }

    @Test
    fun `the body alone has no horizon and no arrow, and fits the box centred`() {
        val margin = RiseSetGlyph.STROKE / 2
        val sun = RiseSetGlyph.body(Body.SUN)
        // A whole disc and eight rays, nothing else.
        assertEquals(1 + 8, sun.strokes.size)
        assertEquals(0, sun.fills.size)
        val disc = sun.strokes.first()
        assertEquals("the disc is closed", disc[0], disc[disc.size - 2], 1e-3f)
        assertEquals("the disc is closed", disc[1], disc[disc.size - 1], 1e-3f)
        val moon = RiseSetGlyph.body(Body.MOON)
        assertEquals(0, moon.strokes.size)
        assertEquals(1, moon.fills.size)
        for (d in listOf(sun, moon)) {
            val all = d.strokes + d.fills
            val x = all.flatMap { xs(it) }
            val y = all.flatMap { ys(it) }
            for (v in x + y) assertTrue("$v out of the box", v - margin >= 0f && v + margin <= RiseSetGlyph.SIZE)
            // Centred within a unit either way, so it sits on the title's line like a letter.
            assertEquals(RiseSetGlyph.SIZE / 2, (x.min() + x.max()) / 2, 1f)
            assertEquals(RiseSetGlyph.SIZE / 2, (y.min() + y.max()) / 2, 1f)
        }
    }

    @Test
    fun `a crescent at any size and place is the icon's crescent scaled and moved`() {
        // The off-screen pointer draws the unit crescent scaled up; it must be the shape the pills show.
        val icon = RiseSetGlyph.drawing(Body.MOON, true).fills.single()
        val unit = RiseSetGlyph.crescentOutline(0f, 0f, 1f)
        assertEquals(icon.size, unit.size)
        val ix = xs(icon)
        val iy = ys(icon)
        val ux = xs(unit)
        val uy = ys(unit)
        // Scale and centre from the outer circle's extent along x on the left (the band) and the overall height.
        val scale = (iy.max() - iy.min()) / (uy.max() - uy.min())
        for (i in ix.indices) {
            assertEquals("x $i", ix[i] - ix[0], (ux[i] - ux[0]) * scale, 1e-3f)
            assertEquals("y $i", iy[i] - iy[0], (uy[i] - uy[0]) * scale, 1e-3f)
        }
    }

    @Test
    fun `the moon's crescent is closed, opens to the right and is a band, not a sliver`() {
        val crescent = RiseSetGlyph.drawing(Body.MOON, true).fills.single()
        val n = crescent.size
        assertEquals(crescent[0], crescent[n - 2], 1e-3f)
        assertEquals(crescent[1], crescent[n - 1], 1e-3f)
        val x = xs(crescent)
        val y = ys(crescent)
        val width = x.max() - x.min()
        val height = y.max() - y.min()
        assertTrue("about as tall as it is wide: $width x $height", width > height * 0.6f && width <= height)
        // The thick part of the band is on the left: at the crescent's middle height, the leftmost
        // run of the outline is wider than a hairline.
        val midY = (y.max() + y.min()) / 2
        val crossings = (0 until n / 2 - 1).mapNotNull { i ->
            val y0 = crescent[i * 2 + 1]
            val y1 = crescent[i * 2 + 3]
            if ((y0 - midY) * (y1 - midY) > 0f || y0 == y1) null
            else crescent[i * 2] + (crescent[i * 2 + 2] - crescent[i * 2]) * (midY - y0) / (y1 - y0)
        }.sorted()
        assertEquals("one band crossed twice at mid height", 2, crossings.size)
        assertTrue("the band is thick enough to read: ${crossings[1] - crossings[0]}", crossings[1] - crossings[0] > 1.2f)
        assertTrue("the band is on the left", crossings[0] < 12f)
    }
}

package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBackdropTest {

    // Roughly a 10 sp label at density 2.75: ascent and descent as Roboto reports them.
    private val textSize = 27.5f
    private val ascent = -25.5f
    private val descent = 6.7f

    @Test
    fun withoutABackdropTheSpriteGetsNoExtraMargin() {
        assertEquals(0, TextBackdrop.marginX(textSize, backdrop = false))
        assertEquals(0, TextBackdrop.marginY(textSize, backdrop = false))
    }

    @Test
    fun theMarginHoldsThePillWithWholePixels() {
        val mx = TextBackdrop.marginX(textSize, backdrop = true)
        val my = TextBackdrop.marginY(textSize, backdrop = true)
        assertEquals(11, mx) // ceil(27.5 * 0.4)
        assertEquals(4, my) // ceil(27.5 * 0.12)
        assertTrue("the pill fits sideways", mx >= textSize * TextBackdrop.PAD_X)
        assertTrue("the pill fits above and below", my >= textSize * TextBackdrop.PAD_Y)
    }

    @Test
    fun thePillHugsTheLettersAndReachesALittlePastThem() {
        val out = FloatArray(4)
        TextBackdrop.pill(textLeft = 20f, textRight = 120f, baseline = 40f, ascent, descent, textSize, out)
        assertEquals("left", 20f - 11f, out[0], 1e-4f)
        assertEquals("top: from the ascent, not the line box", 40f - 25.5f - 3.3f, out[1], 1e-4f)
        assertEquals("right", 120f + 11f, out[2], 1e-4f)
        assertEquals("bottom: to the descent", 40f + 6.7f + 3.3f, out[3], 1e-4f)
    }

    /**
     * A sprite padded by [TextBackdrop.marginX]/[TextBackdrop.marginY] around its letters (as
     * `SpriteAtlas.addText` and `LandmarkMarks.addSprites` lay it out) always contains its pill, so
     * the round ends are never clipped by the sprite's edge.
     */
    @Test
    fun thePillStaysInsideASpritePaddedByTheMargins() {
        for (size in listOf(18f, 22f, 27.5f, 33f, 41.25f)) {
            val shadowPad = 8
            val padX = shadowPad + TextBackdrop.marginX(size, backdrop = true)
            val padY = shadowPad + TextBackdrop.marginY(size, backdrop = true)
            val a = -0.93f * size
            val d = 0.24f * size
            val textWidth = 7.3f * size
            val w = kotlin.math.ceil(textWidth) + 2 * padX
            val h = kotlin.math.ceil(d - a) + 2 * padY
            val baseline = padY - a
            val out = FloatArray(4)
            TextBackdrop.pill(padX.toFloat(), padX + textWidth, baseline, a, d, size, out)
            assertTrue("left in sprite at $size", out[0] >= 0f)
            assertTrue("top in sprite at $size", out[1] >= 0f)
            assertTrue("right in sprite at $size", out[2] <= w)
            assertTrue("bottom in sprite at $size", out[3] <= h)
        }
    }

    @Test
    fun theNameBackdropIsAQuietTranslucentBlack() {
        val alpha = TextBackdrop.NAME_ARGB ushr 24
        val rgb = TextBackdrop.NAME_ARGB and 0xFFFFFF
        assertEquals("black", 0, rgb)
        assertTrue("translucent, the sky shows through (alpha $alpha)", alpha in 64..140)
    }
}

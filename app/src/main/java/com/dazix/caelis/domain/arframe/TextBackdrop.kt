package com.dazix.caelis.domain.arframe

import kotlin.math.ceil

/**
 * The rounded pill a sky name can be drawn on, shared by every atlas sprite that has one: Night Sky's
 * constellation and asterism names ([com.dazix.caelis.ar.scene.SpriteAtlas.addText]) and Sun AR's
 * peak names ([com.dazix.caelis.ar.scene.LandmarkMarks]). One shape, so a name reads the same on
 * either screen.
 *
 * A name is drawn in the terrain's or the sky's own colour with a soft dark shadow. On a dark sky or
 * a bright star that is enough; on a bright daytime sky above a ridge the shadow spreads out and the
 * letters have nothing under them, so a peak name next to a pale sky could not be read. The pill hugs
 * the letters (ascent to descent, not the font's whole line box) and reaches [PAD_X] / [PAD_Y] of the
 * text size past them; it is translucent ([NAME_ARGB]) so the sky and the ridge still show through.
 */
object TextBackdrop {

    /** How far the pill reaches past the letters sideways, as a fraction of the text size. */
    const val PAD_X = 0.4f

    /** How far the pill reaches above the ascent and below the descent, as a fraction of the text size. */
    const val PAD_Y = 0.12f

    /** Black at 45 %: dark enough to carry pale text on a bright sky, light enough to stay a quiet mark. */
    const val NAME_ARGB = 0x73000000

    /** Whole pixels the sprite must add on each side of the letters for the pill (0 without one). */
    fun marginX(textSize: Float, backdrop: Boolean): Int = if (backdrop) ceil(textSize * PAD_X).toInt() else 0

    /** As [marginX], above and below. */
    fun marginY(textSize: Float, backdrop: Boolean): Int = if (backdrop) ceil(textSize * PAD_Y).toInt() else 0

    /**
     * The pill's rectangle, written to [out] as left, top, right, bottom (pixels, y down), for text of
     * [textSize] whose letters run from [textLeft] to [textRight] and whose baseline is at [baseline];
     * [ascent] is negative and [descent] positive, as Android's font metrics give them. The corner
     * radius is half the height.
     */
    fun pill(
        textLeft: Float,
        textRight: Float,
        baseline: Float,
        ascent: Float,
        descent: Float,
        textSize: Float,
        out: FloatArray,
    ) {
        val bx = textSize * PAD_X
        val by = textSize * PAD_Y
        out[0] = textLeft - bx
        out[1] = baseline + ascent - by
        out[2] = textRight + bx
        out[3] = baseline + descent + by
    }
}

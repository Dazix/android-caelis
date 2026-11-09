package com.dazix.caelis.domain.arframe

/**
 * How far the top HUD of the AR screens (the bar with the GPS position, the status chips under it,
 * the debug HUD) has to move down so that nothing on it lies under the camera cutout.
 *
 * The screens are immersive: the status bar is hidden, so `statusBarsPadding` is zero and the bar was
 * placed at a fixed [DESIGN_TOP_DP]. On a phone whose punch-hole or notch reaches lower than that, the
 * position in the middle of the bar was printed over the camera. The cutout inset is what stays, and
 * the whole HUD moves by one shift computed here, so the bar and the chips placed under it cannot end
 * up overlapping each other. Wrapping the text around the hole was rejected: cutouts differ in shape
 * and place from phone to phone, and a split position line does not fit in every language.
 *
 * On a phone whose cutout ends above the designed position the shift is zero and nothing moves.
 */
object HudTopInset {
    /** Where the top bar stands on a screen without a cutout, in dp from the top edge. */
    const val DESIGN_TOP_DP = 36f

    /** Clear space kept between the bottom of the cutout and the top of the bar, in dp. */
    const val CUTOUT_GAP_DP = 8f

    /**
     * The shift, in dp, to add to every top-anchored HUD element, for a cutout reaching [cutoutTopDp]
     * from the top edge (the display cutout's top inset). Never negative: a HUD is never pulled up
     * above its designed place, and an unreadable inset (negative, NaN) moves nothing.
     */
    fun shiftDp(cutoutTopDp: Float): Float {
        if (cutoutTopDp.isNaN() || cutoutTopDp <= 0f) return 0f
        return (cutoutTopDp + CUTOUT_GAP_DP - DESIGN_TOP_DP).coerceAtLeast(0f)
    }
}

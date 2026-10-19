package com.dazix.caelis.domain.arframe

/**
 * Whether a touch starts where the system's own gestures live: back on either side, home and recents
 * along the bottom.
 *
 * Found on the device: swiping Sun AR away to the home screen shifted the stored calibration twice
 * (−2.4/−11.1° to −5.6/−6.7° to 3.0/−6.5°), because the drag that aligns the overlay also ran for a
 * finger that started on the navigation bar. The sides were already left unconsumed so the back
 * gesture could pass, but they still moved the calibration. A gesture that starts in a zone belongs
 * to the system: it neither pans nor aligns, and it is not consumed. A tap there is still a tap.
 */
object SystemGestureZone {

    /** [sideSlopPx] and [bottomSlopPx] are the zones' widths, in the same pixels as [x], [y], [width], [height]. */
    fun contains(x: Float, y: Float, width: Float, height: Float, sideSlopPx: Float, bottomSlopPx: Float): Boolean =
        x < sideSlopPx || x > width - sideSlopPx || y > height - bottomSlopPx
}

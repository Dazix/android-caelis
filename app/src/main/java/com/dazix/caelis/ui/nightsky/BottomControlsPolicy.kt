package com.dazix.caelis.ui.nightsky

/**
 * Whether Night Sky's end-side button column (places, compass reset) has to stand above the bottom
 * HUD rather than beside it.
 *
 * The HUD is centred and grows with the virtual clock ("🕒 10:24") and with the language; once its
 * right edge reached the column, the HUD ran under the compass button. Lifting the column always was
 * one fix, but it moved the buttons on screens where nothing collided. So the column stays on the
 * bottom line and goes up only when the HUD would come closer than [gapPx] to it.
 */
object BottomControlsPolicy {

    /**
     * [containerWidthPx] is the row both share, the HUD centred in it and the column ([columnWidthPx]
     * wide) at its end. A width not measured yet (0) never lifts: the first frame would otherwise
     * jump.
     */
    fun liftButtons(containerWidthPx: Int, hudWidthPx: Int, columnWidthPx: Int, gapPx: Int): Boolean {
        if (containerWidthPx <= 0 || hudWidthPx <= 0 || columnWidthPx <= 0) return false
        val hudRight = (containerWidthPx + hudWidthPx) / 2
        val columnLeft = containerWidthPx - columnWidthPx
        return hudRight + gapPx > columnLeft
    }
}

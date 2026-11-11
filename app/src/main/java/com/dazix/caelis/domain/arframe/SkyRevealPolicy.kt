package com.dazix.caelis.domain.arframe

/**
 * When an AR sky that starts hidden is faded in.
 *
 * Not before its first frame has been rendered: that frame compiles the shaders. And, when the camera
 * picture belongs under it, not before the picture is in the frame. Opening the camera takes longer
 * than building the sky (measured on a Pixel 7 Pro: the grid was drawn after ~250 ms, the first camera
 * image arrived after ~640 ms), and revealed on its first frame the grid stood on an empty dark sky
 * for that difference on every entry. A camera that never delivers (in use elsewhere, failed to open)
 * must not keep the screen empty, so after [PICTURE_WAIT_NS] from the first frame the sky is shown
 * without it. Allocation-free: called on every frame until it says yes.
 */
object SkyRevealPolicy {

    /** [shouldReveal]'s `firstFrameNs` before any frame has been rendered. */
    const val NO_FRAME = Long.MIN_VALUE

    /** How long after its first frame the sky waits for the camera picture before showing without it. */
    const val PICTURE_WAIT_NS = 700_000_000L

    /**
     * @param firstFrameNs frame time of the first rendered frame, or [NO_FRAME].
     * @param nowNs frame time of the frame just rendered, on the same clock.
     * @param pictureWanted whether a camera picture belongs under the sky (false for a virtual place).
     * @param pictureShown whether the frame just rendered contains the camera picture.
     */
    fun shouldReveal(firstFrameNs: Long, nowNs: Long, pictureWanted: Boolean, pictureShown: Boolean): Boolean {
        if (firstFrameNs == NO_FRAME) return false
        if (!pictureWanted || pictureShown) return true
        return nowNs - firstFrameNs >= PICTURE_WAIT_NS
    }
}

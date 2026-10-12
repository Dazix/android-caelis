package com.dazix.caelis.domain.arframe

/**
 * When the yaw between ARCore's world and north has to be measured again.
 *
 * ARCore's world has an arbitrary heading that is fixed when the session starts, and ARCore keeps it
 * only as long as it keeps tracking. Three things can change it behind the app's back, and each one
 * leaves an old yaw estimate wrong while looking perfectly healthy:
 *  - a new session or a resume: the world is a new one ([epoch] changes);
 *  - tracking lost and regained: relocalisation can leave the world rotated;
 *  - (the slow drift of a long session is handled by [HeadingFusion] itself, which rejects outliers
 *    and starts over when they persist).
 *
 * The consequence of a missed one is an overlay rotated by some unknown angle that follows the
 * phone smoothly, so nothing downstream would notice. Hence an explicit trigger instead of trusting
 * the fusion to find out by itself.
 *
 * A sample taken while not tracking is never fed to the fusion, but it still has to be reported here,
 * otherwise a loss that fits between two reported samples would never be seen.
 */
class RealignTrigger {

    private var lastTracking = false
    private var lastEpoch = Int.MIN_VALUE

    /** Returns true when the heading estimate must be discarded before using this sample. */
    fun onFrame(tracking: Boolean, epoch: Int): Boolean {
        val epochChanged = epoch != lastEpoch
        val regained = tracking && !lastTracking
        lastEpoch = epoch
        lastTracking = tracking
        return epochChanged || regained
    }

    fun reset() {
        lastTracking = false
        lastEpoch = Int.MIN_VALUE
    }
}

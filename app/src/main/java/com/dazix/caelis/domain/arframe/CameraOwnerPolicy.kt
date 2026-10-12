package com.dazix.caelis.domain.arframe

import kotlin.math.abs

/**
 * Which camera client owns the physical camera: CameraX or ARCore.
 *
 * They cannot both have it. ARCore opens the camera device itself (there is no way to hand it a
 * CameraX stream), so every change of owner is "release one, then start the other" and costs the
 * better part of a second of picture. That is why this is a policy with memory rather than a
 * function of the inputs: a flickering input must not turn into a flickering camera.
 *
 * ARCore owns the camera only while every condition holds, and "unknown" is never ARCore:
 * - the user consented to ARCore (it reports to Google; see `ArCoreSessionController`);
 * - the camera permission is granted;
 * - the zoom is exactly 1x. ARCore has no zoom, and a picture and an overlay that zoom apart are
 *   worse than no overlay, so a pinch hands the camera back to CameraX at once (the zoom below 1x is
 *   CameraX's ultrawide lens, so it belongs there too);
 * - the last attempt to start ARCore did not fail within [failureBackoffNanos]: a device that cannot
 *   run it would otherwise be asked again every frame.
 *
 * Leaving ARCore is immediate. Coming back after the *zoom* took it away waits [reenterDelayNanos]
 * of conditions holding, so pinching around 1x does not rebind the camera on every crossing. The very
 * first entry has no such wait: the screen has just opened and there is no flicker to guard against.
 *
 * The pose is a separate decision ([PoseArbiter]): ARCore may own the *picture* while the sensors
 * still supply the pose (heading not converged yet, or a dark sky has paused tracking). That is the
 * normal state for the first second after ARCore starts.
 *
 * Pure Kotlin, no allocations.
 */
class CameraOwnerPolicy(
    private val reenterDelayNanos: Long = 1_500_000_000L,
    private val failureBackoffNanos: Long = 30_000_000_000L,
) {

    enum class Owner { CAMERAX, ARCORE }

    var owner: Owner = Owner.CAMERAX
        private set

    private var eligibleSinceNs = Long.MIN_VALUE
    private var hasLeftArCore = false
    private var blockedUntilNs = Long.MIN_VALUE

    /**
     * @param arCoreUsable false when ARCore is known to be unavailable on this device (not supported,
     *   not installed and nobody asked for the install), which is the same as a failed start.
     */
    fun update(
        nowNs: Long,
        consented: Boolean,
        cameraPermission: Boolean,
        zoomRatio: Float,
        arCoreUsable: Boolean = true,
    ): Owner {
        // Withdrawing consent forgets a failure: a user who flips it off and on again is asking for
        // a fresh attempt.
        if (!consented) blockedUntilNs = Long.MIN_VALUE

        val eligible = consented && cameraPermission && arCoreUsable &&
            abs(zoomRatio - 1f) <= ZOOM_EPSILON &&
            (blockedUntilNs == Long.MIN_VALUE || nowNs >= blockedUntilNs)

        if (!eligible) {
            if (owner == Owner.ARCORE) hasLeftArCore = true
            owner = Owner.CAMERAX
            eligibleSinceNs = Long.MIN_VALUE
            return owner
        }
        if (eligibleSinceNs == Long.MIN_VALUE) eligibleSinceNs = nowNs
        val wait = if (hasLeftArCore) reenterDelayNanos else 0L
        if (owner == Owner.CAMERAX && nowNs - eligibleSinceNs >= wait) owner = Owner.ARCORE
        return owner
    }

    /** ARCore refused to start or died: stay on CameraX for the backoff period. */
    fun noteStartFailed(nowNs: Long) {
        blockedUntilNs = nowNs + failureBackoffNanos
        eligibleSinceNs = Long.MIN_VALUE
        hasLeftArCore = true
        owner = Owner.CAMERAX
    }

    companion object {
        /** The same tolerance as [PoseArbiter]: "1x" has to tolerate the last digit of a float, and no more. */
        const val ZOOM_EPSILON = 1e-3f
    }
}

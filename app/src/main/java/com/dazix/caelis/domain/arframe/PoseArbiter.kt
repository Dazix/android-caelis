package com.dazix.caelis.domain.arframe

import kotlin.math.abs

/**
 * Which tracker drives the sky this frame.
 *
 * The sensor tracker is the default and the fallback; ARCore is used only when every condition is
 * positively known to hold. "Unknown" is never ARCore:
 * - no consent -> sensor (ARCore sends telemetry to Google; see `ArCoreSessionController`);
 * - ARCore not TRACKING -> sensor (a dark sky starves VIO of features within seconds);
 * - heading not converged -> sensor (ARCore without north would draw the sky rotated);
 * - zoom other than 1x -> sensor (ARCore has no zoom; the camera image and the overlay must zoom
 *   together or not at all, and only the CameraX path can zoom the image).
 *
 * Handing *to* ARCore waits for [enterDelayNanos] of all conditions holding, so a flickering
 * tracking state does not swap trackers back and forth; handing *back* is immediate. Who owns the
 * camera is a separate decision ([CameraOwnerPolicy]): ARCore may supply the picture while the sensors
 * still supply the pose.
 */
class PoseArbiter(private val enterDelayNanos: Long = 500_000_000L) {

    enum class Mode { SENSOR, ARCORE }

    var mode: Mode = Mode.SENSOR
        private set

    private var eligibleSinceNs = Long.MIN_VALUE

    fun update(
        nowNs: Long,
        consented: Boolean,
        arCoreTracking: Boolean,
        headingConverged: Boolean,
        zoomRatio: Float,
    ): Mode {
        val eligible = consented && arCoreTracking && headingConverged && abs(zoomRatio - 1f) <= ZOOM_EPSILON
        if (!eligible) {
            eligibleSinceNs = Long.MIN_VALUE
            mode = Mode.SENSOR
            return mode
        }
        if (eligibleSinceNs == Long.MIN_VALUE) eligibleSinceNs = nowNs
        if (mode == Mode.SENSOR && nowNs - eligibleSinceNs >= enterDelayNanos) mode = Mode.ARCORE
        return mode
    }

    private companion object {
        const val ZOOM_EPSILON = 1e-3f
    }
}

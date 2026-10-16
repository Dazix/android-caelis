package com.dazix.caelis.ar.pose

import com.dazix.caelis.domain.arframe.PoseArbiter

/**
 * Decides, every frame, whether the sky is drawn with ARCore's pose or the sensors', and keeps the
 * two from disagreeing about when.
 *
 * ARCore's frames feed [arCore]; [PoseArbiter] turns "tracking", "heading converged", "consented" and
 * "zoom" into a mode. Handing *to* ARCore is slow (all conditions for half a second) and handing
 * *back* is immediate: a dark sky pauses ARCore within moments and a frame drawn from a stale pose is
 * the worst thing this screen can show. A tracking state that has not been refreshed for
 * [STALE_NANOS] counts as lost, because a session that stalls stops reporting rather than reporting
 * that it stalled.
 *
 * The sensors are never stopped while ARCore draws: they are the heading reference and the fallback,
 * and the hand-back must find them warm.
 *
 * Not in here: who owns the camera ([com.dazix.caelis.domain.arframe.CameraOwnerPolicy]). ARCore can
 * own the *picture* while this still says sensors - the normal state for the first second after it
 * starts, and for the whole time under a dark sky.
 *
 * All times are one clock, supplied by the caller. Zero allocations.
 */
class PoseHandoff(
    sensor: SensorReference,
    private val arbiter: PoseArbiter = PoseArbiter(),
    val arCore: ArCorePoseSource = ArCorePoseSource(sensor),
) {

    /** The tracker the next frame is drawn with. */
    var kind: PoseKind = PoseKind.SENSOR
        private set

    private var lastSampleAtNs = Long.MIN_VALUE

    /** A new ARCore frame arrived. */
    fun onSample(sample: ArFrameSample, nowNs: Long, consented: Boolean, zoomRatio: Float) {
        arCore.onSample(sample)
        lastSampleAtNs = nowNs
        evaluate(nowNs, consented, zoomRatio)
    }

    /** No new frame this vsync: the mode is still re-evaluated, so a stall is noticed. */
    fun tick(nowNs: Long, consented: Boolean, zoomRatio: Float) = evaluate(nowNs, consented, zoomRatio)

    /** The session is gone (paused, closed, handed to CameraX): back to the sensors, nothing remembered. */
    fun onSessionEnded(nowNs: Long) {
        arCore.reset()
        lastSampleAtNs = Long.MIN_VALUE
        evaluate(nowNs, consented = false, zoomRatio = 1f)
    }

    /**
     * Writes the pose to draw with when ARCore is the active source and has one; false means "use the
     * sensors", whether because they are the active source or because ARCore's pose vanished.
     */
    fun renderMatrix(imageTimestampNs: Long, out16: FloatArray): Boolean =
        kind == PoseKind.ARCORE && arCore.renderMatrix(imageTimestampNs, out16)

    private fun evaluate(nowNs: Long, consented: Boolean, zoomRatio: Float) {
        val fresh = lastSampleAtNs != Long.MIN_VALUE && nowNs - lastSampleAtNs <= STALE_NANOS
        val mode = arbiter.update(
            nowNs = nowNs,
            consented = consented,
            arCoreTracking = arCore.isTracking && fresh,
            headingConverged = arCore.headingConverged,
            zoomRatio = zoomRatio,
        )
        kind = if (mode == PoseArbiter.Mode.ARCORE) PoseKind.ARCORE else PoseKind.SENSOR
    }

    companion object {
        /** About seven camera frames. */
        const val STALE_NANOS = 250_000_000L
    }
}

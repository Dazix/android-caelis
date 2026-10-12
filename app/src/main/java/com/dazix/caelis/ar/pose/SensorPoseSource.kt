package com.dazix.caelis.ar.pose

import com.dazix.caelis.ar.OrientationTracker

/**
 * The sensor tracker as a [PoseSource]: [OrientationTracker] unchanged, with the pose taken at the
 * camera image's instant (SLERP of the raw ring-buffer samples) and the standing calibration, the
 * declination and the heading lock applied exactly as they always were.
 *
 * It is the default, the fallback and the north reference for ARCore ([SensorReference]). It never
 * depends on light, so it is the one that keeps working when a dark sky pauses ARCore.
 *
 * A pan that holds the sensors (Night Sky's manual mode) is the tracker's own business:
 * [OrientationTracker.getRenderMatrix] hands back the frozen pose then.
 */
class SensorPoseSource(private val tracker: OrientationTracker) : SensorPose {

    override val kind: PoseKind = PoseKind.SENSOR

    override fun renderMatrix(imageTimestampNs: Long, out16: FloatArray): Boolean {
        tracker.getRenderMatrix(imageTimestampNs, out16)
        return true
    }

    override fun referenceMatrix(timestampNs: Long, out16: FloatArray): Boolean =
        tracker.getReferenceMatrix(timestampNs, out16)

    override val headingAccuracy: Int get() = tracker.headingAccuracy

    override val calibrationAzimuthDegrees: Float get() = tracker.calibrationAzimuthOffsetDeg

    override val calibrationPitchDegrees: Float get() = tracker.calibrationPitchOffsetDeg
}

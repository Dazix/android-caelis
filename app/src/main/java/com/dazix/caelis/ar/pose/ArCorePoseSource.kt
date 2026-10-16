package com.dazix.caelis.ar.pose

import com.dazix.caelis.ar.OrientationTracker
import com.dazix.caelis.domain.arframe.ArWorldAlignment
import com.dazix.caelis.domain.arframe.ArYawEstimator
import com.dazix.caelis.domain.arframe.HeadingFusion
import com.dazix.caelis.domain.arframe.RealignTrigger

/**
 * ARCore's camera pose as the sky's device -> ENU rotation.
 *
 * ARCore's world is gravity-aligned with an arbitrary heading, so the pose becomes ENU through one
 * yaw ([ArWorldAlignment.deviceToEnu]): `Rz(psi) * EUS_TO_ENU * deviceToArWorld`. `psi` is FOCAI's
 * `northOffset = cameraWorldYaw - trueHeading`, measured by [ArYawEstimator] against the best north
 * there is: Geospatial's orientation when it is consented, keyed and accurate, otherwise the sensor
 * tracker's heading (declination, and the gyro-held lock once the user has aligned), averaged by
 * [HeadingFusion] so ARCore's steadiness ends up pointed at the compass's north.
 *
 * The user's standing calibration is applied on top, as the same world-Z pre-multiply plus camera-X
 * pitch the sensor path uses ([OrientationTracker.applyCalibration]) and read fresh on every
 * [renderMatrix], so a drag or a nudge moves the overlay at once and ARCore needs no calibration of
 * its own: a nudge edits the one stored calibration and both sources follow. The yaw reference
 * excludes it for exactly that reason ([SensorReference.referenceMatrix]).
 *
 * The pose is for one camera frame and belongs to that frame's image: there is no timestamp to
 * interpolate to, which is the whole point of drawing the picture and the overlay from one ARCore
 * frame.
 *
 * Each ARCore session is a new world, and so is tracking regained after a loss ([RealignTrigger]):
 * the estimate starts over on either, and until it has converged again this source says it has no
 * pose, which is what keeps [PoseHandoff] on the sensors.
 *
 * Zero allocations. Main thread, like the frame loop that feeds it.
 */
class ArCorePoseSource(
    private val sensor: SensorReference,
    private val estimator: ArYawEstimator = ArYawEstimator(),
    private val realign: RealignTrigger = RealignTrigger(),
) : PoseSource {

    override val kind: PoseKind = PoseKind.ARCORE

    private val reference = FloatArray(16)
    private val scratch9 = FloatArray(9)
    private val deviceToEnu = FloatArray(16)
    private val calibrationScratch = FloatArray(16)
    private var hasPose = false

    /** Whether the newest frame was TRACKING. */
    var isTracking = false
        private set

    /** The yaw estimate is stable enough to draw with. */
    val headingConverged: Boolean get() = estimator.isConverged

    /** What the north comes from, for diagnostics. */
    val headingSource: HeadingFusion.Source get() = estimator.source

    /** Takes the newest ARCore frame: re-aligns if the world may have changed, measures, stores the pose. */
    fun onSample(sample: ArFrameSample) {
        val tracking = sample.tracking == ArTrackingState.TRACKING
        if (realign.onFrame(tracking, sample.sessionEpoch)) estimator.reset()
        isTracking = tracking
        if (!tracking) {
            // A paused session's pose is stale and says nothing about the heading; do not feed it.
            hasPose = false
            return
        }
        if (sample.geospatialValid) {
            estimator.addGeospatial(
                sample.geospatialEus,
                sample.cameraPose,
                sample.geospatialYawAccuracyDegrees,
                sample.timestampNs,
            )
        }
        // Without a sensor pose from the same instant there is no sample: a pose from "now" against
        // a frame from 60 ms ago would turn the phone's own motion into a yaw error.
        if (sensor.referenceMatrix(sample.timestampNs, reference)) {
            estimator.addCompass(reference, sensor.headingAccuracy, sample.devicePose, sample.timestampNs)
        }
        hasPose = estimator.hasEstimate
        if (hasPose) {
            ArWorldAlignment.deviceToEnu(estimator.yaw, sample.devicePose, scratch9, deviceToEnu)
        }
    }

    /** ARCore's session ended or was replaced: whatever world it had is gone. */
    fun reset() {
        estimator.reset()
        realign.reset()
        isTracking = false
        hasPose = false
    }

    override fun renderMatrix(imageTimestampNs: Long, out16: FloatArray): Boolean {
        if (!hasPose) return false
        OrientationTracker.applyCalibration(
            sensorMatrix = deviceToEnu,
            declinationDeg = 0f,
            panAzimuthOffsetDeg = sensor.calibrationAzimuthDegrees,
            panPitchOffsetDeg = sensor.calibrationPitchDegrees,
            tempDeclinationBuffer = calibrationScratch,
            outMatrix = out16,
        )
        return true
    }
}

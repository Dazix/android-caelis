package com.dazix.caelis.domain.arframe

/**
 * The north offset of ARCore's world: FOCAI's `northOffset = cameraWorldYaw - trueHeading`, done on
 * whole rotations instead of one azimuth so it keeps working at any pitch and near the zenith.
 *
 * Each sample compares the same camera frame in two worlds — ARCore's, and one that knows north
 * (the sensor tracker, or Geospatial's EUS orientation) — and [HeadingFusion] averages the yaw that
 * relates them. ARCore holds its own heading rock steady, so averaging pays off: the result is
 * ARCore's stability pointed at the best north available. A Geospatial yaw outranks the compass
 * whenever it is accurate ([HeadingFusion.addGeospatial]).
 *
 * What is *not* in here: the user's standing calibration. The reference is the sensor path with
 * declination and heading lock but without the calibration, so the calibration stays a separate,
 * instantly editable correction applied on top in both pose sources, the same way. Folding it into
 * the yaw would make a nudge wait for the fusion's time constant, and would count it twice.
 *
 * Zero allocations.
 */
class ArYawEstimator(private val fusion: HeadingFusion = HeadingFusion()) {

    private val scratch9 = FloatArray(9)

    /** Radians, counter-clockwise about Up: the `psi` of [ArWorldAlignment.deviceToEnu]. Meaningful when [hasEstimate]. */
    val yaw: Float get() = fusion.yaw

    val hasEstimate: Boolean get() = fusion.source != HeadingFusion.Source.NONE

    /** Stable enough to draw with: the gate for handing the pose to ARCore. */
    val isConverged: Boolean get() = fusion.isConverged

    val source: HeadingFusion.Source get() = fusion.source

    val uncertaintyDegrees: Float get() = fusion.uncertaintyDegrees

    /**
     * @param reference device -> ENU of the sensor path at the frame's instant (row-major 3x3 or 4x4),
     *   without the standing calibration.
     * @param accuracy `SensorManager.SENSOR_STATUS_*` of the heading the reference rests on.
     * @param arDevice device -> ARCore world of the same frame, row-major 3x3.
     */
    fun addCompass(reference: FloatArray, accuracy: Int, arDevice: FloatArray, timestampNs: Long) {
        val yaw = ArWorldAlignment.estimateYaw(reference, arDevice, scratch9)
        if (!yaw.isFinite()) return
        fusion.addCompass(yaw, accuracy, timestampNs)
    }

    /**
     * @param eusCamera the camera's orientation in East-Up-South from Geospatial, row-major 3x3.
     * @param arCamera the same camera's orientation in ARCore's world, row-major 3x3.
     * @param yawAccuracyDegrees Geospatial's own accuracy for that orientation; samples above
     *   [HeadingFusion]'s threshold are ignored.
     */
    fun addGeospatial(eusCamera: FloatArray, arCamera: FloatArray, yawAccuracyDegrees: Float, timestampNs: Long) {
        val yaw = ArWorldAlignment.yawFromGeospatial(eusCamera, arCamera)
        if (!yaw.isFinite()) return
        fusion.addGeospatial(yaw, yawAccuracyDegrees, timestampNs)
    }

    /** ARCore's world changed (new session, relocalisation) or the session ended. */
    fun reset() = fusion.reset()
}

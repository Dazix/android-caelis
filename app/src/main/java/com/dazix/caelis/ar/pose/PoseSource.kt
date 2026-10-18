package com.dazix.caelis.ar.pose

import android.hardware.HardwareBuffer

/** Which tracker a pose came from. Shown to the user ([com.dazix.caelis.ui.PoseSourceChip]). */
enum class PoseKind { SENSOR, ARCORE }

/**
 * Something that can say where the camera points, as the device -> ENU rotation the sky is drawn
 * with: row-major 4x4 in the layout `SensorManager` produces, with the user's standing calibration
 * already applied.
 *
 * This file is free of ARCore types on purpose. Everything outside `ar/pose/` is not allowed to see
 * them (`NetworkIsolationTest`), and the screens, the frame loops and the tests only need this much.
 */
interface PoseSource {
    val kind: PoseKind

    /**
     * The pose for the camera image whose instant is [imageTimestampNs] (the sensor clock; 0 = the
     * newest pose there is). Returns false when this source has no pose to give right now.
     */
    fun renderMatrix(imageTimestampNs: Long, out16: FloatArray): Boolean
}

/**
 * What ARCore's world needs from the sensor path: the north it is aligned to and the standing
 * calibration applied on top. See `ArYawEstimator` for why the reference excludes the calibration.
 */
interface SensorReference {
    /**
     * device -> ENU at [timestampNs] with declination and heading lock but no calibration and no
     * coarse heading correction. False (and [out16] untouched) when the sensor history has nothing
     * bracketing that instant.
     */
    fun referenceMatrix(timestampNs: Long, out16: FloatArray): Boolean

    /** `SensorManager.SENSOR_STATUS_ACCURACY_*` of the heading the reference rests on. */
    val headingAccuracy: Int

    /** The compass correction applied on top, world-Z, degrees: the coarse heading correction plus the calibration. */
    val calibrationAzimuthDegrees: Float

    /** The standing pitch correction, camera-X, degrees. */
    val calibrationPitchDegrees: Float
}

/** The sensor tracker: a pose to draw with, and the north and calibration ARCore's pose is built on. */
interface SensorPose : PoseSource, SensorReference

enum class ArTrackingState { TRACKING, PAUSED, STOPPED }

/**
 * One ARCore camera frame reduced to what the app uses, in plain arrays so a test can fake it and so
 * the render loop can reuse one instance.
 *
 * ARCore's Java API allocates a `Frame`, a `Camera` and a `Pose` wrapper on every `update()` and
 * `getEastUpSouthQuaternion()` returns a fresh array; those are the platform's and cannot be pooled.
 * Everything the app adds on top is written into these preallocated fields.
 */
class ArFrameSample {
    /** Capture time of the image on the sensor clock (`Frame.getAndroidCameraTimestamp`). */
    var timestampNs = 0L
    var tracking = ArTrackingState.STOPPED

    /** Changes whenever ARCore's world may be a different one: a new session, a resume. */
    var sessionEpoch = 0

    /** The device's axes in ARCore's world, row-major 3x3 (`Frame.getAndroidSensorPose`). */
    val devicePose = FloatArray(9)

    /** The camera's axes in ARCore's world, row-major 3x3 (`Camera.getPose`), for Geospatial's yaw. */
    val cameraPose = FloatArray(9)

    /** Horizontal field of view of the viewport, from ARCore's projection; 0 when not known. */
    var fovHorizontalDegrees = 0f

    var geospatialValid = false
    val geospatialEus = FloatArray(9)
    var geospatialYawAccuracyDegrees = Float.NaN

    /**
     * The camera image, when this frame brought a new one. **Ownership passes to whoever reads it**:
     * it must be handed to the background (which closes it when Filament is done) or closed.
     */
    var buffer: HardwareBuffer? = null
    var imageWidth = 0
    var imageHeight = 0

    /** [uv] holds new buffer coordinates for the full-screen triangle (display geometry changed). */
    var uvChanged = false
    val uv = FloatArray(6)

    fun clear() {
        timestampNs = 0L
        tracking = ArTrackingState.STOPPED
        fovHorizontalDegrees = 0f
        geospatialValid = false
        geospatialYawAccuracyDegrees = Float.NaN
        buffer = null
        imageWidth = 0
        imageHeight = 0
        uvChanged = false
    }
}

/**
 * A brightness plane copied upright out of an ARCore image (tightly packed: stride = [width]), and
 * how many viewport pixels one of its pixels covers as ARCore maps the image onto the screen. The
 * pose and field of view of the same frame are the rig's to add ([com.dazix.caelis.ar.LumaFramePose]).
 */
class ArLumaCapture(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val fillScale: Float,
)

/**
 * The ARCore session as the pose code sees it: the newest frame, nothing else. The real one wraps
 * `Session.update()` ([ArCoreFrameSource]); a test supplies a script, which is how the hand-off on
 * `TrackingState.PAUSED` is checked without a session or a device that has ARCore.
 */
interface ArFrameSource {

    /** Changes when the world may have changed (new session, resume). */
    val sessionEpoch: Int

    /**
     * Fills [out] with the newest frame if it is newer than the last one returned, and says so.
     * Allocation-free except for what ARCore itself allocates. Never throws for a session that is
     * paused or gone: that is "nothing new".
     */
    fun poll(out: ArFrameSample): Boolean

    /** The display ARCore fits its picture to; call whenever the viewport or the rotation changes. */
    fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int)

    /**
     * The brightness plane of the frame [poll] returned last, upright, with the geometry to place it;
     * null when it cannot be had (not yet available, session gone).
     */
    fun copyLuma(): ArLumaCapture?
}

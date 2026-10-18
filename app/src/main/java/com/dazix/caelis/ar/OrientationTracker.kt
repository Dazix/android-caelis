package com.dazix.caelis.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.dazix.caelis.domain.arframe.CoarseHeading
import com.dazix.caelis.domain.arframe.HeadingLock
import com.dazix.caelis.domain.arframe.HeadingState
import com.dazix.caelis.domain.arframe.YawOffset
import com.dazix.caelis.domain.solar.GeoLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class DeviceOrientation(
    val rotationMatrix: FloatArray,
    val cameraAzimuthDegrees: Float,
    val cameraElevationDegrees: Float,
    val cameraRollDegrees: Float,
    val accuracy: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceOrientation
        return rotationMatrix.contentEquals(other.rotationMatrix) &&
            cameraAzimuthDegrees == other.cameraAzimuthDegrees &&
            cameraElevationDegrees == other.cameraElevationDegrees &&
            cameraRollDegrees == other.cameraRollDegrees &&
            accuracy == other.accuracy
    }

    override fun hashCode(): Int {
        var result = rotationMatrix.contentHashCode()
        result = 31 * result + cameraAzimuthDegrees.hashCode()
        result = 31 * result + cameraElevationDegrees.hashCode()
        result = 31 * result + cameraRollDegrees.hashCode()
        result = 31 * result + accuracy
        return result
    }
}

/**
 * Real-time 3D orientation tracking for the AR Sky Dome using hardware sensors.
 *
 * Employs Quaternion Spherical Linear Interpolation (SLERP / normalized LERP) to prevent
 * matrix degeneration, ensuring a guaranteed 100% orthonormal rotation matrix at all times.
 *
 * Implements seamless sensor freezing on drag (Static Manual Mode):
 * - On first touch drag: freezes current live sensor matrix and pauses sensor tracking.
 * - Horizontal swipe: rotates frozen matrix about World Z-axis (Up), keeping horizon perfectly level.
 * - Vertical swipe: rotates about Camera X-axis (Right) with direct-touch mapping (swipe UP -> sky UP).
 * - Dynamic clamping at 85° prevents virtual camera gimbal lock.
 * - Upside-down physical tilt (> 90° or < -90°) dynamically inverts horizontal drag for natural UX.
 * - When resumed: unpauses sensor tracking and returns to pure live sensor orientation.
 */
class OrientationTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gameSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /**
     * Also listen to the game rotation vector, so that an alignment can stop the compass moving the
     * heading ([headingLock]).
     *
     * The rotation vector alone keeps pulling the heading towards the magnetometer, and near rebar
     * or a window frame the field is bent differently in every direction: aligned by hand, a look
     * left and right came back off. The rotation vector is still what is drawn: drawing the game
     * vector instead made the overlay visibly trail a tilt up or down on a Pixel 7 Pro. Without
     * both sensors this is the old single-sensor path.
     */
    private val dualMode = gameSensor != null && rotationSensor != null &&
        rotationSensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR

    private val headingLock = HeadingLock()

    /** [HeadingLock.correctionDegrees], folded into the world-Z term next to the declination. */
    @Volatile
    private var headingOffsetDeg = 0f

    /**
     * The part of an alignment the ±[MAX_CALIBRATION_AZIMUTH] calibration cannot hold, while the
     * compass is not trusted and the gyro holds the heading ([CoarseHeading]). Session-only: never
     * stored, cleared by [start] and [clearCalibration]. Folded into the world-Z term next to the
     * declination, so it is in [getReferenceMatrix] — the automatic alignment searches around the
     * coarsely corrected heading, and the calibration stays a fine correction on top. ARCore's north
     * ([getCompassReferenceMatrix]) leaves it out and gets it on top instead.
     */
    @Volatile
    var coarseHeadingOffsetDeg: Float = 0f
        private set

    /** The rotation vector's own heading uncertainty (`values[4]`), degrees; NaN when not reported. */
    private var headingUncertaintyDeg = Float.NaN
    private var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private val compassMatrix = FloatArray(16)
    private val gameMatrix = FloatArray(16)
    private val latestGameQuat = FloatArray(4)
    private var latestGameTimestampNs = 0L
    private var hasGameSample = false
    private val latestCompassQuat = FloatArray(4)
    private var latestCompassTimestampNs = 0L
    private var hasCompassSample = false
    private var lastPairedTimestampNs = Long.MIN_VALUE
    private val sampleQuat = FloatArray(4)

    private val headingStateFlow = MutableStateFlow(HeadingState.OK)

    /** What the heading rests on: the compass (and how good it says it is) or the user's alignment. */
    val headingState: StateFlow<HeadingState> = headingStateFlow.asStateFlow()

    private val smoothedQuat = FloatArray(4).apply { this[3] = 1f }
    private val rawMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val liveTrueMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val frozenMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val tempDeclinationMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val adjustedMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val tempAdjustedMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val publishedMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    // 1.3 s at SAMPLING_PERIOD_US, comfortably more than PoseTimestampPolicy.MAX_FRAME_AGE_NS.
    private val ringBuffer = SensorTimestampRingBuffer(256)
    private var lastRotationSampleNs = 0L
    private val syncQ1 = FloatArray(4)
    private val syncQ2 = FloatArray(4)
    private val syncQInterp = FloatArray(4)
    private val syncTimestamps = LongArray(2)
    private val syncRawMatrix = FloatArray(16)

    @Volatile
    var panAzimuthOffsetDeg: Float = 0f
        private set

    @Volatile
    var panPitchOffsetDeg: Float = 0f
        private set

    /**
     * Standing correction for the compass, applied to the *live* matrix on every sensor sample.
     *
     * Separate from [panAzimuthOffsetDeg] on purpose. A pan is a gesture and freezes the sensors
     * while it lasts; a calibration is a property of the phone's magnetometer in the place it is
     * standing, and the overlay has to keep tracking while it is in force. Restoring a saved
     * calibration through [setPanOffsets] used to open the AR screen frozen, which made the stored
     * value worse than useless.
     */
    @Volatile
    var calibrationAzimuthOffsetDeg: Float = 0f
        private set

    /** Standing correction for the camera's pitch. See [calibrationAzimuthOffsetDeg]. */
    @Volatile
    var calibrationPitchOffsetDeg: Float = 0f
        private set

    val isSensorTrackingPaused = MutableStateFlow(false)

    private val stateFlow = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = stateFlow.asStateFlow()

    /** The sensor instant of the last [orientation] emission; see [publishDue]. */
    private var lastPublishedSampleNs = 0L

    private var isListening = false
    private var isFirstSample = true

    private var magneticDeclinationDeg = 0.0f

    /**
     * Returns the live True-North corrected sensor rotation matrix (World ENU space)
     * without heap allocation.
     */
    fun getLiveMatrix(): FloatArray = liveTrueMatrix

    fun getLiveMatrix(out: FloatArray): FloatArray {
        System.arraycopy(liveTrueMatrix, 0, out, 0, minOf(liveTrueMatrix.size, out.size))
        return out
    }

    /**
     * Obtains the True-North and calibration-corrected rotation matrix matching the exact
     * exposure [timestampNs] using quaternion SLERP.
     *
     * @param timestampNs Camera frame exposure timestamp in nanoseconds (from ImageInfo).
     * @param outMatrix16 Pre-allocated 16-element FloatArray receiving R_true.
     * @return true if interpolated from bracketing sensor events; false if falling back to live matrix.
     */
    fun getSynchronizedMatrix(timestampNs: Long, outMatrix16: FloatArray): Boolean =
        synchronizedMatrix(timestampNs, outMatrix16, northCorrectionDeg(), calibrationAzimuthOffsetDeg, calibrationPitchOffsetDeg)

    /**
     * The same pose as [getSynchronizedMatrix] but without the standing calibration: declination,
     * the heading lock and the coarse heading correction are in, the calibration the user nudged is not.
     *
     * This is the north reference ARCore's world is aligned to. The calibration is applied on top of
     * either pose source afterwards, identically, so it stays instantly editable and is never counted
     * twice. Unlike [getSynchronizedMatrix] this has no live fallback and does not accept the ring
     * buffer's clamping: a pose that is not from the requested instant would turn the phone's own
     * motion into a yaw error, and a timestamp from another clock would look exactly like a stale one.
     * So it returns false, leaving [outMatrix16] untouched, unless the history really covers the
     * instant: not older than its oldest sample, and not more than [REFERENCE_MAX_EXTRAPOLATION_NS]
     * past its newest (one sample period or so at 200 Hz).
     */
    fun getReferenceMatrix(timestampNs: Long, outMatrix16: FloatArray): Boolean =
        referenceMatrix(timestampNs, outMatrix16, northCorrectionDeg())

    /**
     * [getReferenceMatrix] without the coarse heading correction either: the north the sensors
     * themselves measure (declination and heading lock in), which is what ARCore's world is aligned
     * to. The coarse correction is then applied on top of ARCore's pose together with the
     * calibration ([coarseHeadingOffsetDeg] + [calibrationAzimuthOffsetDeg]). Inside this reference
     * a coarse drag would arrive at `HeadingFusion` as a jump of tens of degrees, be rejected as an
     * outlier for 30 samples and only then restart the estimate.
     */
    fun getCompassReferenceMatrix(timestampNs: Long, outMatrix16: FloatArray): Boolean =
        referenceMatrix(timestampNs, outMatrix16, northCorrectionDeg() - coarseHeadingOffsetDeg)

    private fun referenceMatrix(timestampNs: Long, outMatrix16: FloatArray, northDeg: Float): Boolean {
        require(outMatrix16.size >= 16) { "outMatrix16 must have at least 16 elements" }
        if (timestampNs <= 0L || !ringBuffer.findBracket(timestampNs, syncQ1, syncQ2, syncTimestamps)) return false
        if (timestampNs < syncTimestamps[0] || timestampNs > syncTimestamps[1] + REFERENCE_MAX_EXTRAPOLATION_NS) return false
        return synchronizedMatrix(timestampNs, outMatrix16, northDeg, 0f, 0f)
    }

    /**
     * What the heading of the sensor path rests on, as `SENSOR_STATUS_ACCURACY_*`: the compass's own
     * report, or high once the user has aligned and the gyro holds the heading (the user measured it).
     */
    val headingAccuracy: Int
        get() = if (headingLock.isLocked) SensorManager.SENSOR_STATUS_ACCURACY_HIGH else compassAccuracy

    private fun synchronizedMatrix(
        timestampNs: Long,
        outMatrix16: FloatArray,
        northDeg: Float,
        calibrationAzimuthDeg: Float,
        calibrationPitchDeg: Float,
    ): Boolean {
        require(outMatrix16.size >= 16) { "outMatrix16 must have at least 16 elements" }

        if (timestampNs <= 0L || !ringBuffer.findBracket(timestampNs, syncQ1, syncQ2, syncTimestamps)) {
            getLiveMatrix(outMatrix16)
            return false
        }

        val t1 = syncTimestamps[0]
        val t2 = syncTimestamps[1]
        val u = if (t2 > t1) {
            ((timestampNs - t1).toDouble() / (t2 - t1).toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        QuaternionSlerp.slerp(syncQ1, syncQ2, u, syncQInterp)
        QuaternionSlerp.quaternionToRowMajorMatrix(syncQInterp, syncRawMatrix)

        applyCalibration(
            sensorMatrix = syncRawMatrix,
            declinationDeg = northDeg,
            panAzimuthOffsetDeg = calibrationAzimuthDeg,
            panPitchOffsetDeg = calibrationPitchDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = outMatrix16,
        )
        return true
    }

    /**
     * The matrix an AR frame draws with: the pose at the camera image's instant [timestampNs]
     * (`PreviewFrameClock.renderTimestampNs`), the live one when that is 0, and the frozen pan while
     * a pan holds the sensors. Allocation-free; main thread, like the sensor callbacks.
     */
    fun getRenderMatrix(timestampNs: Long, outMatrix16: FloatArray) {
        if (isSensorTrackingPaused.value) {
            System.arraycopy(getAdjustedMatrix(), 0, outMatrix16, 0, 16)
        } else {
            getSynchronizedMatrix(timestampNs, outMatrix16)
        }
    }

    /**
     * Computes the adjusted rotation matrix incorporating manual pan offsets or live sensor state
     * with 100% zero heap allocations.
     */
    fun getAdjustedMatrix(liveMatrix: FloatArray = liveTrueMatrix): FloatArray {
        if (isSensorTrackingPaused.value) {
            System.arraycopy(frozenMatrix, 0, tempAdjustedMatrix, 0, 16)
            applyCalibration(
                sensorMatrix = frozenMatrix,
                declinationDeg = 0.0f,
                panAzimuthOffsetDeg = panAzimuthOffsetDeg,
                panPitchOffsetDeg = panPitchOffsetDeg,
                tempDeclinationBuffer = tempDeclinationMatrix,
                outMatrix = tempAdjustedMatrix,
            )
            return tempAdjustedMatrix
        } else {
            return liveMatrix
        }
    }

    fun updateLocation(location: GeoLocation) {
        try {
            val field = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitudeMeters.toFloat(),
                System.currentTimeMillis(),
            )
            magneticDeclinationDeg = field.declination
        } catch (_: Exception) {
        }
    }

    /**
     * Sets the standing calibration without touching sensor tracking.
     *
     * This is what a restored calibration and the alignment controls go through. [setPanOffsets] is
     * the gesture path and pauses the sensors; using it to restore a saved value froze the overlay
     * on every launch for anyone who had ever calibrated.
     */
    fun setCalibration(azimuthDeg: Float, pitchDeg: Float) {
        // Clamped, not wrapped. A calibration corrects a compass by single degrees; allowing it to
        // run all the way round would turn a careless swipe into "the sky is now facing the other
        // way", indistinguishable on the next launch from a broken magnetometer. A compass that really
        // is that far out is corrected by the session-only coarseHeadingOffsetDeg instead.
        calibrationAzimuthOffsetDeg = signedAzimuth(azimuthDeg)
            .coerceIn(-MAX_CALIBRATION_AZIMUTH, MAX_CALIBRATION_AZIMUTH)
        calibrationPitchOffsetDeg = pitchDeg.coerceIn(-MAX_CALIBRATION_PITCH, MAX_CALIBRATION_PITCH)

        // Re-derive the published matrix immediately: a nudge has to move the overlay now, not on
        // the next sensor sample, and while tracking is paused there will not be one.
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = northCorrectionDeg(),
            panAzimuthOffsetDeg = calibrationAzimuthOffsetDeg,
            panPitchOffsetDeg = calibrationPitchOffsetDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = liveTrueMatrix,
        )
        if (!isSensorTrackingPaused.value) {
            System.arraycopy(liveTrueMatrix, 0, frozenMatrix, 0, 16)
            System.arraycopy(liveTrueMatrix, 0, adjustedMatrix, 0, 16)
            publishOrientation(accuracy = stateFlow.value?.accuracy ?: 3)
        }
    }

    /**
     * Moves the standing calibration by a delta, leaving the sensors running.
     *
     * This is the drag gesture on the Sun AR screen and the nudge buttons: the finger walks the
     * overlay onto the real skyline while the phone keeps tracking, so letting go does not snap
     * anything back and turning around still works.
     */
    fun nudgeCalibrationBy(deltaAzimuthDeg: Float, deltaPitchDeg: Float) {
        // Only an azimuth correction measures the heading. A pitch-only nudge (the up/down
        // buttons, an auto-align on terrain too flat for the compass) says nothing about north.
        if (deltaAzimuthDeg != 0f) lockHeading()
        setAlignment(
            totalAzimuthDeg = coarseHeadingOffsetDeg + calibrationAzimuthOffsetDeg + deltaAzimuthDeg,
            pitchDeg = calibrationPitchOffsetDeg + deltaPitchDeg,
        )
    }

    /**
     * Applies a hand alignment of [totalAzimuthDeg] in all: the calibration takes what its bound
     * allows and, while [CoarseHeading.isAllowed], the coarse correction takes the rest.
     */
    private fun setAlignment(totalAzimuthDeg: Float, pitchDeg: Float) {
        val allowed = CoarseHeading.isAllowed(
            locked = headingLock.isLocked,
            compassState = HeadingLock.compassState(compassAccuracy, headingUncertaintyDeg, MAX_CALIBRATION_AZIMUTH),
        )
        coarseHeadingOffsetDeg = CoarseHeading.coarseFor(
            totalAzimuthDegrees = totalAzimuthDeg,
            currentCoarseDegrees = coarseHeadingOffsetDeg,
            boundDegrees = MAX_CALIBRATION_AZIMUTH,
            allowed = allowed,
        )
        setCalibration(azimuthDeg = totalAzimuthDeg - coarseHeadingOffsetDeg, pitchDeg = pitchDeg)
    }

    /**
     * Turns the pan the user has just dragged into the standing calibration and resumes tracking.
     *
     * This is the "align the overlay with the real skyline, then keep tracking" gesture: the frozen
     * delta is exactly the compass error the user just measured by eye.
     */
    fun commitPanAsCalibration() {
        val azimuth = calibrationAzimuthOffsetDeg + panAzimuthOffsetDeg
        val pitch = calibrationPitchOffsetDeg + panPitchOffsetDeg
        panAzimuthOffsetDeg = 0f
        panPitchOffsetDeg = 0f
        isSensorTrackingPaused.value = false
        if (azimuth != calibrationAzimuthOffsetDeg) lockHeading()
        setAlignment(coarseHeadingOffsetDeg + azimuth, pitch)
    }

    /**
     * The user has just aligned the overlay with something real: from now on the gyro holds the
     * heading and the compass is no longer consulted, until [clearCalibration] or a new session.
     * A restored calibration ([setCalibration]) does not come through here — it was measured
     * somewhere else.
     */
    fun lockHeading() {
        if (!dualMode) return
        headingLock.lock()
        publishHeadingState()
    }

    /** Explicit reset: no calibration, and back to the compass for the heading. */
    fun clearCalibration() {
        headingLock.reset()
        coarseHeadingOffsetDeg = 0f
        publishHeadingState()
        setCalibration(0f, 0f)
    }

    fun setPanOffsets(azimuthDeg: Float, pitchDeg: Float) {
        panAzimuthOffsetDeg = (azimuthDeg % 360f + 360f) % 360f
        panPitchOffsetDeg = pitchDeg.coerceIn(-MAX_GESTURE_PITCH, MAX_GESTURE_PITCH)
        if (panAzimuthOffsetDeg != 0f || panPitchOffsetDeg != 0f) {
            isSensorTrackingPaused.value = true
        }
    }

    fun resetUserRotation() {
        resumeSensorTracking()
    }

    fun resumeSensorTracking() {
        panAzimuthOffsetDeg = 0f
        panPitchOffsetDeg = 0f
        isSensorTrackingPaused.value = false

        // Immediately update orientation to live sensor values. The standing calibration survives:
        // dropping a pan is "stop dragging", not "forget where north is".
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = northCorrectionDeg(),
            panAzimuthOffsetDeg = calibrationAzimuthOffsetDeg,
            panPitchOffsetDeg = calibrationPitchOffsetDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = adjustedMatrix,
        )
        System.arraycopy(adjustedMatrix, 0, liveTrueMatrix, 0, 16)
        System.arraycopy(adjustedMatrix, 0, frozenMatrix, 0, 16)
        publishOrientation(accuracy = 3)
    }

    fun isUserRotated(): Boolean = isSensorTrackingPaused.value || (panAzimuthOffsetDeg != 0f || panPitchOffsetDeg != 0f)


    /**
     * Bulletproof drag gesture handler for Alt-Azimuth panning with sensor freezing.
     *
     * @param deltaX Horizontal pan in pixels (+X = dragging right).
     * @param deltaY Vertical pan in pixels (+Y = dragging down).
     * @param pixelsToDegreesFactor Scaling factor converting pixels to degrees (e.g. fovDegrees / viewWidth).
     * @param currentLiveMatrix Optional override for live matrix (defaults to [liveTrueMatrix]).
     */
    fun onPanGesture(
        deltaX: Float,
        deltaY: Float,
        pixelsToDegreesFactor: Float,
        currentLiveMatrix: FloatArray = liveTrueMatrix,
    ) {
        // 1. FREEZE SENSORS ON FIRST TOUCH
        if (!isSensorTrackingPaused.value) {
            isSensorTrackingPaused.value = true
            System.arraycopy(currentLiveMatrix, 0, frozenMatrix, 0, minOf(currentLiveMatrix.size, 16))
        }

        // 2. EXTRACT PITCH FROM THE FROZEN MATRIX
        val basePitch = extractPitchFromMatrix(frozenMatrix)
        val currentAbsolutePitch = basePitch + panPitchOffsetDeg

        // 3. AZIMUTH (Horizontal Pan)
        // If phone is tilted past zenith (> 90 or < -90), camera is upside down -> invert X drag
        val isUpsideDown = currentAbsolutePitch > 90f || currentAbsolutePitch < -90f
        val effectiveDeltaX = if (isUpsideDown) -deltaX else deltaX

        panAzimuthOffsetDeg -= effectiveDeltaX * pixelsToDegreesFactor
        panAzimuthOffsetDeg = (panAzimuthOffsetDeg % 360f + 360f) % 360f

        // 4. PITCH (Vertical Pan) - Direct touch mapping (swipe UP -> sky UP)
        val requestedPitchDelta = deltaY * pixelsToDegreesFactor

        // 5. DYNAMIC CLAMPING (Stop before Gimbal Lock at 85°)
        val prospectiveAbsolutePitch = currentAbsolutePitch + requestedPitchDelta
        val clampedAbsolutePitch = prospectiveAbsolutePitch.coerceIn(-MAX_GESTURE_PITCH, MAX_GESTURE_PITCH)

        panPitchOffsetDeg = clampedAbsolutePitch - basePitch

        // 6. ASSEMBLE MATRIX FROM FROZEN MATRIX AND PUBLISH
        applyCalibration(
            sensorMatrix = frozenMatrix,
            declinationDeg = 0.0f, // Already declination-corrected in frozenMatrix
            panAzimuthOffsetDeg = panAzimuthOffsetDeg,
            panPitchOffsetDeg = panPitchOffsetDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = adjustedMatrix,
        )
        publishOrientation(accuracy = 3)
    }

    fun start() {
        if (isListening || sensorManager == null || rotationSensor == null) return
        isFirstSample = true
        // A new session: the game vector's north restarts and the user has not aligned anything yet.
        headingLock.reset()
        headingOffsetDeg = 0f
        coarseHeadingOffsetDeg = 0f
        headingUncertaintyDeg = Float.NaN
        hasGameSample = false
        hasCompassSample = false
        lastPairedTimestampNs = Long.MIN_VALUE
        publishHeadingState()
        if (dualMode) {
            sensorManager.registerListener(this, gameSensor, SAMPLING_PERIOD_US)
        }
        sensorManager.registerListener(this, rotationSensor, SAMPLING_PERIOD_US)
        isListening = true
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
        isFirstSample = true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return
        if (dualMode && event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            readQuaternion(event.values, latestGameQuat)
            latestGameTimestampNs = event.timestamp
            hasGameSample = true
            updateHeading()
            return
        }

        readQuaternion(event.values, sampleQuat)
        val qx = sampleQuat[0]
        val qy = sampleQuat[1]
        val qz = sampleQuat[2]
        val qw = sampleQuat[3]

        ringBuffer.push(event.timestamp, qx, qy, qz, qw)
        val previousSampleNs = lastRotationSampleNs
        lastRotationSampleNs = event.timestamp
        // Game events have returned above; what is left is the rotation sensor, and only a vector
        // with a magnetometer in it has a heading uncertainty to report.
        if (rotationSensor?.type != Sensor.TYPE_GAME_ROTATION_VECTOR) {
            headingUncertaintyDeg = HeadingLock.headingUncertaintyDegrees(event.values)
            if (!dualMode) publishHeadingState()
        }
        if (dualMode) {
            System.arraycopy(sampleQuat, 0, latestCompassQuat, 0, 4)
            latestCompassTimestampNs = event.timestamp
            hasCompassSample = true
            compassAccuracy = event.accuracy
            updateHeading()
        }

        if (isFirstSample) {
            smoothedQuat[0] = qx
            smoothedQuat[1] = qy
            smoothedQuat[2] = qz
            smoothedQuat[3] = qw
            isFirstSample = false
        } else {
            // Adaptive SLERP alpha
            val dot = smoothedQuat[0] * qx + smoothedQuat[1] * qy + smoothedQuat[2] * qz + smoothedQuat[3] * qw
            val sign = if (dot < 0f) -1f else 1f
            val absDot = (dot * sign).coerceIn(0f, 1f)
            val deltaAngleRad = 2.0 * kotlin.math.acos(absDot.toDouble())
            val t = ((deltaAngleRad - 0.002) / (0.025 - 0.002)).toFloat().coerceIn(0f, 1f)
            val alphaPerReference = ALPHA_STATIONARY + t * (ALPHA_MOVING - ALPHA_STATIONARY)
            // The alphas were tuned per sample at the 20 ms of SENSOR_DELAY_GAME. Scaled to the
            // actual interval, the live pose keeps the same time constant at the faster rate the
            // ring buffer needs.
            val dtNs = (event.timestamp - previousSampleNs).coerceIn(1L, ALPHA_REFERENCE_INTERVAL_NS * 5)
            val currentAlpha = 1f - Math.pow(
                (1f - alphaPerReference).toDouble(),
                dtNs.toDouble() / ALPHA_REFERENCE_INTERVAL_NS,
            ).toFloat()

            smoothedQuat[0] += currentAlpha * (qx * sign - smoothedQuat[0])
            smoothedQuat[1] += currentAlpha * (qy * sign - smoothedQuat[1])
            smoothedQuat[2] += currentAlpha * (qz * sign - smoothedQuat[2])
            smoothedQuat[3] += currentAlpha * (qw * sign - smoothedQuat[3])

            // Re-normalize quaternion
            val norm = sqrt(
                smoothedQuat[0] * smoothedQuat[0] +
                smoothedQuat[1] * smoothedQuat[1] +
                smoothedQuat[2] * smoothedQuat[2] +
                smoothedQuat[3] * smoothedQuat[3]
            )
            if (norm > 0.0001f) {
                smoothedQuat[0] /= norm
                smoothedQuat[1] /= norm
                smoothedQuat[2] /= norm
                smoothedQuat[3] /= norm
            }
        }

        // Convert unit quaternion to guaranteed 100% orthonormal rotation matrix (sensor ENU)
        SensorManager.getRotationMatrixFromVector(rawMatrix, smoothedQuat)

        // Compute live True North matrix, with the standing calibration folded in. Both are rotations
        // of the world frame, so they compose into the single offset applyCalibration already takes.
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = northCorrectionDeg(),
            panAzimuthOffsetDeg = calibrationAzimuthOffsetDeg,
            panPitchOffsetDeg = calibrationPitchOffsetDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = liveTrueMatrix,
        )

        // If not paused, update frozen snapshot and publish live orientation
        if (!isSensorTrackingPaused.value) {
            System.arraycopy(liveTrueMatrix, 0, frozenMatrix, 0, 16)
            System.arraycopy(liveTrueMatrix, 0, adjustedMatrix, 0, 16)
            if (publishDue(event.timestamp, lastPublishedSampleNs)) {
                lastPublishedSampleNs = event.timestamp
                publishOrientation(accuracy = event.accuracy)
            }
        }
    }

    /**
     * ψ between the rotation vector and the game vector, and from it the correction a lock applies.
     *
     * Only a pair taken at the same instant is used: on the Pixel both streams carry identical
     * timestamps but arrive in either order, and pairing a sample with the other stream's previous
     * one would turn the phone's own motion during those 17 ms into a heading error. So this runs
     * from both handlers and acts once, when the second half of a pair arrives.
     */
    private fun updateHeading() {
        if (hasGameSample && hasCompassSample &&
            latestCompassTimestampNs != lastPairedTimestampNs &&
            kotlin.math.abs(latestCompassTimestampNs - latestGameTimestampNs) <= MAX_GAME_SKEW_NS
        ) {
            lastPairedTimestampNs = latestCompassTimestampNs
            SensorManager.getRotationMatrixFromVector(compassMatrix, latestCompassQuat)
            SensorManager.getRotationMatrixFromVector(gameMatrix, latestGameQuat)
            headingLock.addCompass(YawOffset.extractDegrees(compassMatrix, gameMatrix))
            headingOffsetDeg = headingLock.correctionDegrees()
        }
        publishHeadingState()
    }

    private fun publishHeadingState() {
        headingStateFlow.value = headingLock.state(compassAccuracy, headingUncertaintyDeg, MAX_CALIBRATION_AZIMUTH)
    }

    /** Everything that turns the rotation vector's north into true north, except the calibration. */
    private fun northCorrectionDeg(): Float = magneticDeclinationDeg + headingOffsetDeg + coarseHeadingOffsetDeg

    private fun readQuaternion(values: FloatArray, out: FloatArray) {
        out[0] = values[0]
        out[1] = values[1]
        out[2] = values[2]
        out[3] = if (values.size >= 4) {
            values[3]
        } else {
            val s = 1.0f - (out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            if (s > 0f) sqrt(s) else 0f
        }
    }

    private fun publishOrientation(accuracy: Int) {
        val camFwdX = -adjustedMatrix[2] // East component
        val camFwdY = -adjustedMatrix[6] // North component
        val camFwdZ = -adjustedMatrix[10] // Up component

        var camAzDeg = (Math.toDegrees(atan2(camFwdX.toDouble(), camFwdY.toDouble()))).toFloat()
        if (camAzDeg < 0f) camAzDeg += 360f
        if (camAzDeg >= 360f) camAzDeg -= 360f

        val camElDeg = (Math.toDegrees(asin(camFwdZ.coerceIn(-1f, 1f).toDouble()))).toFloat()
        val rollDeg = (Math.toDegrees(atan2(adjustedMatrix[1].toDouble(), adjustedMatrix[5].toDouble()))).toFloat()

        System.arraycopy(adjustedMatrix, 0, publishedMatrix, 0, 16)

        stateFlow.value = DeviceOrientation(
            rotationMatrix = publishedMatrix,
            cameraAzimuthDegrees = camAzDeg,
            cameraElevationDegrees = camElDeg,
            cameraRollDegrees = rollDeg,
            accuracy = accuracy,
        )
    }

    companion object {
        const val MAX_GESTURE_PITCH = 85f

        /** How far past the newest sensor sample a [getReferenceMatrix] instant may lie: two sample periods. */
        private const val REFERENCE_MAX_EXTRAPOLATION_NS = 10_000_000L

        /** How far apart a rotation vector and a game vector sample may be to count as one instant. */
        private const val MAX_GAME_SKEW_NS = 2_000_000L

        /**
         * How far the standing calibration may tilt the world frame.
         *
         * A compass and a rotation vector are wrong by degrees, not by tens of them. A larger value
         * would only ever be a mis-drag saved by accident, and it would be indistinguishable from a
         * broken sensor the next time the screen opened.
         */
        const val MAX_CALIBRATION_PITCH = 20f

        /** The same bound for the compass correction. See [MAX_CALIBRATION_PITCH]. */
        const val MAX_CALIBRATION_AZIMUTH = 20f

        /** An azimuth expressed in (-180, 180] — the short way round, which is what a bound needs. */
        fun signedAzimuth(azimuthDeg: Float): Float {
            val wrapped = ((azimuthDeg % 360f) + 360f) % 360f
            return if (wrapped > 180f) wrapped - 360f else wrapped
        }
        const val ALPHA_STATIONARY = 0.30f
        const val ALPHA_MOVING = 0.85f

        /** The sample interval [ALPHA_STATIONARY] and [ALPHA_MOVING] were tuned at. */
        private const val ALPHA_REFERENCE_INTERVAL_NS = 20_000_000L

        /**
         * 200 Hz. The synchronized pose interpolates between samples at a camera frame's instant;
         * at the 50 Hz of SENSOR_DELAY_GAME a fast turn moved several tenths of a degree between
         * two of them.
         */
        private const val SAMPLING_PERIOD_US = 5_000

        /**
         * The fastest [orientation] is emitted: about a display frame. The ring buffer and the render
         * matrices take every 200 Hz sample, but the flow only feeds the HUD text and the screens'
         * "have we a pose" checks, and the screens read it at the top of their composition, so every
         * emission is an allocation and a recomposition. At the sensor rate that is a few times
         * the display's own, none of which can be seen.
         */
        internal const val PUBLISH_INTERVAL_NS = 15_000_000L

        /** Whether the sample at [timestampNs] should be published, [lastPublishedNs] being the previous one (0 = none yet). */
        internal fun publishDue(timestampNs: Long, lastPublishedNs: Long): Boolean =
            lastPublishedNs == 0L || timestampNs < lastPublishedNs || timestampNs - lastPublishedNs >= PUBLISH_INTERVAL_NS

        /**
         * Extracts camera optical pitch / elevation in degrees [-90.0, +90.0] from a rotation matrix.
         */
        fun extractPitchFromMatrix(matrix: FloatArray): Float {
            val fwdZ = if (matrix.size >= 16) -matrix[10] else if (matrix.size >= 9) -matrix[8] else 0f
            return Math.toDegrees(asin(fwdZ.coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        /**
         * Rotates a 4x4 rotation matrix about the world Z (Up) axis by [declinationDeg].
         */
        fun applyMagneticDeclination(
            sensorMatrix: FloatArray,
            declinationDeg: Float,
            outMatrix: FloatArray,
        ) {
            if (kotlin.math.abs(declinationDeg) < 0.0001f) {
                System.arraycopy(sensorMatrix, 0, outMatrix, 0, minOf(sensorMatrix.size, outMatrix.size))
                return
            }

            val decRad = Math.toRadians(declinationDeg.toDouble())
            val cosD = kotlin.math.cos(decRad).toFloat()
            val sinD = kotlin.math.sin(decRad).toFloat()

            val r0 = cosD * sensorMatrix[0] + sinD * sensorMatrix[4]
            val r1 = cosD * sensorMatrix[1] + sinD * sensorMatrix[5]
            val r2 = cosD * sensorMatrix[2] + sinD * sensorMatrix[6]
            val r3 = cosD * sensorMatrix[3] + sinD * sensorMatrix[7]

            val r4 = -sinD * sensorMatrix[0] + cosD * sensorMatrix[4]
            val r5 = -sinD * sensorMatrix[1] + cosD * sensorMatrix[5]
            val r6 = -sinD * sensorMatrix[2] + cosD * sensorMatrix[6]
            val r7 = -sinD * sensorMatrix[3] + cosD * sensorMatrix[7]

            outMatrix[0] = r0
            outMatrix[1] = r1
            outMatrix[2] = r2
            outMatrix[3] = r3

            outMatrix[4] = r4
            outMatrix[5] = r5
            outMatrix[6] = r6
            outMatrix[7] = r7

            outMatrix[8] = sensorMatrix[8]
            outMatrix[9] = sensorMatrix[9]
            outMatrix[10] = sensorMatrix[10]
            outMatrix[11] = sensorMatrix[11]

            outMatrix[12] = sensorMatrix[12]
            outMatrix[13] = sensorMatrix[13]
            outMatrix[14] = sensorMatrix[14]
            outMatrix[15] = sensorMatrix[15]
        }

        /**
         * Applies True-North declination (World Z), user azimuth offset (World Z),
         * and user local pitch offset (Camera X) with zero heap allocations on the 60 FPS loop.
         */
        fun applyCalibration(
            sensorMatrix: FloatArray,
            declinationDeg: Float,
            panAzimuthOffsetDeg: Float,
            panPitchOffsetDeg: Float,
            tempDeclinationBuffer: FloatArray,
            outMatrix: FloatArray,
        ) {
            val totalAzimuthOffset = declinationDeg + panAzimuthOffsetDeg
            applyMagneticDeclination(sensorMatrix, totalAzimuthOffset, tempDeclinationBuffer)

            if (kotlin.math.abs(panPitchOffsetDeg) < 1e-4f) {
                System.arraycopy(tempDeclinationBuffer, 0, outMatrix, 0, minOf(tempDeclinationBuffer.size, outMatrix.size))
                return
            }

            val pitchRad = Math.toRadians(panPitchOffsetDeg.toDouble())
            val cosP = kotlin.math.cos(pitchRad).toFloat()
            val sinP = kotlin.math.sin(pitchRad).toFloat()

            // Col 0 (+X_dev) unchanged
            outMatrix[0] = tempDeclinationBuffer[0]
            outMatrix[4] = tempDeclinationBuffer[4]
            outMatrix[8] = tempDeclinationBuffer[8]
            outMatrix[12] = tempDeclinationBuffer[12]

            // Col 1 (+Y_dev) = cosP * Col 1 + sinP * Col 2
            outMatrix[1] = cosP * tempDeclinationBuffer[1] + sinP * tempDeclinationBuffer[2]
            outMatrix[5] = cosP * tempDeclinationBuffer[5] + sinP * tempDeclinationBuffer[6]
            outMatrix[9] = cosP * tempDeclinationBuffer[9] + sinP * tempDeclinationBuffer[10]
            outMatrix[13] = cosP * tempDeclinationBuffer[13] + sinP * tempDeclinationBuffer[14]

            // Col 2 (+Z_dev) = -sinP * Col 1 + cosP * Col 2
            outMatrix[2] = -sinP * tempDeclinationBuffer[1] + cosP * tempDeclinationBuffer[2]
            outMatrix[6] = -sinP * tempDeclinationBuffer[5] + cosP * tempDeclinationBuffer[6]
            outMatrix[10] = -sinP * tempDeclinationBuffer[9] + cosP * tempDeclinationBuffer[10]
            outMatrix[14] = -sinP * tempDeclinationBuffer[13] + cosP * tempDeclinationBuffer[14]

            // Col 3 = [0, 0, 0, 1]
            outMatrix[3] = 0f
            outMatrix[7] = 0f
            outMatrix[11] = 0f
            outMatrix[15] = 1f
        }

        /**
         * Computes dynamic adaptive SLERP alpha.
         */
        fun computeAdaptiveAlpha(
            currentQuat: FloatArray,
            targetQx: Float,
            targetQy: Float,
            targetQz: Float,
            targetQw: Float,
            minAlpha: Float = ALPHA_STATIONARY,
            maxAlpha: Float = ALPHA_MOVING,
        ): Float {
            val dot = currentQuat[0] * targetQx + currentQuat[1] * targetQy +
                currentQuat[2] * targetQz + currentQuat[3] * targetQw
            val absDot = kotlin.math.abs(dot).coerceIn(0f, 1f)
            val deltaAngleRad = 2.0 * kotlin.math.acos(absDot.toDouble())
            val t = ((deltaAngleRad - 0.002) / (0.025 - 0.002)).toFloat().coerceIn(0f, 1f)
            return minAlpha + t * (maxAlpha - minAlpha)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (dualMode) {
            // The game vector has no magnetometer; only the compass stream's accuracy means anything.
            if (sensor?.type == Sensor.TYPE_GAME_ROTATION_VECTOR) return
            compassAccuracy = accuracy
            publishHeadingState()
        }
        val current = stateFlow.value
        if (current != null) {
            stateFlow.value = current.copy(accuracy = accuracy)
        }
    }
}

package com.dazix.intervalometer.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dazix.intervalometer.domain.solar.GeoLocation
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

    var panAzimuthOffsetDeg: Float = 0f
        private set
    var panPitchOffsetDeg: Float = 0f
        private set

    val isSensorTrackingPaused = MutableStateFlow(false)

    private val stateFlow = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = stateFlow.asStateFlow()

    private var isListening = false
    private var isFirstSample = true

    private var magneticDeclinationDeg = 0.0f

    private var targetAnimator: android.animation.ValueAnimator? = null

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
        targetAnimator?.cancel()
        targetAnimator = null
        panAzimuthOffsetDeg = 0f
        panPitchOffsetDeg = 0f
        isSensorTrackingPaused.value = false

        // Immediately update orientation to live sensor values
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = magneticDeclinationDeg,
            panAzimuthOffsetDeg = 0f,
            panPitchOffsetDeg = 0f,
            tempDeclinationBuffer = tempDeclinationMatrix,
            outMatrix = adjustedMatrix,
        )
        publishOrientation(accuracy = 3)
    }

    /**
     * Smoothly rotates the virtual camera in Hybrid Alt-Azimuth mode to center on [targetAzimuthDeg]
     * and [targetElevationDeg] over 800ms using shortest spherical angular interpolation.
     */
    fun animateToTarget(
        targetAzimuthDeg: Float,
        targetElevationDeg: Float,
        currentFrozenMatrix: FloatArray = if (isSensorTrackingPaused.value) frozenMatrix else liveTrueMatrix,
        onUpdate: ((Float, Float) -> Unit)? = null,
    ) {
        targetAnimator?.cancel()

        // 1. FREEZE SENSORS IF NOT PAUSED
        if (!isSensorTrackingPaused.value) {
            isSensorTrackingPaused.value = true
            System.arraycopy(currentFrozenMatrix, 0, frozenMatrix, 0, minOf(currentFrozenMatrix.size, 16))
        }

        // 2. EXTRACT CAMERA FORWARD VECTOR FROM FROZEN MATRIX
        val camFwdX = -frozenMatrix[2]
        val camFwdY = -frozenMatrix[6]
        val camFwdZ = -frozenMatrix[10]

        // 3. CONVERT TO BASE AZIMUTH & ELEVATION
        val camAzRad = kotlin.math.atan2(camFwdX.toDouble(), camFwdY.toDouble())
        var camAzDeg = Math.toDegrees(camAzRad).toFloat()
        if (camAzDeg < 0f) camAzDeg += 360f
        val camElDeg = Math.toDegrees(kotlin.math.asin(camFwdZ.coerceIn(-1f, 1f).toDouble())).toFloat()

        // 4. CALCULATE TARGET OFFSETS RELATIVE TO FROZEN BASE MATRIX
        var targetOffsetAz = targetAzimuthDeg - camAzDeg
        if (targetOffsetAz > 180f) targetOffsetAz -= 360f
        if (targetOffsetAz < -180f) targetOffsetAz += 360f
        val targetOffsetPitch = (targetElevationDeg - camElDeg).coerceIn(-MAX_GESTURE_PITCH, MAX_GESTURE_PITCH)

        // 5. CALCULATE SHORTEST PATH FROM CURRENT OFFSETS
        val startAzOffset = panAzimuthOffsetDeg
        val startPitchOffset = panPitchOffsetDeg

        var diffAz = targetOffsetAz - startAzOffset
        if (diffAz > 180f) diffAz -= 360f
        if (diffAz < -180f) diffAz += 360f
        val diffPitch = targetOffsetPitch - startPitchOffset

        // 6. ANIMATE VIA VALUEANIMATOR OVER 800MS
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val newAz = startAzOffset + diffAz * fraction
                panAzimuthOffsetDeg = (newAz % 360f + 360f) % 360f
                panPitchOffsetDeg = (startPitchOffset + diffPitch * fraction).coerceIn(-MAX_GESTURE_PITCH, MAX_GESTURE_PITCH)

                applyCalibration(
                    sensorMatrix = frozenMatrix,
                    declinationDeg = 0.0f,
                    panAzimuthOffsetDeg = panAzimuthOffsetDeg,
                    panPitchOffsetDeg = panPitchOffsetDeg,
                    tempDeclinationBuffer = tempDeclinationMatrix,
                    outMatrix = adjustedMatrix,
                )
                publishOrientation(accuracy = 3)
                onUpdate?.invoke(panAzimuthOffsetDeg, panPitchOffsetDeg)
            }
        }
        targetAnimator = animator
        animator.start()
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
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        isListening = true
    }

    fun stop() {
        targetAnimator?.cancel()
        targetAnimator = null
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
        isFirstSample = true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return

        val qx = event.values[0]
        val qy = event.values[1]
        val qz = event.values[2]
        val qw = if (event.values.size >= 4) {
            event.values[3]
        } else {
            val s = 1.0f - (qx * qx + qy * qy + qz * qz)
            if (s > 0f) sqrt(s) else 0f
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
            val currentAlpha = ALPHA_STATIONARY + t * (ALPHA_MOVING - ALPHA_STATIONARY)

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

        // Compute live True North matrix
        applyMagneticDeclination(rawMatrix, magneticDeclinationDeg, liveTrueMatrix)

        // If not paused, update frozen snapshot and publish live orientation
        if (!isSensorTrackingPaused.value) {
            System.arraycopy(liveTrueMatrix, 0, frozenMatrix, 0, 16)
            System.arraycopy(liveTrueMatrix, 0, adjustedMatrix, 0, 16)
            publishOrientation(accuracy = event.accuracy)
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

        stateFlow.value = DeviceOrientation(
            rotationMatrix = adjustedMatrix.clone(),
            cameraAzimuthDegrees = camAzDeg,
            cameraElevationDegrees = camElDeg,
            cameraRollDegrees = rollDeg,
            accuracy = accuracy,
        )
    }

    companion object {
        const val MAX_GESTURE_PITCH = 85f
        const val ALPHA_STATIONARY = 0.30f
        const val ALPHA_MOVING = 0.85f

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
        val current = stateFlow.value
        if (current != null) {
            stateFlow.value = current.copy(accuracy = accuracy)
        }
    }
}

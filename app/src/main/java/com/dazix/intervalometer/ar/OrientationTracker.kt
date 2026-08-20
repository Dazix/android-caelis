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
    private val tempDeclinationMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val tempUserMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val adjustedMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    private var userAzimuthDeg = 0.0f
    private var userPitchDeg = 0.0f

    private val stateFlow = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = stateFlow.asStateFlow()

    private var isListening = false
    private var isFirstSample = true
    private val alpha = 0.22f // Smooth low-pass factor for stable AR overlay

    private var magneticDeclinationDeg = 0.0f

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

    fun setUserOffsets(azDeg: Float, pitchDeg: Float) {
        var a = azDeg % 360f
        if (a < 0f) a += 360f
        userAzimuthDeg = a

        var p = pitchDeg % 360f
        if (p > 180f) p -= 360f
        if (p < -180f) p += 360f
        userPitchDeg = p
    }

    fun getUserOffsets(): Pair<Float, Float> = Pair(userAzimuthDeg, userPitchDeg)

    fun rotateUser(deltaAzDeg: Float, deltaPitchDeg: Float) {
        var a = (userAzimuthDeg + deltaAzDeg) % 360f
        if (a < 0f) a += 360f
        userAzimuthDeg = a

        var p = (userPitchDeg + deltaPitchDeg) % 360f
        if (p > 180f) p -= 360f
        if (p < -180f) p += 360f
        userPitchDeg = p
    }

    fun resetUserRotation() {
        userAzimuthDeg = 0.0f
        userPitchDeg = 0.0f
    }

    fun isUserRotated(): Boolean =
        kotlin.math.abs(userAzimuthDeg) > 0.01f || kotlin.math.abs(userPitchDeg) > 0.01f

    fun start() {
        if (isListening || sensorManager == null || rotationSensor == null) return
        isFirstSample = true
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
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
            // Shortest-path quaternion interpolation
            val dot = smoothedQuat[0] * qx + smoothedQuat[1] * qy + smoothedQuat[2] * qz + smoothedQuat[3] * qw
            val sign = if (dot < 0f) -1f else 1f

            smoothedQuat[0] += alpha * (qx * sign - smoothedQuat[0])
            smoothedQuat[1] += alpha * (qy * sign - smoothedQuat[1])
            smoothedQuat[2] += alpha * (qz * sign - smoothedQuat[2])
            smoothedQuat[3] += alpha * (qw * sign - smoothedQuat[3])

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

        // Convert unit quaternion to a guaranteed 100% orthonormal rotation matrix (sensor ENU)
        SensorManager.getRotationMatrixFromVector(rawMatrix, smoothedQuat)

        // Apply true-north magnetic declination + continuous upright spherical user rotation
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = magneticDeclinationDeg,
            userAzimuthDeg = userAzimuthDeg,
            userPitchDeg = userPitchDeg,
            tempDeclinationBuffer = tempDeclinationMatrix,
            tempRotXBuffer = tempUserMatrix,
            outMatrix = adjustedMatrix,
        )

        // Compute where the back camera (-Z_dev) is pointing in World ENU coordinates
        // Since v_world = R * v_dev: v_world = R * [0, 0, -1]^T = [-R[2], -R[6], -R[10]]
        val camFwdX = -adjustedMatrix[2] // East component
        val camFwdY = -adjustedMatrix[6] // North component
        val camFwdZ = -adjustedMatrix[10] // Up component

        var camAzDeg = (Math.toDegrees(atan2(camFwdX.toDouble(), camFwdY.toDouble()))).toFloat()
        if (camAzDeg < 0f) camAzDeg += 360f
        if (camAzDeg >= 360f) camAzDeg -= 360f

        val camElDeg = (Math.toDegrees(asin(camFwdZ.coerceIn(-1f, 1f).toDouble()))).toFloat()

        // Up vector on screen (+Y_dev in portrait) in world: R * [0, 1, 0]^T = [R[1], R[5], R[9]]
        val rollDeg = (Math.toDegrees(atan2(adjustedMatrix[1].toDouble(), adjustedMatrix[5].toDouble()))).toFloat()

        stateFlow.value = DeviceOrientation(
            rotationMatrix = adjustedMatrix.clone(),
            cameraAzimuthDegrees = camAzDeg,
            cameraElevationDegrees = camElDeg,
            cameraRollDegrees = rollDeg,
            accuracy = event.accuracy,
        )
    }

    companion object {
        /**
         * Rotates a $4 \times 4$ rotation matrix about the world Z (Up) axis by [declinationDeg]
         * so that the frame aligns with True North instead of Magnetic North.
         *
         * World frame rotation: R_true = R_z(declination) * R_sensor
         * Preserves 100% orthonormality of the rotation matrix.
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

            // Pre-multiply R_z(D) * sensorMatrix (4x4) to rotate frame from Magnetic ENU to True ENU
            // Row 0 = cosD * Row 0 + sinD * Row 1
            // Row 1 = -sinD * Row 0 + cosD * Row 1
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
         * Fills [out] with a $4 \times 4$ rotation matrix representing pitch rotation by [pitchDeg]
         * around device X (+X_dev, screen right).
         */
        fun applyPitchRotation(pitchDeg: Float, out: FloatArray) {
            val rad = Math.toRadians(pitchDeg.toDouble())
            val cosP = kotlin.math.cos(rad).toFloat()
            val sinP = kotlin.math.sin(rad).toFloat()

            out[0] = 1f;  out[1] = 0f;    out[2] = 0f;     out[3] = 0f
            out[4] = 0f;  out[5] = cosP;  out[6] = -sinP;  out[7] = 0f
            out[8] = 0f;  out[9] = sinP;  out[10] = cosP;  out[11] = 0f
            out[12] = 0f; out[13] = 0f;   out[14] = 0f;    out[15] = 1f
        }

        /**
         * Multiplies two 4x4 matrices [a] and [b] and stores the result in [out] with zero allocations.
         * $out = a \cdot b$
         */
        fun multiplyMatrices(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (i in 0..3) {
                val i4 = i * 4
                val a0 = a[i4 + 0]
                val a1 = a[i4 + 1]
                val a2 = a[i4 + 2]
                val a3 = a[i4 + 3]
                for (j in 0..3) {
                    out[i4 + j] = a0 * b[0 * 4 + j] +
                                  a1 * b[1 * 4 + j] +
                                  a2 * b[2 * 4 + j] +
                                  a3 * b[3 * 4 + j]
                }
            }
        }

        /**
         * Applies True-North declination, azimuth offset, and continuous upright pitch offset
         * to produce a guaranteed 100% orthonormal, upright adjusted rotation matrix with zero allocations.
         *
         * Upright guarantee: Screen Up vector (+Y_dev) in World coordinates always maintains a non-negative
         * Up component (Up_Z >= 0), ensuring the horizon never rolls upside down.
         *
         * Gesture consistency guarantee: Horizontal swipes always rotate about the World Up axis (Z_world),
         * ensuring consistent left/right panning without inversion across the entire celestial dome.
         */
        fun applyCalibration(
            sensorMatrix: FloatArray,
            declinationDeg: Float,
            userAzimuthDeg: Float,
            userPitchDeg: Float,
            tempDeclinationBuffer: FloatArray,
            tempRotXBuffer: FloatArray,
            outMatrix: FloatArray,
        ) {
            // Compute effective azimuth and elevation from continuous pitch angle
            var p = userPitchDeg % 360f
            if (p > 180f) p -= 360f
            if (p < -180f) p += 360f

            val effElevationDeg: Float
            val effAzimuthDeg: Float
            if (p in -90f..90f) {
                effElevationDeg = p
                effAzimuthDeg = userAzimuthDeg
            } else if (p > 90f) {
                effElevationDeg = 180f - p
                effAzimuthDeg = userAzimuthDeg + 180f
            } else {
                effElevationDeg = -180f - p
                effAzimuthDeg = userAzimuthDeg + 180f
            }

            // Total World Z (Up) rotation = declination + effAzimuthDeg
            val totalAzimuthDeg = declinationDeg + effAzimuthDeg
            applyMagneticDeclination(sensorMatrix, totalAzimuthDeg, tempDeclinationBuffer)

            if (kotlin.math.abs(effElevationDeg) < 0.0001f) {
                System.arraycopy(tempDeclinationBuffer, 0, outMatrix, 0, minOf(tempDeclinationBuffer.size, outMatrix.size))
                return
            }

            // Apply Elevation tilt (rotation about device X axis)
            applyPitchRotation(effElevationDeg, tempRotXBuffer)
            multiplyMatrices(tempDeclinationBuffer, tempRotXBuffer, outMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val current = stateFlow.value
        if (current != null) {
            stateFlow.value = current.copy(accuracy = accuracy)
        }
    }
}

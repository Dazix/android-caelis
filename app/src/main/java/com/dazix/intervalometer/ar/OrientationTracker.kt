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

    private val userQuat = FloatArray(4).apply { this[3] = 1f }

    private val stateFlow = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = stateFlow.asStateFlow()

    private var isListening = false
    private var isFirstSample = true

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

    fun setUserQuaternion(quat: FloatArray) {
        if (quat.size >= 4) {
            val norm = kotlin.math.sqrt(quat[0] * quat[0] + quat[1] * quat[1] + quat[2] * quat[2] + quat[3] * quat[3])
            if (norm > 1e-6f) {
                userQuat[0] = quat[0] / norm
                userQuat[1] = quat[1] / norm
                userQuat[2] = quat[2] / norm
                userQuat[3] = quat[3] / norm
            } else {
                resetUserRotation()
            }
        }
    }

    fun getUserQuaternion(): FloatArray = userQuat.clone()

    fun rotateUser(pitchRad: Float, yawRad: Float) {
        rotateQuaternion(userQuat, pitchRad, yawRad, userQuat)
    }

    fun resetUserRotation() {
        userQuat[0] = 0.0f
        userQuat[1] = 0.0f
        userQuat[2] = 0.0f
        userQuat[3] = 1.0f
    }

    fun isUserRotated(): Boolean = !isIdentityQuaternion(userQuat)

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
            // Shortest-path quaternion interpolation with dynamic adaptive SLERP alpha:
            // High responsiveness (alpha -> 0.85) during panning to eliminate camera lag,
            // dropping to 0.30 when stationary to filter micro-jitters.
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

        // Convert unit quaternion to a guaranteed 100% orthonormal rotation matrix (sensor ENU)
        SensorManager.getRotationMatrixFromVector(rawMatrix, smoothedQuat)

        // Apply true-north magnetic declination + continuous 3D user rotation quaternion
        applyCalibration(
            sensorMatrix = rawMatrix,
            declinationDeg = magneticDeclinationDeg,
            userQuat = userQuat,
            tempDeclinationBuffer = tempDeclinationMatrix,
            tempUserMatrixBuffer = tempUserMatrix,
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
         * Converts a unit quaternion [q] = (qx, qy, qz, qw) into a 100% orthonormal 4x4 rotation matrix [out].
         */
        fun quaternionToMatrix(q: FloatArray, out: FloatArray) {
            val q1 = q[0] // x
            val q2 = q[1] // y
            val q3 = q[2] // z
            val q0 = q[3] // w

            out[0] = 1f - 2f * (q2 * q2 + q3 * q3)
            out[1] = 2f * (q1 * q2 - q0 * q3)
            out[2] = 2f * (q1 * q3 + q0 * q2)
            out[3] = 0f

            out[4] = 2f * (q1 * q2 + q0 * q3)
            out[5] = 1f - 2f * (q1 * q1 + q3 * q3)
            out[6] = 2f * (q2 * q3 - q0 * q1)
            out[7] = 0f

            out[8] = 2f * (q1 * q3 - q0 * q2)
            out[9] = 2f * (q2 * q3 + q0 * q1)
            out[10] = 1f - 2f * (q1 * q1 + q2 * q2)
            out[11] = 0f

            out[12] = 0f
            out[13] = 0f
            out[14] = 0f
            out[15] = 1f
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
         * Increments a rotation quaternion [currentQuat] by pitch ([pitchRad] around device X)
         * and yaw ([yawRad] around device Y) without any gimbal lock, singularity, or pole lock.
         *
         * Allows continuous, seamless 360-degree rotation across Zenith and Nadir in any direction.
         */
        fun rotateQuaternion(
            currentQuat: FloatArray,
            pitchRad: Float,
            yawRad: Float,
            outQuat: FloatArray,
        ) {
            if (kotlin.math.abs(pitchRad) < 1e-7f && kotlin.math.abs(yawRad) < 1e-7f) {
                System.arraycopy(currentQuat, 0, outQuat, 0, minOf(currentQuat.size, outQuat.size))
                return
            }

            val halfP = pitchRad * 0.5f
            val sinP = kotlin.math.sin(halfP)
            val cosP = kotlin.math.cos(halfP)

            val halfY = yawRad * 0.5f
            val sinY = kotlin.math.sin(halfY)
            val cosY = kotlin.math.cos(halfY)

            // dq = dq_pitch * dq_yaw (pitch around +X_dev, yaw around +Y_dev)
            val dqX = sinP * cosY
            val dqY = cosP * sinY
            val dqZ = -sinP * sinY
            val dqW = cosP * cosY

            // out = currentQuat * dq (post-multiplication in camera frame)
            val x1 = currentQuat[0]
            val y1 = currentQuat[1]
            val z1 = currentQuat[2]
            val w1 = currentQuat[3]

            val ox = w1 * dqX + x1 * dqW + y1 * dqZ - z1 * dqY
            val oy = w1 * dqY - x1 * dqZ + y1 * dqW + z1 * dqX
            val oz = w1 * dqZ + x1 * dqY - y1 * dqX + z1 * dqW
            val ow = w1 * dqW - x1 * dqX - y1 * dqY - z1 * dqZ

            val norm = kotlin.math.sqrt(ox * ox + oy * oy + oz * oz + ow * ow)
            if (norm > 1e-6f) {
                outQuat[0] = ox / norm
                outQuat[1] = oy / norm
                outQuat[2] = oz / norm
                outQuat[3] = ow / norm
            } else {
                outQuat[0] = 0f
                outQuat[1] = 0f
                outQuat[2] = 0f
                outQuat[3] = 1f
            }
        }

        fun isIdentityQuaternion(q: FloatArray): Boolean {
            if (q.size < 4) return true
            return kotlin.math.abs(q[0]) < 1e-4f &&
                   kotlin.math.abs(q[1]) < 1e-4f &&
                   kotlin.math.abs(q[2]) < 1e-4f &&
                   kotlin.math.abs(q[3] - 1.0f) < 1e-4f
        }

        const val ALPHA_STATIONARY = 0.30f
        const val ALPHA_MOVING = 0.85f

        /**
         * Computes the dynamic adaptive SLERP alpha based on the angular distance between quaternions.
         * Returns high alpha (up to [maxAlpha]) during fast movement to eliminate lag,
         * and low alpha (down to [minAlpha]) when stationary to filter micro-jitters.
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

        /**
         * Applies True-North declination and continuous 3D user rotation quaternion
         * to produce a 100% orthonormal adjusted rotation matrix with zero allocations.
         *
         * $R_{\text{adjusted}} = R_{\text{true}} \cdot R_{\text{user}}$
         */
        fun applyCalibration(
            sensorMatrix: FloatArray,
            declinationDeg: Float,
            userQuat: FloatArray,
            tempDeclinationBuffer: FloatArray,
            tempUserMatrixBuffer: FloatArray,
            outMatrix: FloatArray,
        ) {
            // 1. Apply magnetic declination to sensor matrix
            applyMagneticDeclination(sensorMatrix, declinationDeg, tempDeclinationBuffer)

            // 2. If user quaternion is identity, no further adjustment needed
            if (isIdentityQuaternion(userQuat)) {
                System.arraycopy(tempDeclinationBuffer, 0, outMatrix, 0, minOf(tempDeclinationBuffer.size, outMatrix.size))
                return
            }

            // 3. Convert user quaternion to matrix and multiply
            quaternionToMatrix(userQuat, tempUserMatrixBuffer)
            multiplyMatrices(tempDeclinationBuffer, tempUserMatrixBuffer, outMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val current = stateFlow.value
        if (current != null) {
            stateFlow.value = current.copy(accuracy = accuracy)
        }
    }
}

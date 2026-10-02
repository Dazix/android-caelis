package com.dazix.caelis.domain.arframe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turning ARCore's world into ENU.
 *
 * ARCore's world is gravity-aligned (+Y up) with an arbitrary heading fixed at session start. Read
 * as East-Up-South at heading 0, it becomes ENU after a single rotation about Up by an unknown yaw
 * `psi`:
 *
 *     deviceToEnu = Rz(psi) * EUS_TO_ENU * deviceToArWorld
 *
 * where `deviceToArWorld` comes from `Frame.getAndroidSensorPose()` (device axes, not affected by
 * display rotation — the same axes `SensorManager` uses). Everything here estimates `psi` from a
 * frame that knows north (the compass tracker, or Geospatial's EUS orientation of the same camera),
 * and builds the matrix. Both inputs are gravity-aligned, so their relative rotation is a yaw up to
 * noise; [yawOfNearZRotation] extracts it without being thrown by the residual tilt.
 *
 * Matrices here are row-major 3x3 (index `row * 3 + col`), like the Android sensor matrices.
 */
object ArWorldAlignment {

    /** ARCore world (X, Y up, Z) read as East-Up-South, into ENU (X East, Y North, Z Up). */
    val EUS_TO_ENU = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    /**
     * Yaw (radians, counter-clockwise about ENU Up) that best explains `reference` as
     * `Rz(psi) * EUS_TO_ENU * arDevice`, both being device -> world rotations of the same instant.
     * `reference` is row-major 3x3 or 4x4; `arDevice` is row-major 3x3.
     */
    fun estimateYaw(reference: FloatArray, arDevice: FloatArray, scratch9: FloatArray): Float {
        val stride = if (reference.size >= 16) 4 else 3
        // scratch = EUS_TO_ENU * arDevice  (device -> ENU at psi = 0)
        mul3(EUS_TO_ENU, arDevice, scratch9)
        // Q = reference * scratch^T ; ideally Rz(psi).
        var q00 = 0f; var q01 = 0f; var q10 = 0f; var q11 = 0f
        for (k in 0 until 3) {
            q00 += reference[0 * stride + k] * scratch9[0 * 3 + k]
            q01 += reference[0 * stride + k] * scratch9[1 * 3 + k]
            q10 += reference[1 * stride + k] * scratch9[0 * 3 + k]
            q11 += reference[1 * stride + k] * scratch9[1 * 3 + k]
        }
        return yawOfNearZRotation(q00, q01, q10, q11)
    }

    /**
     * Least-squares yaw of a rotation that is Rz(psi) up to a small tilt, from its upper-left 2x2:
     * the closest planar rotation to that block.
     */
    fun yawOfNearZRotation(q00: Float, q01: Float, q10: Float, q11: Float): Float =
        atan2(q10 - q01, q00 + q11)

    /** `out` (row-major 4x4) = Rz(psi) * EUS_TO_ENU * arDevice, i.e. the device -> ENU matrix. */
    fun deviceToEnu(yaw: Float, arDevice: FloatArray, scratch9: FloatArray, out16: FloatArray) {
        mul3(EUS_TO_ENU, arDevice, scratch9)
        val c = cos(yaw)
        val s = sin(yaw)
        for (col in 0 until 3) {
            val x = scratch9[0 * 3 + col]
            val y = scratch9[1 * 3 + col]
            val z = scratch9[2 * 3 + col]
            out16[0 * 4 + col] = c * x - s * y
            out16[1 * 4 + col] = s * x + c * y
            out16[2 * 4 + col] = z
        }
        out16[3] = 0f; out16[7] = 0f; out16[11] = 0f
        out16[12] = 0f; out16[13] = 0f; out16[14] = 0f; out16[15] = 1f
    }

    /** Row-major 3x3 rotation from a unit quaternion (x, y, z, w), as ARCore's `Pose` stores it. */
    fun quaternionToMatrix(x: Float, y: Float, z: Float, w: Float, out9: FloatArray) {
        out9[0] = 1f - 2f * (y * y + z * z); out9[1] = 2f * (x * y - z * w); out9[2] = 2f * (x * z + y * w)
        out9[3] = 2f * (x * y + z * w); out9[4] = 1f - 2f * (x * x + z * z); out9[5] = 2f * (y * z - x * w)
        out9[6] = 2f * (x * z - y * w); out9[7] = 2f * (y * z + x * w); out9[8] = 1f - 2f * (x * x + y * y)
    }

    /**
     * Yaw from Geospatial: the same camera frame's orientation in EUS (`GeospatialPose`
     * `getEastUpSouthQuaternion`) and in ARCore's world (the camera pose it was reported for).
     * `arWorldToEus = eusCamera * arCamera^T`, and its yaw about Up is `psi`.
     */
    fun yawFromGeospatial(eusCamera9: FloatArray, arCamera9: FloatArray): Float {
        // M = eusCamera * arCamera^T is ARCore world -> EUS, a rotation about Y (up) by the yaw.
        // Rotating about EUS +Y by angle t maps (X east, Z south); in ENU that is a rotation about
        // Up by the same angle t (EUS_TO_ENU is a proper rotation that sends Y to Z).
        var m00 = 0f; var m02 = 0f; var m20 = 0f; var m22 = 0f
        for (k in 0 until 3) {
            m00 += eusCamera9[0 * 3 + k] * arCamera9[0 * 3 + k]
            m02 += eusCamera9[0 * 3 + k] * arCamera9[2 * 3 + k]
            m20 += eusCamera9[2 * 3 + k] * arCamera9[0 * 3 + k]
            m22 += eusCamera9[2 * 3 + k] * arCamera9[2 * 3 + k]
        }
        // Ry(t) = [[c, 0, s], [0, 1, 0], [-s, 0, c]]  ->  t = atan2(m02 - m20, m00 + m22).
        return atan2(m02 - m20, m00 + m22)
    }

    private fun mul3(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0 until 3) for (c in 0 until 3) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}

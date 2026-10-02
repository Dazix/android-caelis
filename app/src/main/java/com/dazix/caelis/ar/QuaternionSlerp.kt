package com.dazix.caelis.ar

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance Quaternion Spherical Linear Interpolation (SLERP) and matrix conversions.
 * Executes with 100% zero heap allocations.
 */
object QuaternionSlerp {

    private const val DOT_THRESHOLD = 0.9995f

    /**
     * Interpolates between [q1] and [q2] at parameter [u] in [0, 1] into [outQ].
     *
     * Handles antipodal quaternions ($q_1 \cdot q_2 < 0$) by inverting $q_2$ to follow
     * the shortest angular geodesic path along the 4D hypersphere.
     */
    fun slerp(q1: FloatArray, q2: FloatArray, u: Float, outQ: FloatArray) {
        val clampedU = u.coerceIn(0f, 1f)

        var dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]

        // Shortest path on hypersphere
        var q2x = q2[0]
        var q2y = q2[1]
        var q2z = q2[2]
        var q2w = q2[3]

        if (dot < 0f) {
            dot = -dot
            q2x = -q2x
            q2y = -q2y
            q2z = -q2z
            q2w = -q2w
        }

        if (dot > DOT_THRESHOLD) {
            // Quaternions are very close: NLERP to avoid division by zero
            val invU = 1f - clampedU
            outQ[0] = invU * q1[0] + clampedU * q2x
            outQ[1] = invU * q1[1] + clampedU * q2y
            outQ[2] = invU * q1[2] + clampedU * q2z
            outQ[3] = invU * q1[3] + clampedU * q2w
        } else {
            val theta = acos(dot.coerceIn(-1f, 1f))
            val sinTheta = sin(theta)
            val w1 = sin((1f - clampedU) * theta) / sinTheta
            val w2 = sin(clampedU * theta) / sinTheta

            outQ[0] = w1 * q1[0] + w2 * q2x
            outQ[1] = w1 * q1[1] + w2 * q2y
            outQ[2] = w1 * q1[2] + w2 * q2z
            outQ[3] = w1 * q1[3] + w2 * q2w
        }

        // Normalize
        val norm = sqrt(outQ[0] * outQ[0] + outQ[1] * outQ[1] + outQ[2] * outQ[2] + outQ[3] * outQ[3])
        if (norm > 1e-6f) {
            val invNorm = 1f / norm
            outQ[0] *= invNorm
            outQ[1] *= invNorm
            outQ[2] *= invNorm
            outQ[3] *= invNorm
        }
    }

    /**
     * Converts a unit quaternion [q] (x, y, z, w) into a 4x4 **row-major** rotation matrix, the layout
     * `SensorManager.getRotationMatrixFromVector` writes and every sensor matrix in this app uses.
     *
     * The column-major [quaternionToRotationMatrix] is its transpose. Feeding that one to code that
     * reads sensor matrices row-major once inverted the camera rotation: the synchronized matrix of
     * the automatic alignment, and the heading between the two rotation vectors, which came out
     * tens of degrees off and changed as the phone turned.
     */
    fun quaternionToRowMajorMatrix(q: FloatArray, outMatrix16: FloatArray) {
        quaternionToRotationMatrix(q, outMatrix16)
        var t = outMatrix16[1]; outMatrix16[1] = outMatrix16[4]; outMatrix16[4] = t
        t = outMatrix16[2]; outMatrix16[2] = outMatrix16[8]; outMatrix16[8] = t
        t = outMatrix16[6]; outMatrix16[6] = outMatrix16[9]; outMatrix16[9] = t
    }

    /**
     * Converts a unit quaternion [q] (x, y, z, w) into a 4x4 column-major rotation matrix in [outMatrix16].
     * Sensor matrices are row-major: use [quaternionToRowMajorMatrix] for those.
     */
    fun quaternionToRotationMatrix(q: FloatArray, outMatrix16: FloatArray) {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]

        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        outMatrix16[0] = 1f - 2f * (yy + zz)
        outMatrix16[1] = 2f * (xy + wz)
        outMatrix16[2] = 2f * (xz - wy)
        outMatrix16[3] = 0f

        outMatrix16[4] = 2f * (xy - wz)
        outMatrix16[5] = 1f - 2f * (xx + zz)
        outMatrix16[6] = 2f * (yz + wx)
        outMatrix16[7] = 0f

        outMatrix16[8] = 2f * (xz + wy)
        outMatrix16[9] = 2f * (yz - wx)
        outMatrix16[10] = 1f - 2f * (xx + yy)
        outMatrix16[11] = 0f

        outMatrix16[12] = 0f
        outMatrix16[13] = 0f
        outMatrix16[14] = 0f
        outMatrix16[15] = 1f
    }
}

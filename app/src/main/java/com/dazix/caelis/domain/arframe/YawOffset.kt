package com.dazix.caelis.domain.arframe

import kotlin.math.atan2

/**
 * The heading difference between two device-to-ENU rotation matrices that agree on "up".
 *
 * Android's game rotation vector (gyro + accelerometer) and the rotation vector (the same plus the
 * magnetometer) describe the same physical orientation and share the gravity axis; they differ only
 * by a rotation about world Up, because the game vector's north is arbitrary. `M · Gᵀ` is that
 * rotation. Reading the angle off the product, rather than subtracting two camera azimuths, keeps it
 * defined when the camera points at the zenith or the nadir, where an azimuth is not.
 */
object YawOffset {

    /**
     * Returns ψ in degrees such that rotating [game] by ψ about world Up gives [magnetic], in the
     * convention of `OrientationTracker.applyMagneticDeclination` (which receives it unchanged).
     *
     * Both matrices are row-major device-to-ENU, 3×3 in the top-left of a 4×4 (16 elements) as
     * `SensorManager.getRotationMatrixFromVector` writes them. Allocation-free.
     */
    fun extractDegrees(magnetic: FloatArray, game: FloatArray): Float {
        // D = M · Gᵀ, only the four entries of the horizontal block are needed.
        val d00 = row(magnetic, 0, game, 0)
        val d01 = row(magnetic, 0, game, 1)
        val d10 = row(magnetic, 1, game, 0)
        val d11 = row(magnetic, 1, game, 1)
        // applyMagneticDeclination left-multiplies by [[c, s], [-s, c]]. The least-squares angle of
        // the 2×2 block is robust to the small tilt disagreement two fusions always have.
        return Math.toDegrees(atan2((d01 - d10).toDouble(), (d00 + d11).toDouble())).toFloat()
    }

    private fun row(a: FloatArray, i: Int, b: FloatArray, j: Int): Float =
        a[i * 4] * b[j * 4] + a[i * 4 + 1] * b[j * 4 + 1] + a[i * 4 + 2] * b[j * 4 + 2]
}

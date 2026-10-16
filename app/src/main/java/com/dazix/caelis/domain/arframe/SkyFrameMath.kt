package com.dazix.caelis.domain.arframe

import kotlin.math.cos
import kotlin.math.sin

/**
 * The frames the GPU sky is drawn in, as 4x4 **column-major** matrices ready for Filament.
 *
 * World is ENU (X East, Y North, Z Up) — the frame `SensorManager.getRotationMatrixFromVector`
 * already maps the device into, so a sensor matrix is the camera's model matrix without any
 * re-basing. The camera stays at the world origin in every tracking mode: the sky is at infinity,
 * and translating the camera (ARCore VIO does) would only add parallax the sky does not have.
 *
 * Pure Kotlin, no allocations: every function writes into a caller-owned array.
 */
object SkyFrameMath {

    private const val DEG_TO_RAD = Math.PI / 180.0

    /**
     * Camera model matrix (camera -> ENU) from a row-major device -> ENU rotation (3x3 or 4x4, as
     * Android produces it) and the display rotation in degrees (0, 90, 180, 270).
     *
     * Filament's camera looks down its local -Z with +Y up, exactly the device axes of a back
     * camera in natural orientation. With the display rotated by `r` degrees counter-clockwise the
     * screen's right/up are the device axes turned by -r about device Z, hence `R * Rz(-r)`.
     */
    fun cameraModelMatrix(deviceToEnu: FloatArray, displayRotationDegrees: Int, out16: FloatArray) {
        val stride = if (deviceToEnu.size >= 16) 4 else 3
        val rad = -displayRotationDegrees * DEG_TO_RAD
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        for (row in 0 until 3) {
            val r0 = deviceToEnu[row * stride]
            val r1 = deviceToEnu[row * stride + 1]
            val r2 = deviceToEnu[row * stride + 2]
            // Columns of R * Rz: col0 = R*(c, s, 0), col1 = R*(-s, c, 0), col2 = R*(0, 0, 1).
            out16[0 * 4 + row] = r0 * c + r1 * s
            out16[1 * 4 + row] = -r0 * s + r1 * c
            out16[2 * 4 + row] = r2
        }
        out16[3] = 0f; out16[7] = 0f; out16[11] = 0f
        out16[12] = 0f; out16[13] = 0f; out16[14] = 0f; out16[15] = 1f
    }

    /**
     * Celestial (J2000, the catalogue's Y-up convention `(cos d cos a, sin d, cos d sin a)`) -> ENU.
     *
     * This is the transpose of `CelestialMatrixCalculator`'s M_enu2cel; the column-major layout of a
     * transpose is the row-major layout of the original, which is what is written here.
     */
    fun celestialToEnu(lstDegrees: Double, latitudeDegrees: Double, out16: FloatArray) {
        val lst = lstDegrees * DEG_TO_RAD
        val lat = latitudeDegrees * DEG_TO_RAD
        val sinLst = sin(lst).toFloat()
        val cosLst = cos(lst).toFloat()
        val sinLat = sin(lat).toFloat()
        val cosLat = cos(lat).toFloat()
        // M_enu2cel rows, written as the columns of the result.
        out16[0] = -sinLst; out16[1] = -sinLat * cosLst; out16[2] = cosLat * cosLst; out16[3] = 0f
        out16[4] = 0f; out16[5] = cosLat; out16[6] = sinLat; out16[7] = 0f
        out16[8] = cosLst; out16[9] = -sinLat * sinLst; out16[10] = cosLat * sinLst; out16[11] = 0f
        out16[12] = 0f; out16[13] = 0f; out16[14] = 0f; out16[15] = 1f
    }

    /** `out = m * (x, y, z, 0)` for a column-major 4x4; direction only. */
    fun transformDirection(m16: FloatArray, x: Float, y: Float, z: Float, out3: FloatArray) {
        out3[0] = m16[0] * x + m16[4] * y + m16[8] * z
        out3[1] = m16[1] * x + m16[5] * y + m16[9] * z
        out3[2] = m16[2] * x + m16[6] * y + m16[10] * z
    }
}

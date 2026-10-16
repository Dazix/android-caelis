package com.dazix.caelis.domain.nightsky

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a deep-sky photograph's four corners sit on the celestial sphere.
 *
 * The same construction the Canvas renderer fed `Matrix.setPolyToPoly`, kept so the GPU quad covers
 * exactly the sky the old bitmap did: a rectangle `widthDeg` wide (aspect from the image) around the
 * object, turned by its position angle plus the art's own rotation, offset in RA by
 * `u / cos(dec)` (clamped away from the pole) and in Dec by `v`.
 *
 * Output corners are top-left, top-right, bottom-right, bottom-left, as celestial unit vectors in
 * the catalogue's Y-up convention `(cos d cos a, sin d, cos d sin a)`.
 */
object DsoArtGeometry {

    fun corners(
        raDeg: Double,
        decDeg: Double,
        widthDeg: Double,
        heightOverWidth: Double,
        rotationDeg: Double,
        out12: FloatArray,
    ) {
        val halfW = widthDeg * 0.5
        val halfH = halfW * heightOverWidth
        val phi = Math.toRadians(rotationDeg)
        val cosP = cos(phi)
        val sinP = sin(phi)
        val cosDec = cos(Math.toRadians(decDeg)).coerceAtLeast(0.01)
        for (i in 0 until 4) {
            val u = if (i == 0 || i == 3) -halfW else halfW
            val v = if (i == 0 || i == 1) halfH else -halfH
            val uRot = u * cosP - v * sinP
            val vRot = u * sinP + v * cosP
            val ra = Math.toRadians(raDeg - uRot / cosDec)
            val dec = Math.toRadians((decDeg + vRot).coerceIn(-90.0, 90.0))
            out12[i * 3] = (cos(dec) * cos(ra)).toFloat()
            out12[i * 3 + 1] = sin(dec).toFloat()
            out12[i * 3 + 2] = (cos(dec) * sin(ra)).toFloat()
        }
    }

    /** The art width the catalogue gives, or the object's size, at least 0.1 deg. */
    fun widthDeg(artWidthDeg: Float, sizeArcmin: Float): Double =
        if (artWidthDeg > 0f) artWidthDeg.toDouble() else (sizeArcmin.toDouble() / 60.0).coerceAtLeast(0.1)
}

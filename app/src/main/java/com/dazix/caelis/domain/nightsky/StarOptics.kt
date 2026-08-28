package com.dazix.caelis.domain.nightsky

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Astronomical and optical calculations for realistic star rendering.
 * Pure Kotlin, zero Android dependencies, zero allocations.
 */
object StarOptics {

    const val NUM_BUCKETS = 6

    // Magnitude thresholds
    const val BASE_MAGNITUDE_LIMIT = 5.8f
    const val MAGNITUDE_SLOPE = 2.5f
    const val EXTINCTION_ANGLE_DEG = 10.0f
    const val MAX_AIRMASS_EXTINCTION_MAG = 1.5f

    // Hybrid Star Rendering: Sprite Caching vs Pinpoint Point Batching
    const val SPRITE_MAGNITUDE_THRESHOLD = 3.0f // Buckets 0, 1, 2 use radial gradient sprite glow
    const val STAR_SPRITE_SIZE = 64

    /**
     * Computes the limiting visible stellar magnitude for a given optical zoom factor.
     * Simulates physical telescope aperture gain ($M(z) = M_0 + 2.5 \log_{10}(z)$).
     *
     * @param zoomFactor Optical zoom factor (clamped between 0.2x and 150.0x).
     * @return Maximum visible apparent magnitude (fainter stars have higher magnitude numbers).
     */
    fun computeFaintestVisibleMagnitude(zoomFactor: Float): Float {
        val clampedZoom = zoomFactor.coerceIn(0.2f, 150.0f)
        return (BASE_MAGNITUDE_LIMIT + (MAGNITUDE_SLOPE * log10(clampedZoom.toDouble())).toFloat()).coerceAtLeast(3.0f)
    }

    /**
     * Computes the optical point-spread scaling factor for star core and halo stroke widths.
     * Uses square root scaling to mimic physical diffraction patterns without ballooning star sizes at high magnification.
     *
     * @param zoomFactor Optical zoom factor.
     * @return Optical stroke scale factor.
     */
    fun computeOpticScale(zoomFactor: Float): Float {
        return sqrt(zoomFactor.coerceIn(0.4f, 10.0f))
    }

    /**
     * Maps an apparent magnitude into one of 6 fixed pre-allocated rendering buckets.
     *
     * Bucket 0: Mag < 1.0 (Sirius, Vega, Rigel, Betelgeuse, Arcturus)
     * Bucket 1: 1.0 <= Mag < 2.0 (Polaris, Orion belt, Big Dipper pointers)
     * Bucket 2: 2.0 <= Mag < 3.0
     * Bucket 3: 3.0 <= Mag < 4.0
     * Bucket 4: 4.0 <= Mag < 5.0
     * Bucket 5: Mag >= 5.0 (Telescopic background stars)
     */
    fun computeBucketIndex(magnitude: Float): Int {
        return when {
            magnitude < 1.0f -> 0
            magnitude < 2.0f -> 1
            magnitude < 3.0f -> 2
            magnitude < 4.0f -> 3
            magnitude < 5.0f -> 4
            else -> 5
        }
    }

    /**
     * Calculates the apparent magnitude after atmospheric airmass extinction for stars near the horizon ($0^\circ \le h < 15^\circ$).
     * Sub-horizon stars ($h \le 0^\circ$) and high-altitude stars ($h \ge 15^\circ$) return the unextinguished magnitude.
     *
     * @param magnitude Intrinsic catalog magnitude.
     * @param elevationDegrees Altitude above horizon in degrees.
     * @return Extinguished apparent magnitude.
     */
    fun computeAirmassExtinction(magnitude: Float, elevationDegrees: Float): Float {
        if (elevationDegrees <= 0.0f || elevationDegrees >= EXTINCTION_ANGLE_DEG) {
            return magnitude
        }
        val extinctionFraction = 1.0f - (elevationDegrees / EXTINCTION_ANGLE_DEG)
        return magnitude + (extinctionFraction * MAX_AIRMASS_EXTINCTION_MAG)
    }
}

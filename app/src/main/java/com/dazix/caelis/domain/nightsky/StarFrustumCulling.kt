package com.dazix.caelis.domain.nightsky

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

/**
 * High-performance vector dot-product frustum culling on the 3D celestial sphere.
 * Pure Kotlin, zero allocations.
 */
object StarFrustumCulling {

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val SAFETY_MARGIN_RAD = 12.0 * DEG_TO_RAD

    /**
     * Computes the minimum dot-product threshold for frustum culling given camera FOV and viewport dimensions.
     *
     * @param fovHorizontalDegrees Camera horizontal field of view in degrees.
     * @param viewportWidth Screen width in pixels.
     * @param viewportHeight Screen height in pixels.
     * @return Cosine of maximum diagonal half-angle plus safety margin.
     */
    fun computeFrustumDotThreshold(
        fovHorizontalDegrees: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Float {
        val halfFovHRad = fovHorizontalDegrees * 0.5 * DEG_TO_RAD
        val tanHalfH = tan(halfFovHRad)
        val aspect = (viewportHeight / viewportWidth.coerceAtLeast(1f)).toDouble()
        val tanHalfV = tanHalfH * aspect
        val maxHalfAngleRad = atan(kotlin.math.sqrt(tanHalfH * tanHalfH + tanHalfV * tanHalfV)) + SAFETY_MARGIN_RAD
        return cos(maxHalfAngleRad).toFloat().coerceIn(-1.0f, 0.99f)
    }

    /**
     * Evaluates whether a star direction vector in world coordinates is within the camera view frustum.
     *
     * @param camFwdX East component of camera optical axis.
     * @param camFwdY North component of camera optical axis.
     * @param camFwdZ Up component of camera optical axis.
     * @param starVx East component of star world unit vector.
     * @param starVy North component of star world unit vector.
     * @param starVz Up component of star world unit vector.
     * @param dotThreshold Pre-computed threshold from [computeFrustumDotThreshold].
     * @return True if star is within frustum.
     */
    fun isStarInFrustum(
        camFwdX: Float,
        camFwdY: Float,
        camFwdZ: Float,
        starVx: Float,
        starVy: Float,
        starVz: Float,
        dotThreshold: Float,
    ): Boolean {
        val dot = camFwdX * starVx + camFwdY * starVy + camFwdZ * starVz
        return dot >= dotThreshold
    }
}

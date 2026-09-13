package com.dazix.caelis.ui.horizon

/**
 * Immutable state representing camera-to-terrain alignment offsets.
 *
 * @property azimuthOffset Horizontal compass correction in degrees (additive).
 * @property elevationOffset Vertical pitch correction in degrees (additive).
 * @property isLocked True when residual <= 0.5° and all validation gates pass.
 * @property residualDegrees Median error between camera luma edge and DEM profile.
 */
data class HorizonOffsetState(
    val azimuthOffset: Float = 0f,
    val elevationOffset: Float = 0f,
    val isLocked: Boolean = false,
    val residualDegrees: Float = Float.MAX_VALUE,
)

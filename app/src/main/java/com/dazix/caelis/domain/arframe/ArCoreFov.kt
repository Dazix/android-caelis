package com.dazix.caelis.domain.arframe

import kotlin.math.atan
import kotlin.math.tan

/**
 * The horizontal field of view of what ARCore draws on the screen, taken from ARCore's own numbers
 * rather than from the lens.
 *
 * In ARCore mode the picture is ARCore's, so the overlay's field of view has to be the picture's:
 * the projection matrix `Camera.getProjectionMatrix` returns is built for the display geometry that
 * was set on the session (viewport size, rotation, and the centred crop that fills it), so its
 * first diagonal entry is exactly `1 / tan(fovH / 2)` for the *viewport*, which is the number
 * `SkyProjector` and Filament's camera take. This replaces the `CameraFovCalculator` path, whose
 * inputs (CameraX crop region and resolution) describe a stream ARCore is not using.
 *
 * ARCore's projection may carry a small off-axis term (principal point away from the image centre);
 * both the GPU camera and the CPU projector are symmetric, so it is ignored here for both alike.
 */
object ArCoreFov {

    /**
     * @param projection00 element (0, 0) of ARCore's column-major projection matrix: `2 fx / width`.
     * @return degrees, or 0 for a value that cannot be a camera (not finite, not positive).
     */
    fun horizontalDegrees(projection00: Float): Float {
        if (!projection00.isFinite() || projection00 <= 0f) return 0f
        return Math.toDegrees(2.0 * atan(1.0 / projection00)).toFloat()
    }

    /** The inverse, for tests and for synthesising a projection from a known field of view. */
    fun projection00For(fovHorizontalDegrees: Float): Float =
        (1.0 / tan(Math.toRadians(fovHorizontalDegrees * 0.5))).toFloat()
}

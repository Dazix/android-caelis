package com.dazix.caelis.domain.arframe

import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The CPU twin of the Filament camera: projects ENU (or celestial) directions to screen pixels with
 * exactly the model matrix and horizontal FOV the GPU frame was drawn with.
 *
 * Labels, the selection reticle, off-screen chevrons and tap hit-testing all go through this, so
 * they cannot drift from what is drawn: one [update] per frame feeds both. Filament's
 * `Camera.Fov.HORIZONTAL` projection puts `tan(fovH / 2)` at the left/right screen edges, which is
 * the same pinhole the Canvas pipeline used (`f = (w / 2) / tan(fovH / 2)`).
 *
 * Screen coordinates are pixels with the origin top-left and Y down. Zero allocations.
 */
class SkyProjector {

    /** Camera -> ENU, column-major (as written by [SkyFrameMath.cameraModelMatrix]). */
    val cameraModel = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    var viewportWidth = 1f
        private set
    var viewportHeight = 1f
        private set
    var fovHorizontalDegrees = 60f
        private set
    var focalLengthPixels = 1f
        private set

    fun update(cameraModel16: FloatArray, width: Float, height: Float, fovHorizontalDegrees: Float) {
        cameraModel16.copyInto(cameraModel, 0, 0, 16)
        viewportWidth = width.coerceAtLeast(1f)
        viewportHeight = height.coerceAtLeast(1f)
        this.fovHorizontalDegrees = fovHorizontalDegrees
        focalLengthPixels = (viewportWidth * 0.5f) / tan(Math.toRadians(fovHorizontalDegrees * 0.5).toFloat())
    }

    /**
     * Writes `(screenX, screenY, depth)` at [offset]; depth > 0 is in front of the camera.
     * A point behind the camera is mirrored through the centre, like `CameraProjection`, so an
     * off-screen indicator still gets the right bearing.
     *
     * @return true when the point is in front of the camera.
     */
    fun projectEnu(x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0): Boolean {
        val m = cameraModel
        // Camera-space = model^T * d (the model is a pure rotation).
        val vx = m[0] * x + m[1] * y + m[2] * z
        val vy = m[4] * x + m[5] * y + m[6] * z
        val vz = m[8] * x + m[9] * y + m[10] * z
        val depth = -vz
        val inFront = depth > IN_FRONT_EPSILON
        val d = if (inFront) depth else -depth
        val safe = if (d < 1e-6f) 1e-6f else d
        val sign = if (inFront) 1f else -1f
        out[offset] = viewportWidth * 0.5f + sign * focalLengthPixels * (vx / safe)
        out[offset + 1] = viewportHeight * 0.5f - sign * focalLengthPixels * (vy / safe)
        out[offset + 2] = depth
        return inFront
    }

    /** Celestial direction through a column-major celestial -> ENU matrix, then [projectEnu]. */
    fun projectCelestial(
        celestialToEnu16: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        out: FloatArray,
        offset: Int = 0,
    ): Boolean {
        val c = celestialToEnu16
        return projectEnu(
            c[0] * x + c[4] * y + c[8] * z,
            c[1] * x + c[5] * y + c[9] * z,
            c[2] * x + c[6] * y + c[10] * z,
            out,
            offset,
        )
    }

    /** The unit ENU direction seen at a screen pixel. */
    fun unproject(screenX: Float, screenY: Float, out3: FloatArray) {
        val vx = (screenX - viewportWidth * 0.5f) / focalLengthPixels
        val vy = -(screenY - viewportHeight * 0.5f) / focalLengthPixels
        val vz = -1f
        val m = cameraModel
        val x = m[0] * vx + m[4] * vy + m[8] * vz
        val y = m[1] * vx + m[5] * vy + m[9] * vz
        val z = m[2] * vx + m[6] * vy + m[10] * vz
        val inv = 1f / sqrt(x * x + y * y + z * z)
        out3[0] = x * inv
        out3[1] = y * inv
        out3[2] = z * inv
    }

    private companion object {
        const val IN_FRONT_EPSILON = 0.001f
    }
}

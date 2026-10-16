package com.dazix.caelis.domain.arframe

/**
 * Which part of the camera buffer each corner of the screen shows, so the Filament camera
 * background draws exactly what `PreviewView` FILL_CENTER used to.
 *
 * The buffer is the camera's own, in sensor orientation. CameraX reports how many degrees clockwise
 * it has to be turned to stand upright on the display (`TransformationInfo.rotationDegrees`), and
 * FILL_CENTER then scales that upright picture until it covers the viewport and trims the overflow
 * evenly from both sides of the longer axis. `CameraFovCalculator.computeStreamHorizontalFov`
 * derives the AR field of view from the same assumption (the axis the viewport is relatively longer
 * in stays whole), so the picture and the overlay cannot disagree about what is on screen.
 *
 * Buffer coordinates are texture coordinates of a buffer in memory: origin at the first row's left
 * edge (the top-left of the image as the camera wrote it), u to the right, v **down**. That is the
 * convention of every GL texture and of an `AHardwareBuffer` imported as an external image — which
 * `CameraBackgroundDeviceTest` checks on the real GPU, because a flipped origin would look like a
 * mirrored camera and nothing here could notice.
 *
 * Screen coordinates are normalised: (0, 0) top-left, (1, 1) bottom-right, y down. The full-screen
 * triangle's corners, (-1,-1), (3,-1), (-1,3) in clip space (y up), are therefore (0, 1), (2, 1) and
 * (0, -1). The mapping is affine, so the three corners define it everywhere in between.
 *
 * Pure Kotlin, no allocations: results go into a caller-owned array.
 */
object CameraBackgroundUv {

    /** Floats written by [compute]: u and v of the three triangle corners. */
    const val UV_COUNT = 6

    /**
     * Writes the buffer coordinates of the full-screen triangle's corners (clip (-1,-1), (3,-1),
     * (-1,3), in that order) to [out] as u0, v0, u1, v1, u2, v2.
     *
     * @param bufferWidth width of the camera buffer in pixels, as the camera wrote it.
     * @param bufferHeight height of the camera buffer in pixels.
     * @param rotationDegrees clockwise rotation that makes the buffer upright; a multiple of 90.
     * @param viewportWidth width of the surface the triangle covers, in pixels.
     * @param viewportHeight height of that surface.
     * @return false (leaving [out] untouched) for a degenerate size or a rotation that is not a
     *   multiple of 90, which has no meaning for a camera buffer.
     */
    fun compute(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        out: FloatArray,
    ): Boolean {
        val rotation = normalisedRotation(rotationDegrees)
        if (rotation < 0 || bufferWidth <= 0 || bufferHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return false
        }
        val ratio = visibleRatio(bufferWidth, bufferHeight, rotation, viewportWidth, viewportHeight)
        bufferPoint(0.0, 1.0, ratio, rotation, out, 0)
        bufferPoint(2.0, 1.0, ratio, rotation, out, 2)
        bufferPoint(0.0, -1.0, ratio, rotation, out, 4)
        return true
    }

    /**
     * The buffer coordinates shown at one normalised screen point, written to [out] as u, v. The
     * same mapping as [compute]; exposed so a point inside the screen (a corner, the centre) can be
     * checked without interpolating by hand.
     */
    fun bufferPointAt(
        screenX: Double,
        screenY: Double,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        out: FloatArray,
    ): Boolean {
        val rotation = normalisedRotation(rotationDegrees)
        if (rotation < 0 || bufferWidth <= 0 || bufferHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return false
        }
        val ratio = visibleRatio(bufferWidth, bufferHeight, rotation, viewportWidth, viewportHeight)
        bufferPoint(screenX, screenY, ratio, rotation, out, 0)
        return true
    }

    /**
     * The triangle's buffer coordinates from three samples of a mapping somebody else computed — ARCore's
     * `Frame.transformCoordinates2d` from VIEW_NORMALIZED to TEXTURE_NORMALIZED — instead of from
     * rotation and crop. ARCore knows the display rotation, the crop and the camera's own geometry;
     * the app only has to ask where the screen's three corners land in the texture.
     *
     * The mapping is affine, so the screen corners (0,0), (1,0) and (0,1) fix it, and the triangle's
     * corners (0,1), (2,1), (0,-1) follow by extrapolation. They are never passed to ARCore
     * themselves: two of them lie outside the view, where a transform is free to clamp.
     *
     * @param samples u, v of the top-left, top-right and bottom-left screen corners (6 floats).
     * @param textureVIsDown whether the texture's v runs down the image as stored in memory, like
     *   ours ([CameraBackgroundUv] convention). False flips v. Which one ARCore's TEXTURE_NORMALIZED
     *   is has to be confirmed on a device: it is the single switch that would mirror the picture.
     */
    fun fromCornerSamples(samples: FloatArray, textureVIsDown: Boolean, out: FloatArray): Boolean {
        if (samples.size < 6 || out.size < UV_COUNT) return false
        for (i in 0 until 6) if (!samples[i].isFinite()) return false
        val u00 = samples[0].toDouble(); val v00 = samples[1].toDouble()
        val du = samples[2] - samples[0]; val dv = samples[3] - samples[1]   // one screen width to the right
        val eu = samples[4] - samples[0]; val ev = samples[5] - samples[1]   // one screen height down
        // Triangle corners in normalised screen coordinates: (0, 1), (2, 1), (0, -1).
        corner(u00, v00, du, dv, eu, ev, 0.0, 1.0, textureVIsDown, out, 0)
        corner(u00, v00, du, dv, eu, ev, 2.0, 1.0, textureVIsDown, out, 2)
        corner(u00, v00, du, dv, eu, ev, 0.0, -1.0, textureVIsDown, out, 4)
        return true
    }

    private fun corner(
        u00: Double, v00: Double, du: Float, dv: Float, eu: Float, ev: Float,
        x: Double, y: Double, vDown: Boolean, out: FloatArray, at: Int,
    ) {
        val u = u00 + x * du + y * eu
        val v = v00 + x * dv + y * ev
        out[at] = u.toFloat()
        out[at + 1] = (if (vDown) v else 1.0 - v).toFloat()
    }

    /**
     * The clockwise rotation (0, 90, 180, 270) that makes a camera image upright, from where the
     * image's top edge points on the screen: the vector from the image's top-left to its top-right
     * corner, in screen coordinates with y down. For an upright image that is "to the right"; for one
     * that has to be turned 90 degrees clockwise the top edge runs down the screen. Used where the
     * platform reports geometry as a coordinate mapping rather than as a rotation (ARCore).
     */
    fun rotationFromTopEdge(dx: Float, dy: Float): Int = when {
        !dx.isFinite() || !dy.isFinite() -> 0
        kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> if (dx >= 0f) 0 else 180
        else -> if (dy > 0f) 90 else 270
    }

    /** 0, 90, 180 or 270, or -1 for anything else. */
    private fun normalisedRotation(degrees: Int): Int {
        val r = ((degrees % 360) + 360) % 360
        return if (r % 90 == 0) r else -1
    }

    /**
     * The upright picture's aspect ratio relative to the viewport's: above 1 the picture is the
     * wider one and FILL_CENTER fills the height and loses the sides; below 1 it fills the width
     * and loses top and bottom.
     */
    private fun visibleRatio(
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Double {
        val turned = rotation == 90 || rotation == 270
        val uprightWidth = (if (turned) bufferHeight else bufferWidth).toDouble()
        val uprightHeight = (if (turned) bufferWidth else bufferHeight).toDouble()
        return (uprightWidth / uprightHeight) / (viewportWidth.toDouble() / viewportHeight)
    }

    private fun bufferPoint(
        screenX: Double,
        screenY: Double,
        pictureOverViewportAspect: Double,
        rotation: Int,
        out: FloatArray,
        at: Int,
    ) {
        // Share of the upright picture that stays visible along each axis.
        val visibleWidth = if (pictureOverViewportAspect > 1.0) 1.0 / pictureOverViewportAspect else 1.0
        val visibleHeight = if (pictureOverViewportAspect > 1.0) 1.0 else pictureOverViewportAspect
        // Position in the upright picture, 0..1 from its top-left, after the centred crop.
        val x = 0.5 + (screenX - 0.5) * visibleWidth
        val y = 0.5 + (screenY - 0.5) * visibleHeight
        // Undo the clockwise turn: a buffer turned 90 degrees clockwise has its top-left corner at
        // the upright picture's top-right, so the buffer's u runs down the picture (u = y) and its v
        // runs from the right edge to the left (v = 1 - x). The other turns follow the same way.
        val u: Double
        val v: Double
        when (rotation) {
            0 -> { u = x; v = y }
            90 -> { u = y; v = 1.0 - x }
            180 -> { u = 1.0 - x; v = 1.0 - y }
            else -> { u = 1.0 - y; v = x }
        }
        out[at] = u.toFloat()
        out[at + 1] = v.toFloat()
    }
}

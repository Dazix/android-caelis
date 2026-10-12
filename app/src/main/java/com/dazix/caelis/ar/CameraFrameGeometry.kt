package com.dazix.caelis.ar

/**
 * Everything [SkylineAligner] needs to know about *where a pixel of one camera frame points*, apart
 * from the pixels themselves.
 *
 * It exists so that the aligner does not care where a frame came from. The sensor path builds it from
 * a CameraX analysis frame and the rotation-vector matrix SLERPed to that frame's timestamp; an ARCore
 * frame can build it from its own Y plane, `Camera.getImageIntrinsics()` and `Frame`'s own pose, which
 * already belong to one instant and so need no interpolation at all. Both must hand over a matrix and a
 * field of view that describe the *same exposure as the pixels*: a pose from "now" with a frame from
 * 80 ms ago is exactly the error the whole alignment is trying to remove.
 *
 * Pure Kotlin, no Android types: [displayRotation] is a `Surface.ROTATION_*` constant, which is a plain
 * int 0..3 here so the type stays unit-testable and the ARCore adapter can fill it in without a
 * conversion.
 *
 * @property frameWidth width of the upright luma frame in pixels (after any output rotation).
 * @property frameHeight height of the upright luma frame in pixels.
 * @property viewportWidth width in pixels of the surface the overlay is drawn on.
 * @property viewportHeight height in pixels of that surface.
 * @property fillScale how many viewport pixels one frame pixel covers. `FILL_CENTER` scales the frame
 *   until it covers the viewport and crops the rest, so [fillCenterScale] is what a preview that
 *   fills its view uses; a source whose picture is fitted differently (an ARCore background with its
 *   own display geometry) passes what it actually measured instead.
 * @property rotationMatrix the matrix the overlay is being drawn with, in the form
 *   [CameraProjection.unprojectFast] takes. Held by reference, not copied: the geometry is built, used
 *   and dropped within one alignment, and copying 16 floats per frame would only hide a caller that
 *   mutates it mid-use.
 * @property displayRotation `Surface.ROTATION_0/90/180/270` as 0..3.
 * @property fovHorizontalDegrees the horizontal field of view of the *viewport*, i.e. what
 *   [CameraProjection] expects, not the raw sensor's.
 */
class CameraFrameGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fillScale: Float,
    val rotationMatrix: FloatArray,
    val displayRotation: Int,
    val fovHorizontalDegrees: Float,
) {

    /**
     * False when the numbers cannot describe a picture on a screen. Checked by the aligner rather than
     * asserted in the constructor: an unusable geometry (a view not yet measured, a camera that has not
     * reported a field of view) is an ordinary moment during start-up, and the right answer to it is
     * "no alignment", not a crash.
     */
    val isUsable: Boolean
        get() = frameWidth > 0 && frameHeight > 0 &&
            viewportWidth > 1f && viewportHeight > 1f &&
            fillScale > 0f && fillScale.isFinite() &&
            fovHorizontalDegrees > 0f && fovHorizontalDegrees < 180f &&
            rotationMatrix.size >= 9

    companion object {

        /**
         * The scale `PreviewView` FILL_CENTER applies: the frame is enlarged until it covers the
         * viewport on both axes, and what overhangs is cropped. The same scale maps an analysis pixel
         * onto the canvas the overlay is drawn on. Returns 0 for a frame or viewport with no size, which
         * [isUsable] then rejects.
         */
        fun fillCenterScale(
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Float,
            viewportHeight: Float,
        ): Float {
            if (frameWidth <= 0 || frameHeight <= 0) return 0f
            return maxOf(viewportWidth / frameWidth, viewportHeight / frameHeight)
        }

        /** A geometry for a frame shown with FILL_CENTER, which is every CameraX preview this app draws. */
        fun fillCenter(
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Float,
            viewportHeight: Float,
            rotationMatrix: FloatArray,
            displayRotation: Int,
            fovHorizontalDegrees: Float,
        ) = CameraFrameGeometry(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            fillScale = fillCenterScale(frameWidth, frameHeight, viewportWidth, viewportHeight),
            rotationMatrix = rotationMatrix,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
        )
    }
}

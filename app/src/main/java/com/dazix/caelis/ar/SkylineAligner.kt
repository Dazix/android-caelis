package com.dazix.caelis.ar

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.SkylineExtractor
import com.dazix.caelis.domain.horizon.SkylineFit
import com.dazix.caelis.domain.horizon.SkylineMatch
import com.dazix.caelis.domain.horizon.SkylineMatcher
import com.dazix.caelis.domain.horizon.SkylineRefusal

/** One camera frame's brightness plane, copied out so the buffer can go back to the camera. */
class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val timestampNs: Long = 0L,
    /**
     * Set by a source that knows where its own pixels point (an ARCore frame). A CameraX frame leaves
     * it null and the caller derives the geometry from the preview and the SLERPed sensor pose.
     */
    val pose: LumaFramePose? = null,
)

/**
 * The geometry that comes with an ARCore frame, all of it for the *same exposure* as the pixels: the
 * pose the overlay was drawn with for that frame (calibration included, so the aligner's offsets are
 * increments on it as they are on the sensor path), the field of view of the viewport, and how many
 * viewport pixels one frame pixel covers.
 */
class LumaFramePose(
    val rotationMatrix: FloatArray,
    val fovHorizontalDegrees: Float,
    val fillScale: Float,
)

/** Asks the camera for a single upright luma frame. Registered by [CameraPreview] once it is bound. */
fun interface FrameGrabber {
    fun grabNext(onFrame: (LumaFrame) -> Unit)
}

/**
 * Turns a camera frame into a correction for the compass, by matching what the camera sees against
 * the terrain profile.
 *
 * The pieces either side of this are testable on their own — [SkylineExtractor] finds the boundary,
 * [SkylineMatcher] does the fitting — so what is left here is the one thing that needs the camera's
 * geometry: where a pixel of the analysis frame lands on the screen the overlay is drawn on.
 */
object SkylineAligner {

    /**
     * Sample every n-th column. At a typical 640-wide analysis frame that is 160 samples, which is
     * plenty for a fit whose answer is one number, and keeps the whole thing inside a frame time.
     */
    const val COLUMN_STRIDE = 4

    /**
     * @param frame an upright luma frame (`ImageAnalysis` with output rotation enabled, or an ARCore
     *   camera image's Y plane).
     * @param geometry where that frame's pixels point: its size, the viewport it is shown in, the fill
     *   scale, and the pose and field of view *of the same exposure*. Must describe [frame].
     * @return the offsets to add to the standing calibration, or null when the frame does not
     *   support an answer — fog, darkness, a wall, terrain too flat to pin the compass, or a geometry
     *   that does not describe this frame.
     */
    fun align(
        frame: LumaFrame,
        geometry: CameraFrameGeometry,
        profile: HorizonProfile,
    ): SkylineMatch? = fit(frame, geometry, profile).match

    /** [align], with the reason for a refusal; see [SkylineMatcher.fit]. */
    fun fit(
        frame: LumaFrame,
        geometry: CameraFrameGeometry,
        profile: HorizonProfile,
    ): SkylineFit {
        if (!geometry.isUsable) return SkylineFit.refused(SkylineRefusal.NO_GEOMETRY, 0)
        // A geometry built for another frame size would put every column in the wrong direction
        // while still looking like a measurement, so it is refused rather than trusted.
        if (frame.width != geometry.frameWidth || frame.height != geometry.frameHeight) {
            return SkylineFit.refused(SkylineRefusal.NO_GEOMETRY, 0)
        }
        val viewportWidth = geometry.viewportWidth
        val viewportHeight = geometry.viewportHeight

        val columns = (frame.width + COLUMN_STRIDE - 1) / COLUMN_STRIDE
        val rows = IntArray(columns)
        val sampled = SkylineExtractor.extract(
            luma = frame.data,
            width = frame.width,
            height = frame.height,
            rowStride = frame.rowStride,
            columnStride = COLUMN_STRIDE,
            outRows = rows,
        )
        if (sampled <= 0) return SkylineFit.refused(SkylineRefusal.TOO_FEW_COLUMNS, 0)

        // For a FILL_CENTER preview the geometry's scale is the one that covers the viewport and crops
        // the rest, so it maps an analysis pixel onto the canvas the overlay is drawn on.
        val scale = geometry.fillScale
        val azimuths = DoubleArray(sampled)
        val elevations = DoubleArray(sampled)
        val azEl = DoubleArray(2)
        var used = 0

        for (i in 0 until sampled) {
            val row = rows[i]
            if (row == SkylineExtractor.NO_EDGE) continue

            val frameX = (i * COLUMN_STRIDE).toFloat()
            val screenX = (frameX - frame.width * 0.5f) * scale + viewportWidth * 0.5f
            val screenY = (row - frame.height * 0.5f) * scale + viewportHeight * 0.5f
            // Columns cropped away by FILL_CENTER are not on screen and say nothing about the
            // overlay's alignment.
            if (screenX < 0f || screenX > viewportWidth) continue
            if (screenY < 0f || screenY > viewportHeight) continue

            CameraProjection.unprojectFast(
                screenX = screenX,
                screenY = screenY,
                rotationMatrix = geometry.rotationMatrix,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                displayRotation = geometry.displayRotation,
                fovHorizontalDegrees = geometry.fovHorizontalDegrees,
                outAzEl = azEl,
            )
            azimuths[used] = azEl[0]
            elevations[used] = azEl[1]
            used++
        }

        return SkylineMatcher.fit(azimuths, elevations, used, profile)
    }
}

package com.dazix.caelis.ar

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.SkylineExtractor
import com.dazix.caelis.domain.horizon.SkylineMatch
import com.dazix.caelis.domain.horizon.SkylineMatcher

/** One camera frame's brightness plane, copied out so the buffer can go back to the camera. */
class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val timestampNs: Long = 0L,
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
     * @param frame an upright luma frame (`ImageAnalysis` with output rotation enabled).
     * @param viewportWidth the size of the Compose canvas the overlay is drawn on, in pixels.
     * @param rotationMatrix the same matrix the overlay is being drawn with.
     * @return the offsets to add to the standing calibration, or null when the frame does not
     *   support an answer — fog, darkness, a wall, or terrain too flat to pin the compass.
     */
    fun align(
        frame: LumaFrame,
        viewportWidth: Float,
        viewportHeight: Float,
        rotationMatrix: FloatArray,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
        profile: HorizonProfile,
    ): SkylineMatch? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (viewportWidth <= 1f || viewportHeight <= 1f) return null

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
        if (sampled <= 0) return null

        // PreviewView FILL_CENTER scales the frame until it covers the viewport and crops the rest,
        // so the same scale maps an analysis pixel onto the canvas the overlay is drawn on.
        val scale = maxOf(viewportWidth / frame.width, viewportHeight / frame.height)
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
                rotationMatrix = rotationMatrix,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovHorizontalDegrees,
                outAzEl = azEl,
            )
            azimuths[used] = azEl[0]
            elevations[used] = azEl[1]
            used++
        }

        return SkylineMatcher.match(azimuths, elevations, used, profile)
    }
}

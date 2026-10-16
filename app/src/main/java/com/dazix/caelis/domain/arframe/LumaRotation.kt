package com.dazix.caelis.domain.arframe

/**
 * Turns a camera brightness plane upright.
 *
 * CameraX can deliver analysis frames already rotated (`setOutputImageRotationEnabled`); ARCore's CPU
 * image is always in the sensor's own orientation, so the skyline search — which looks for "sky above,
 * ground below" along image columns — would be looking along the wrong axis. The rotation uses the
 * same convention as [CameraBackgroundUv]: clockwise degrees that make the buffer upright, so a buffer
 * that has to be turned 90 degrees clockwise has its first row on the right-hand side of the result.
 *
 * Pure Kotlin. The output is tightly packed (row stride = upright width).
 */
object LumaRotation {

    /** Upright width for a source of [width] x [height] turned by [rotationDegrees]. */
    fun uprightWidth(width: Int, height: Int, rotationDegrees: Int): Int =
        if (isTurned(rotationDegrees)) height else width

    fun uprightHeight(width: Int, height: Int, rotationDegrees: Int): Int =
        if (isTurned(rotationDegrees)) width else height

    /**
     * @param src the plane as stored, [rowStride] bytes per row (>= [width]).
     * @param out receives `uprightWidth * uprightHeight` bytes.
     * @return false for a rotation that is not a multiple of 90, a degenerate size or too small a buffer.
     */
    fun rotate(
        src: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        out: ByteArray,
    ): Boolean {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r % 90 != 0 || width <= 0 || height <= 0 || rowStride < width) return false
        if (src.size < (height - 1) * rowStride + width || out.size < width * height) return false
        val outWidth = uprightWidth(width, height, r)
        val outHeight = uprightHeight(width, height, r)
        for (y in 0 until outHeight) {
            val rowStart = y * outWidth
            for (x in 0 until outWidth) {
                val sourceIndex = when (r) {
                    0 -> y * rowStride + x
                    90 -> (height - 1 - x) * rowStride + y
                    180 -> (height - 1 - y) * rowStride + (width - 1 - x)
                    else -> x * rowStride + (width - 1 - y)
                }
                out[rowStart + x] = src[sourceIndex]
            }
        }
        return true
    }

    private fun isTurned(rotationDegrees: Int): Boolean {
        val r = ((rotationDegrees % 360) + 360) % 360
        return r == 90 || r == 270
    }
}

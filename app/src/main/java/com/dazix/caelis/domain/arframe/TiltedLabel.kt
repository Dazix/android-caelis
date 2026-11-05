package com.dazix.caelis.domain.arframe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a sprite laid out against the horizon lands on screen: the CPU twin of `label.mat`'s align
 * path, for the clearance of the peak names.
 *
 * The sprite's "up" is the local vertical at its anchor ([screenUp]), its pivot is `lift` pixels
 * along it, and it is turned a further `angle` counter-clockwise around the pivot. A sprite corner
 * `(dx, dy)` from the pivot (Y down) lands at `pivot + dx * axis - dy * normal`, which is the
 * shader's formula. A peak name is placed this way so that it turns with the ridge instead of with
 * the phone; held screen-upright it swung against the skyline whenever the phone rolled.
 *
 * Screen pixels, origin top-left, Y down. Zero allocations: one instance is reused.
 */
class TiltedLabel {

    /** Centre of the sprite's box. */
    var centerX = 0f
        private set
    var centerY = 0f
        private set

    /** Unit vector along the sprite's rows (the text's reading direction). */
    var axisX = 1f
        private set
    var axisY = 0f
        private set

    /** Unit vector along the sprite's columns, towards its top. */
    var normalX = 0f
        private set
    var normalY = -1f
        private set

    /** Half the sprite's width (along [axisX]) and height (along [normalX]). */
    var halfLength = 0f
        private set
    var halfThickness = 0f
        private set

    /** Half extents of the axis-aligned box around the turned sprite. */
    val boundsHalfWidth: Float get() = abs(axisX) * halfLength + abs(normalX) * halfThickness
    val boundsHalfHeight: Float get() = abs(axisY) * halfLength + abs(normalY) * halfThickness

    /**
     * Lays out a [width] x [height] sprite whose anchor is ([spriteAnchorX], [spriteAnchorY]) inside
     * it (pixels from its top-left), with the anchor at ([anchorX], [anchorY]) on screen, the local
     * vertical ([upX], [upY]) (unit), lifted [liftPx] and turned [angleRad] counter-clockwise.
     */
    fun place(
        anchorX: Float, anchorY: Float,
        upX: Float, upY: Float,
        liftPx: Float, angleRad: Float,
        width: Float, height: Float,
        spriteAnchorX: Float, spriteAnchorY: Float,
    ) {
        // Right of the vertical, Y down: up (0, -1) gives right (1, 0).
        val rightX = -upY
        val rightY = upX
        val ca = cos(angleRad)
        val sa = sin(angleRad)
        axisX = rightX * ca + upX * sa
        axisY = rightY * ca + upY * sa
        normalX = upX * ca - rightX * sa
        normalY = upY * ca - rightY * sa
        halfLength = width * 0.5f
        halfThickness = height * 0.5f
        val dx = width * 0.5f - spriteAnchorX
        val dy = height * 0.5f - spriteAnchorY
        centerX = anchorX + upX * liftPx + dx * axisX - dy * normalX
        centerY = anchorY + upY * liftPx + dx * axisY - dy * normalY
    }

    /**
     * Whether this sprite overlaps one centred at ([otherX], [otherY]) with the given half extents,
     * laid out with the same axes. Peak names are all turned the same way against a horizon that is
     * nearly straight across one screen, so the boxes are taken as parallel and compared along this
     * one's axes: two names side by side whose axis-aligned boxes overlap by far, but whose text does
     * not, are not a collision.
     */
    fun overlapsParallel(otherX: Float, otherY: Float, otherHalfLength: Float, otherHalfThickness: Float): Boolean {
        val ox = otherX - centerX
        val oy = otherY - centerY
        val along = abs(ox * axisX + oy * axisY)
        val across = abs(ox * normalX + oy * normalY)
        return along < halfLength + otherHalfLength && across < halfThickness + otherHalfThickness
    }

    companion object {
        /** How far towards the zenith [screenUp] steps, in degrees. */
        private const val STEP_DEG = 0.1

        /**
         * The local vertical at ([azimuthDeg], [elevationDeg]) as a unit screen vector (Y down) into
         * [out2]: where a small step towards the zenith lands, as in `label.mat`. Falls back to the
         * screen's up when the step does not land in front of the camera or does not move.
         * [scratch3] is a caller-owned buffer of three floats.
         */
        fun screenUp(
            projector: SkyProjector,
            azimuthDeg: Double,
            elevationDeg: Double,
            anchorX: Float,
            anchorY: Float,
            out2: FloatArray,
            scratch3: FloatArray,
        ) {
            out2[0] = 0f
            out2[1] = -1f
            if (!projector.projectAltAz(azimuthDeg, elevationDeg + STEP_DEG, scratch3)) return
            val dx = scratch3[0] - anchorX
            val dy = scratch3[1] - anchorY
            val len = sqrt(dx * dx + dy * dy)
            if (len <= 1e-3f) return
            out2[0] = dx / len
            out2[1] = dy / len
        }
    }
}

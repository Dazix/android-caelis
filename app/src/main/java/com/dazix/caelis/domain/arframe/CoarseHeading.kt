package com.dazix.caelis.domain.arframe

/**
 * A session-only heading correction for a compass that is wrong by more than a calibration may be.
 *
 * Found on a Pixel 10 Pro: on the first start the overlay stood about 180° out, and the ±20° bound
 * on the standing calibration left the user no way to drag it onto the sky. Its magnetometer carries
 * a ~450 µT offset from the built-in magnets, ten times the Earth's field, and until the fusion has
 * estimated that offset the rotation vector's north is anywhere at all. The bound stays right for
 * what it protects — a *stored* calibration, which is a property of the phone and outlives the
 * session — so the excess is kept apart: never stored, gone with the session or a reset.
 *
 * Only while the gyro holds the heading ([HeadingLock]): the correction cancels the compass error of
 * this moment, and without the lock the compass would turn the overlay again as soon as its offset
 * estimate converged, leaving the coarse correction as the error. And only while the compass itself
 * is not trusted ([HeadingLock.compassState] not OK): with a trusted compass a drag past the bound is
 * a mis-drag, as before.
 */
object CoarseHeading {

    fun isAllowed(locked: Boolean, compassState: HeadingState): Boolean =
        locked && compassState != HeadingState.OK

    /**
     * The coarse correction once an alignment asks for [totalAzimuthDegrees] of correction in all
     * (calibration plus coarse). The calibration takes what fits within [boundDegrees], the coarse
     * correction the rest; dragging back gives up the coarse part first. Not [allowed], it stays
     * [currentCoarseDegrees] and the calibration takes the change, clamped as ever.
     */
    fun coarseFor(totalAzimuthDegrees: Float, currentCoarseDegrees: Float, boundDegrees: Float, allowed: Boolean): Float {
        if (!allowed) return currentCoarseDegrees
        val total = signed(totalAzimuthDegrees)
        return total - total.coerceIn(-boundDegrees, boundDegrees)
    }

    private fun signed(deg: Float): Float {
        val wrapped = ((deg % 360f) + 360f) % 360f
        return if (wrapped > 180f) wrapped - 360f else wrapped
    }
}

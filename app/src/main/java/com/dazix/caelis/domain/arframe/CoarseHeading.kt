package com.dazix.caelis.domain.arframe

/**
 * A session-only heading correction for the part of a hand alignment a calibration may not hold.
 *
 * Found on a Pixel 10 Pro: on the first start the overlay stood about 180° out, and the ±20° bound
 * on the standing calibration left the user no way to drag it onto the sky. Its magnetometer carries
 * a ~450 µT offset from the built-in magnets, ten times the Earth's field, and until the fusion has
 * estimated that offset the rotation vector's north is anywhere at all. The bound stays right for
 * what it protects — a *stored* calibration, which is a property of the phone and outlives the
 * session — so the excess is kept apart: never stored, gone with the session or a reset.
 *
 * Never refused. It was allowed only while the compass was not trusted and the gyro held the
 * heading, and that blocked a real correction: a compass judged consistent ([CompassConsistency])
 * has no bias that turns with the phone, but steel nearby adds a field fixed in the world, which
 * shifts north by the same amount in every direction and looks perfectly consistent. A wrong drag
 * costs only this session, and dragging back or a reset takes it away.
 */
object CoarseHeading {

    /**
     * The coarse correction once an alignment asks for [totalAzimuthDegrees] of correction in all
     * (calibration plus coarse). The calibration takes what fits within [boundDegrees], the coarse
     * correction the rest; dragging back gives up the coarse part first.
     */
    fun coarseFor(totalAzimuthDegrees: Float, boundDegrees: Float): Float {
        val total = signed(totalAzimuthDegrees)
        return total - total.coerceIn(-boundDegrees, boundDegrees)
    }

    private fun signed(deg: Float): Float {
        val wrapped = ((deg % 360f) + 360f) % 360f
        return if (wrapped > 180f) wrapped - 360f else wrapped
    }
}

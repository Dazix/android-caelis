package com.dazix.caelis.domain.arframe

/** What the heading of the sensor path currently rests on, for the UI. */
enum class HeadingState {
    /** The magnetometer reports itself unreliable or uncalibrated. */
    UNRELIABLE,

    /** The magnetometer reports low accuracy. */
    LOW,

    /** The magnetometer reports medium or high accuracy. */
    OK,

    /** The user aligned the overlay this session; the compass is no longer consulted. */
    LOCKED,
}

/**
 * Holds the heading of the sensor path once the user has aligned it.
 *
 * Found on the device: by an indoor window the compass needed +14° of correction, on the roof −7°,
 * and after aligning by hand a look left and right came back slightly off. Rebar and window frames
 * bend the field differently in every direction, and the rotation vector keeps pulling the heading
 * back to that bent field, so no single calibration fits all directions.
 *
 * The rotation vector stays what is drawn — it is the responsive one; the game rotation vector
 * (no magnetometer) lagged visibly behind a tilt on a Pixel 7 Pro when it was drawn instead. ψ, the
 * heading between the two ([YawOffset]), carries every compass excursion. Until the user aligns,
 * nothing is corrected and the overlay is the plain rotation vector, as before. The alignment
 * remembers ψ at that moment; from then on [correctionDegrees] cancels every later change of ψ, so
 * the heading follows the gyro and the remaining error is its slow yaw drift, which does not depend
 * on where the phone points. The correction is zero at the instant of locking: no jump.
 *
 * A restored calibration does not lock: it was measured somewhere else, maybe somewhere the field
 * was bent the other way. Angles are degrees. Allocation-free.
 */
class HeadingLock {

    var hasHeading: Boolean = false
        private set

    var isLocked: Boolean = false
        private set

    private var latestPsi = 0f
    private var lockedPsi = 0f

    /** The latest ψ between the rotation vector and the game rotation vector, degrees. */
    fun addCompass(psiDegrees: Float) {
        latestPsi = psiDegrees
        hasHeading = true
    }

    /**
     * The user aligned the overlay. Without a heading yet there is nothing to hold. Further
     * alignments while locked keep the original reference: re-zeroing it would fold the drift
     * accumulated so far back in as a jump.
     */
    fun lock() {
        if (!hasHeading || isLocked) return
        lockedPsi = latestPsi
        isLocked = true
    }

    /** Degrees to add to the rotation vector's heading; zero while unlocked. */
    fun correctionDegrees(): Float = if (isLocked) wrap(lockedPsi - latestPsi) else 0f

    /** Back to the compass: an explicit calibration reset, or a new session. */
    fun reset() {
        isLocked = false
        hasHeading = false
        latestPsi = 0f
        lockedPsi = 0f
    }

    fun state(compassAccuracy: Int): HeadingState = when {
        isLocked -> HeadingState.LOCKED
        compassAccuracy >= SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingState.OK
        compassAccuracy == SENSOR_STATUS_ACCURACY_LOW -> HeadingState.LOW
        else -> HeadingState.UNRELIABLE
    }

    companion object {
        /** `SensorManager.SENSOR_STATUS_ACCURACY_*`, repeated to keep this file Android-free. */
        const val SENSOR_STATUS_ACCURACY_MEDIUM = 2
        const val SENSOR_STATUS_ACCURACY_LOW = 1

        private fun wrap(deg: Float): Float {
            var a = deg % 360f
            if (a > 180f) a -= 360f
            if (a <= -180f) a += 360f
            return a
        }
    }
}

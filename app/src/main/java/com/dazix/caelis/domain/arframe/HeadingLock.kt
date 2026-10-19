package com.dazix.caelis.domain.arframe

/** What the heading of the sensor path currently rests on, for the UI. */
enum class HeadingState {
    /**
     * The magnetometer reports itself unreliable or uncalibrated, or the fusion's own heading
     * uncertainty is more than a calibration can correct.
     */
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
 *
 * A lock taken before the game vector has settled ([GameVectorSettle]) is held back: ψ still moves
 * while it converges, and cancelling that turned the overlay 5.8° away from the ridge it had just
 * been aligned to. Until then the heading stays on the compass, exactly what the alignment was
 * measured against, and ψ is captured at the first settled sample. [isLocked] is true from the
 * request on: the user has aligned, whether or not the gyro holds it yet.
 */
class HeadingLock {

    var hasHeading: Boolean = false
        private set

    /** The user aligned this session; the hold itself may still wait for the game vector. */
    var isLocked: Boolean = false
        private set

    /** ψ is frozen and the compass is no longer consulted. */
    var isHolding: Boolean = false
        private set

    private var latestPsi = 0f
    private var lockedPsi = 0f
    private var gameSettled = false

    /**
     * The latest ψ between the rotation vector and the game rotation vector, degrees, and whether the
     * game vector has settled. A pending lock takes hold at the first settled sample.
     */
    fun addCompass(psiDegrees: Float, gameVectorSettled: Boolean = true) {
        latestPsi = psiDegrees
        hasHeading = true
        gameSettled = gameVectorSettled
        if (isLocked && !isHolding && gameSettled) hold()
    }

    /**
     * The user aligned the overlay. Without a heading yet there is nothing to hold. Further
     * alignments while locked keep the original reference: re-zeroing it would fold the drift
     * accumulated so far back in as a jump.
     */
    fun lock() {
        if (!hasHeading || isLocked) return
        isLocked = true
        if (gameSettled) hold()
    }

    private fun hold() {
        lockedPsi = latestPsi
        isHolding = true
    }

    /** Degrees to add to the rotation vector's heading; zero until the lock holds. */
    fun correctionDegrees(): Float = if (isHolding) wrap(lockedPsi - latestPsi) else 0f

    /** Back to the compass: an explicit calibration reset, or a new session. */
    fun reset() {
        isLocked = false
        isHolding = false
        hasHeading = false
        latestPsi = 0f
        lockedPsi = 0f
        gameSettled = false
    }

    fun state(
        compassAccuracy: Int,
        headingUncertaintyDegrees: Float = Float.NaN,
        correctableDegrees: Float = Float.POSITIVE_INFINITY,
    ): HeadingState =
        if (isLocked) HeadingState.LOCKED else compassState(compassAccuracy, headingUncertaintyDegrees, correctableDegrees)

    companion object {
        /** `SensorManager.SENSOR_STATUS_ACCURACY_*`, repeated to keep this file Android-free. */
        const val SENSOR_STATUS_ACCURACY_MEDIUM = 2
        const val SENSOR_STATUS_ACCURACY_LOW = 1

        /**
         * What the compass alone says, lock or no lock.
         *
         * The accuracy is not enough: on a Pixel 10 Pro the overlay stood about 180° out on the first
         * start and no warning showed, while the rotation vector's own heading estimate
         * ([headingUncertaintyDegrees]) still said ±30° after the offset had settled. An uncertainty
         * beyond [correctableDegrees] — what a calibration can reach — is unreliable whatever the
         * accuracy says; NaN (not reported) leaves the accuracy to decide.
         */
        fun compassState(
            compassAccuracy: Int,
            headingUncertaintyDegrees: Float,
            correctableDegrees: Float,
        ): HeadingState = when {
            headingUncertaintyDegrees > correctableDegrees -> HeadingState.UNRELIABLE
            compassAccuracy >= SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingState.OK
            compassAccuracy == SENSOR_STATUS_ACCURACY_LOW -> HeadingState.LOW
            else -> HeadingState.UNRELIABLE
        }

        /**
         * The rotation vector's estimated heading accuracy, `values[4]`, in degrees; NaN when the
         * event does not carry it or reports it unavailable (-1).
         */
        fun headingUncertaintyDegrees(values: FloatArray): Float {
            if (values.size < 5) return Float.NaN
            val radians = values[4]
            if (radians.isNaN() || radians < 0f) return Float.NaN
            return Math.toDegrees(radians.toDouble()).toFloat()
        }

        private fun wrap(deg: Float): Float {
            var a = deg % 360f
            if (a > 180f) a -= 360f
            if (a <= -180f) a += 360f
            return a
        }
    }
}

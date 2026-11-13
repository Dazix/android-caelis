package com.dazix.caelis.domain.arframe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a manual view level from the roll the phone had when it took over, instead of in one frame.
 *
 * Both AR screens drop the roll when a drag (or Night Sky's manual mode) takes the pose from the
 * sensors: the view is rebuilt from azimuth and elevation alone. Dropped at once, a phone held at a
 * slant made the whole sky jump. [begin] records that roll and [applyTo] lays what is left of it onto
 * the level matrix each frame, eased out over [DURATION_NS]. Matrices are row-major 4x4 device->ENU
 * (index = row * 4 + column), as `SensorManager` writes them. No allocation; render thread only.
 */
class RollEase {
    private var startRollDeg = 0f
    private var startNs = 0L

    /** Whether some roll is still being taken out. */
    val isActive: Boolean get() = startRollDeg != 0f

    /** Starts easing out the roll of [live] against its levelled twin [level], at [nowNs]. */
    fun begin(live: FloatArray, level: FloatArray, nowNs: Long) {
        startRollDeg = rollAgainst(live, level)
        startNs = nowNs
    }

    /** Stops at once: the next frame is level, or live again. */
    fun clear() {
        startRollDeg = 0f
    }

    /** Turns the level matrix [m] by the roll left at [nowNs], in place. */
    fun applyTo(m: FloatArray, nowNs: Long) {
        if (startRollDeg == 0f) return
        val remaining = weight(nowNs - startNs)
        if (remaining <= 0f) {
            startRollDeg = 0f
            return
        }
        applyRoll(m, startRollDeg * remaining)
    }

    companion object {
        /** How long the view takes to turn level. */
        const val DURATION_NS = 350_000_000L

        /**
         * How much of the starting roll is left [elapsedNs] in: a cubic ease-out, so the sky starts
         * turning at once and settles gently. 0 from [DURATION_NS] on.
         */
        fun weight(elapsedNs: Long): Float {
            if (elapsedNs >= DURATION_NS) return 0f
            val left = 1f - elapsedNs.coerceAtLeast(0L).toFloat() / DURATION_NS
            return left * left * left
        }

        /**
         * The angle [live] is turned about the camera axis from [level], in degrees: the device's
         * right axis of [live] measured in [level]'s right/up plane.
         */
        fun rollAgainst(live: FloatArray, level: FloatArray): Float {
            val onX = live[0] * level[0] + live[4] * level[4] + live[8] * level[8]
            val onY = live[0] * level[1] + live[4] * level[5] + live[8] * level[9]
            return Math.toDegrees(atan2(onY.toDouble(), onX.toDouble())).toFloat()
        }

        /** Turns [m]'s device right and up axes by [rollDeg] about its camera axis, in place. */
        fun applyRoll(m: FloatArray, rollDeg: Float) {
            val rad = Math.toRadians(rollDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            for (row in 0..2) {
                val x = m[row * 4]
                val y = m[row * 4 + 1]
                m[row * 4] = c * x + s * y
                m[row * 4 + 1] = -s * x + c * y
            }
        }
    }
}

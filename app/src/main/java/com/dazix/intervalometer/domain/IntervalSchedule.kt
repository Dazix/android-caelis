package com.dazix.intervalometer.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Drift-free schedule: every shot time is an absolute offset derived from the session start instant,
 * never a cumulative chain of sleeps, so one late shot cannot shift the rest of the series.
 *
 * Shot indices are 0-based and shot 0 fires at `sessionStartElapsed + startDelayMs`.
 * `intervalForShot(i, ..)` is the gap between shot `i` and shot `i + 1`.
 */
class IntervalSchedule(private val config: SessionConfig) {

    private val floorMs: Long = max(1L, config.minIntervalFloorMs)

    /** `offsets[i]` = ideal ms from shot 0 to shot i. Grown on demand; only needed when ramping. */
    private val offsets = ArrayList<Long>().apply { add(0L) }
    private val lock = Any()

    fun intervalForShot(index: Int, elapsedSinceFirstShotMs: Long): Long {
        val ramp = config.ramp ?: return config.intervalMs.coerceAtLeast(floorMs)
        val p = progressFor(ramp, index, elapsedSinceFirstShotMs)
        val from = config.intervalMs
        val to = ramp.endIntervalMs
        val exponentialUsable = ramp.curve == RampCurve.EXPONENTIAL && from > 0L && to > 0L
        val raw = if (exponentialUsable) {
            val a = ln(from.toDouble())
            val b = ln(to.toDouble())
            exp(a + (b - a) * p)
        } else {
            from + (to - from) * p
        }
        return raw.roundToLong().coerceAtLeast(floorMs)
    }

    fun targetElapsedFor(index: Int, sessionStartElapsed: Long): Long =
        sessionStartElapsed + config.startDelayMs + offsetForShot(index.coerceAtLeast(0))

    private fun progressFor(ramp: RampSpec, index: Int, elapsedSinceFirstShotMs: Long): Double {
        val p = when (ramp.domain) {
            RampDomain.SHOT_INDEX ->
                index.toDouble() / max(1, ramp.overShots ?: 0).toDouble()
            RampDomain.ELAPSED_TIME ->
                elapsedSinceFirstShotMs.toDouble() / max(1L, ramp.overDurationMs ?: 0L).toDouble()
        }
        return p.coerceIn(0.0, 1.0)
    }

    private fun offsetForShot(index: Int): Long {
        if (config.ramp == null) return index * config.intervalMs.coerceAtLeast(floorMs)
        synchronized(lock) {
            while (offsets.size <= index) {
                val previous = offsets.size - 1
                val accumulated = offsets[previous]
                offsets.add(accumulated + intervalForShot(previous, accumulated))
            }
            return offsets[index]
        }
    }
}

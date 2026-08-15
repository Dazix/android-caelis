package com.dazix.intervalometer.domain

/**
 * Pure decision logic so it is unit-testable without Android. Sensor readings that are `null` mean
 * "unknown right now" and never stop a session.
 */
class SessionPlanner(val config: SessionConfig) {

    fun stopReasonFor(
        shotsTaken: Int,
        elapsedMs: Long,
        batteryPercent: Int?,
        freeBytes: Long?,
    ): StopReason? {
        val stop = config.stop
        val maxShots = stop.maxShots
        if (maxShots != null && maxShots > 0 && shotsTaken >= maxShots) return StopReason.COMPLETED
        val maxDuration = stop.maxDurationMs
        if (maxDuration != null && maxDuration > 0L && elapsedMs >= maxDuration) return StopReason.MAX_DURATION
        val minBattery = stop.minBatteryPercent
        if (minBattery != null && batteryPercent != null && batteryPercent < minBattery) return StopReason.LOW_BATTERY
        val minFree = stop.minFreeBytes
        if (minFree != null && freeBytes != null && freeBytes < minFree) return StopReason.LOW_STORAGE
        return null
    }
}

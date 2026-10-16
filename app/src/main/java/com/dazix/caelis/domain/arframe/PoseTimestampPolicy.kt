package com.dazix.caelis.domain.arframe

/**
 * Which instant the AR overlay is drawn at: the camera image's, not the newest sensor sample's.
 *
 * The camera picture on screen is tens of milliseconds old by the time it is composited — exposure,
 * ISP, buffer queue — while the rotation vector is a few milliseconds old. Drawing the newest pose
 * over that picture made the sun's path and the terrain silhouette run ahead of the photographed
 * ridge during every turn and settle back when the phone stopped. An ARCore renderer does not
 * show this because it draws the camera image and the scene from the same frame; the sensor path
 * gets the same property by drawing the pose interpolated at the image's own timestamp.
 *
 * All timestamps are `SystemClock.elapsedRealtimeNanos` based, the clock both `SensorEvent` and a
 * camera reporting `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` use. Zero means "no usable instant":
 * the caller draws the live pose.
 */
object PoseTimestampPolicy {

    /**
     * A camera frame older than this is a stalled or stopped camera, not a picture on screen; the
     * overlay then follows the phone live rather than freezing on a pose from a second ago.
     */
    const val MAX_FRAME_AGE_NS = 250_000_000L

    /**
     * The middle of the frame's light gathering: the start of exposure the camera reports, plus half
     * the exposure, plus half the rolling-shutter skew (the top row starts that much before the
     * bottom row). An unreported or nonsensical part counts as zero.
     */
    fun frameInstant(sensorTimestampNs: Long, exposureTimeNs: Long, rollingShutterSkewNs: Long): Long {
        if (sensorTimestampNs <= 0L) return 0L
        val exposure = exposureTimeNs.coerceIn(0L, MAX_FRAME_AGE_NS)
        val skew = rollingShutterSkewNs.coerceIn(0L, MAX_FRAME_AGE_NS)
        return sensorTimestampNs + (exposure + skew) / 2
    }

    /**
     * The sensor instant to draw at, given the newest frame's [frameInstantNs], the current time
     * [nowNs], and [displayLagNs] — how much later than that frame the picture composited with this
     * overlay frame tends to be. 0 means draw live.
     */
    fun renderTimestamp(frameInstantNs: Long, nowNs: Long, displayLagNs: Long): Long {
        if (frameInstantNs <= 0L) return 0L
        val age = nowNs - frameInstantNs
        if (age < 0L || age > MAX_FRAME_AGE_NS) return 0L
        return (frameInstantNs + displayLagNs).coerceIn(frameInstantNs - MAX_FRAME_AGE_NS, nowNs)
    }
}

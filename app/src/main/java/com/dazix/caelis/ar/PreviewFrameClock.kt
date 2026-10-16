package com.dazix.caelis.ar

import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import com.dazix.caelis.domain.arframe.PoseTimestampPolicy

/**
 * When the newest camera preview frame was exposed, for drawing the AR overlay at that instant
 * ([PoseTimestampPolicy]).
 *
 * Written from the camera's capture callback thread, read on the main thread by the frame loop;
 * single volatile longs need no lock. Only usable when the camera timestamps frames on the
 * `elapsedRealtimeNanos` clock the sensors use, which [CameraPreview] checks once on binding.
 *
 * It serves two renderers. With the CameraX `PreviewView` the picture arrives by a path this app
 * cannot see into, so [renderTimestampNs] estimates the instant of the newest completed frame.
 * With [CameraFrameSource] the app holds every image itself and knows its exact sensor timestamp;
 * what it still needs from here is the exposure and rolling-shutter skew, which only the capture
 * result carries ([frameInstantFor]).
 */
class PreviewFrameClock {

    @Volatile
    private var frameInstantNs = 0L

    // The newest exposure and skew, not those of one particular frame: the image reader delivers a
    // frame and its capture result on different threads in no fixed order, and both change slowly
    // (auto exposure) compared with the few milliseconds a mismatch would span.
    @Volatile
    private var exposureNs = 0L

    @Volatile
    private var skewNs = 0L

    @Volatile
    internal var sharesSensorClock = false

    internal fun onCaptureCompleted(result: CaptureResult) {
        val start = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        exposureNs = exposure
        skewNs = skew
        frameInstantNs = PoseTimestampPolicy.frameInstant(start, exposure, skew)
    }

    /** The sensor instant to draw the overlay at now, or 0 for the live pose. Allocation-free. */
    fun renderTimestampNs(): Long {
        if (!sharesSensorClock) return 0L
        return PoseTimestampPolicy.renderTimestamp(frameInstantNs, SystemClock.elapsedRealtimeNanos(), displayLagNs)
    }

    /**
     * The middle of the light gathering of the frame whose exposure started at [sensorTimestampNs]
     * (`Image.getTimestamp`), or 0 when the camera's clock is not the sensors' and the overlay has
     * to follow the phone live. Allocation-free.
     */
    fun frameInstantFor(sensorTimestampNs: Long): Long {
        if (!sharesSensorClock) return 0L
        return PoseTimestampPolicy.frameInstant(sensorTimestampNs, exposureNs, skewNs)
    }

    companion object {
        /**
         * How much later than the newest completed frame the picture actually on screen with an
         * overlay frame is, for the `PreviewView` path. Debug builds can set it from the shell
         * (`DebugSessionReceiver`) to tune it against a screen recording. The Filament camera
         * background composites image and overlay in one frame and does not use it.
         */
        @Volatile
        var displayLagNs: Long = 0L
    }
}

package com.dazix.caelis.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dazix.caelis.BuildConfig
import com.dazix.caelis.domain.stability.HandheldDetector
import com.dazix.caelis.domain.stability.HandheldState

/** Something that says whether the phone is held in the hand; the seam a test drives. */
interface MotionSource {
    /** False on a phone without a gyroscope: the check then stays silent instead of guessing. */
    val isAvailable: Boolean

    /**
     * Starts listening and reports every *change* of state on the main thread, starting from
     * [HandheldState.UNKNOWN]. Calling it again while started replaces the callback and starts the
     * window over.
     */
    fun start(onState: (HandheldState) -> Unit)

    /** Stops listening; no callback arrives after it returns. Safe to call when not started. */
    fun stop()
}

/**
 * The gyroscope fed into a [HandheldDetector].
 *
 * The sensor is the non-wake-up one on purpose: nothing is worth waking the CPU for here. While a
 * session shoots the panel keeps the screen on, and a dark screen pauses the session anyway, so the
 * only time samples stop is when nobody is shooting.
 */
class MotionMonitor(context: Context) : MotionSource, SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val handler = Handler(Looper.getMainLooper())
    private val detector = HandheldDetector()
    private var onState: ((HandheldState) -> Unit)? = null
    private var reported = HandheldState.UNKNOWN
    private var lastLogMs = 0L

    override val isAvailable: Boolean get() = gyroscope != null

    override fun start(onState: (HandheldState) -> Unit) {
        val manager = sensorManager ?: return
        val sensor = gyroscope ?: return
        if (this.onState != null) manager.unregisterListener(this)
        detector.reset()
        reported = HandheldState.UNKNOWN
        this.onState = onState
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    override fun stop() {
        if (onState == null) return
        sensorManager?.unregisterListener(this)
        onState = null
        detector.reset()
        reported = HandheldState.UNKNOWN
    }

    override fun onSensorChanged(event: SensorEvent) {
        val callback = onState ?: return
        val values = event.values
        if (values.size < 3) return
        // Event timestamps are elapsedRealtimeNanos on every device this app supports; the detector
        // only compares them with each other.
        val state = detector.add(event.timestamp / 1_000_000L, values[0], values[1], values[2])
        if (BuildConfig.DEBUG) logForTuning(state)
        if (state == reported) return
        reported = state
        callback(state)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Once a second in debug builds: what the thresholds in [HandheldDetector] are tuned against. */
    private fun logForTuning(state: HandheldState) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs < LOG_EVERY_MS) return
        lastLogMs = now
        Log.d(TAG, "state=$state above=${"%.2f".format(detector.aboveFraction)} " +
            "median=${"%.3f".format(detector.medianDegPerSec())}deg/s")
    }

    private companion object {
        const val TAG = "CaelisMotion"
        const val LOG_EVERY_MS = 1_000L
    }
}

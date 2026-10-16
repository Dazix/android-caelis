package com.dazix.caelis

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.CameraBackgroundSink
import com.dazix.caelis.ar.CameraImageSink
import com.dazix.caelis.ar.OrientationTracker
import com.dazix.caelis.ar.PictureFeed
import com.dazix.caelis.ar.pose.ArCoreHost
import com.dazix.caelis.ar.pose.ArCoreSessionController
import com.dazix.caelis.ar.pose.ArFrameSample
import com.dazix.caelis.ar.pose.ArFrameSource
import com.dazix.caelis.ar.pose.ArLumaCapture
import com.dazix.caelis.ar.pose.ArTrackingState
import com.dazix.caelis.ar.pose.PoseIndicator
import com.dazix.caelis.ar.pose.SensorPose
import com.dazix.caelis.ar.pose.SensorPoseSource
import com.dazix.caelis.ar.pose.SkyPoseFrame
import com.dazix.caelis.ar.pose.SkyPoseRig
import com.dazix.caelis.domain.arframe.ArWorldAlignment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hand-off from ARCore's pose to the phone's sensors when ARCore stops tracking, on the real
 * sensor tracker of a real phone and with ARCore replaced by a script.
 *
 * ARCore is a fake on purpose: a dark sky or a covered lens pauses it within moments, which is
 * exactly the situation that cannot be staged repeatably, and what is under test is this app's
 * reaction (draw with the sensors, at once, without a visible jump), not Google's tracking. The
 * script is made consistent with the real sensors: each ARCore frame is built from the tracker's own
 * pose at that instant and an arbitrary ARCore-world heading, so a correct alignment must find that
 * heading and the two poses must agree to a fraction of a degree. Nothing here constructs a
 * `Session`; the controller's process-wide counters stay at zero throughout, which the tests assert.
 *
 * Everything runs on the main thread, where the sensor callbacks arrive and where the rig lives.
 */
@RunWith(AndroidJUnit4::class)
class PoseHandoffDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tracker: OrientationTracker

    /** The real tracker, except that the compass is declared good: the hand-off is what is tested. */
    private lateinit var sensorPose: SensorPose

    @Before
    fun startSensors() {
        ArCoreSessionController.resetCountersForTest()
        main.post {
            tracker = OrientationTracker(context)
            tracker.start()
            val real = SensorPoseSource(tracker)
            sensorPose = object : SensorPose by real {
                override val headingAccuracy: Int = 3
            }
        }
        flushMain()
    }

    @After
    fun stopSensors() {
        main.post { tracker.stop() }
        flushMain()
    }

    private fun flushMain() {
        val latch = CountDownLatch(1)
        main.post { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private class ScriptedFrames : ArFrameSource {
        override var sessionEpoch = 0
        var next: ArFrameSample? = null
        override fun poll(out: ArFrameSample): Boolean {
            val s = next ?: return false
            next = null
            out.clear()
            out.timestampNs = s.timestampNs
            out.tracking = s.tracking
            out.sessionEpoch = sessionEpoch
            s.devicePose.copyInto(out.devicePose)
            out.fovHorizontalDegrees = s.fovHorizontalDegrees
            return true
        }
        override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) = Unit
        override fun copyLuma(): ArLumaCapture? = null
    }

    private class ScriptedHost(override val frames: ScriptedFrames) : ArCoreHost {
        var starts = 0
        override suspend fun availability() = com.dazix.caelis.ar.pose.ArAvailability.USABLE
        override suspend fun start(): Boolean { starts++; frames.sessionEpoch++; return true }
        override fun pause() = Unit
        override fun close() = Unit
        override suspend fun revalidateConsent() = true
    }

    private class NoPicture : PictureFeed {
        override fun latch(sink: CameraImageSink) = false
        override val hasImage = false
        override val bufferWidth = 0
        override val bufferHeight = 0
        override val rotationDegrees = 0
        override fun poseTimestampNs() = 0L
    }

    private class NoSink : CameraBackgroundSink {
        override fun submit(image: android.media.Image) = false
        override fun submitBuffer(buffer: android.hardware.HardwareBuffer, width: Int, height: Int) = false
        override fun setGeometry(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int, viewportWidth: Float, viewportHeight: Float) = Unit
        override fun setUv(u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float) = Unit
    }

    /** ARCore's world is this many degrees off ENU; the alignment has to find it from the sensors. */
    private val worldYawRad = Math.toRadians(137.0)

    private fun rz(a: Double) = floatArrayOf(cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat(), 0f, 0f, 0f, 1f)

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9).also { o ->
        for (r in 0 until 3) for (c in 0 until 3) o[r * 3 + c] = (0 until 3).sumOf { k -> (a[r * 3 + k] * b[k * 3 + c]).toDouble() }.toFloat()
    }

    private fun transpose(a: FloatArray) = FloatArray(9) { i -> a[(i % 3) * 3 + i / 3] }

    /**
     * What ARCore would report for the phone's real pose at [timestampNs] in a world turned by
     * [worldYawRad]: deviceToEnu = Rz(psi) * EUS_TO_ENU * deviceToArWorld, solved for deviceToArWorld.
     * Null until the sensor history can answer for that instant.
     */
    private fun arCorePoseFor(timestampNs: Long): FloatArray? {
        val reference = FloatArray(16)
        if (!sensorPose.referenceMatrix(timestampNs, reference)) return null
        val ref3 = FloatArray(9) { i -> reference[(i / 3) * 4 + i % 3] }
        return mul(transpose(ArWorldAlignment.EUS_TO_ENU), mul(rz(-worldYawRad), ref3))
    }

    /** The angle between two device -> ENU rotations (4x4 row-major), in degrees. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Double {
        var trace = 0.0
        for (r in 0 until 3) for (c in 0 until 3) trace += (a[r * 4 + c] * b[r * 4 + c]).toDouble()
        return Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    /** Calls [body] every ~25 ms on the main thread until it returns true; false if it never did. */
    private fun runOnMainUntil(timeoutMs: Long, body: () -> Boolean): Boolean {
        val done = CountDownLatch(1)
        var success = false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val tick = object : Runnable {
            override fun run() {
                if (body()) {
                    success = true
                    done.countDown()
                } else if (System.nanoTime() > deadline) {
                    done.countDown()
                } else {
                    main.postDelayed(this, 25)
                }
            }
        }
        main.post(tick)
        done.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        return success
    }

    @Test
    fun arCorePoseHandsBackToTheSensorsWhenTrackingIsPaused() {
        val frames = ScriptedFrames()
        val host = ScriptedHost(frames)
        lateinit var rig: SkyPoseRig
        main.post {
            rig = SkyPoseRig(
                scope = CoroutineScope(Dispatchers.Unconfined),
                sensorPose = sensorPose,
                cameraX = NoPicture(),
                hostFactory = { host },
                onFov = {},
            )
            rig.consented = true
            rig.cameraPermission = true
        }
        flushMain()

        val out = SkyPoseFrame()
        val sink = NoSink()
        var released = false
        fun advance() = rig.advance(sink, 1080f, 2340f, 0, 1f, true, true, out)
        fun pushFrame(tracking: ArTrackingState): Boolean {
            // A frame exposed a few frames ago, as a camera image always is by the time it is drawn.
            val ts = android.os.SystemClock.elapsedRealtimeNanos() - 30_000_000L
            val pose = arCorePoseFor(ts) ?: return false
            frames.next = ArFrameSample().also {
                it.timestampNs = ts
                it.tracking = tracking
                pose.copyInto(it.devicePose)
                it.fovHorizontalDegrees = 55f
            }
            return true
        }

        // 1. ARCore takes the camera, then (after its heading has been measured against the real
        // sensors) the pose.
        val tookPose = runOnMainUntil(20_000) {
            if (rig.cameraXEnabled.value.not() && !released) {
                released = true
                rig.onCameraXReleased()
            }
            pushFrame(ArTrackingState.TRACKING)
            advance()
            rig.indicator.value == PoseIndicator.ARCORE
        }
        assertTrue("ARCore never took the pose from the sensors (indicator ${rig.indicator.value})", tookPose)
        assertTrue("the pose to draw is ARCore's own matrix", out.useRigMatrix)
        assertEquals(1, host.starts)

        // 2. Tracking pauses (a dark sky): the sensors draw on that very frame, with no jump.
        val arMatrix = out.matrix.copyOf()
        var handedBack = false
        var sensorMatrix = FloatArray(16)
        val ok = runOnMainUntil(5_000) {
            if (!pushFrame(ArTrackingState.PAUSED)) return@runOnMainUntil false
            advance()
            handedBack = rig.indicator.value == PoseIndicator.SENSOR && !out.useRigMatrix
            if (handedBack) {
                sensorMatrix = FloatArray(16)
                sensorPose.renderMatrix(out.poseTimestampNs, sensorMatrix)
            }
            handedBack
        }
        assertTrue("the pose was not handed back to the sensors when tracking paused", ok && handedBack)
        assertFalse(out.useRigMatrix)
        assertTrue("the sensors are asked for the image's instant", out.poseTimestampNs > 0L)
        val jump = angleBetween(arMatrix, sensorMatrix)
        // A few hundredths of a second of real phone motion separate the two instants, so the bound is
        // generous; a lost or doubled yaw would be tens of degrees.
        assertTrue("the overlay jumped $jump degrees on hand-back", jump < 1.5)

        assertEquals("a Session was constructed", 0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(0, ArCoreSessionController.availabilityCheckCount)
        assertEquals(0, ArCoreSessionController.installRequestCount)
    }

    @Test
    fun withConsentOffTheRigNeverAsksForArCore() {
        var hostRequested = false
        lateinit var rig: SkyPoseRig
        main.post {
            rig = SkyPoseRig(
                scope = CoroutineScope(Dispatchers.Unconfined),
                sensorPose = sensorPose,
                cameraX = NoPicture(),
                hostFactory = { hostRequested = true; ScriptedHost(ScriptedFrames()) },
                onFov = {},
            )
            rig.cameraPermission = true
        }
        flushMain()

        val out = SkyPoseFrame()
        val sink = NoSink()
        var frames = 0
        runOnMainUntil(1_500) {
            rig.advance(sink, 1080f, 2340f, 0, 1f, true, true, out)
            frames++
            false
        }
        assertTrue("the loop did not run", frames > 10)
        assertFalse("ARCore was asked for without consent", hostRequested)
        assertTrue("CameraX must keep the camera", rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertFalse(out.useRigMatrix)
        assertEquals(0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(0, ArCoreSessionController.availabilityCheckCount)
    }
}

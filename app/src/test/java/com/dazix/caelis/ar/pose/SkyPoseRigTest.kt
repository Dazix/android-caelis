package com.dazix.caelis.ar.pose

import android.media.Image
import com.dazix.caelis.ar.CameraBackgroundSink
import com.dazix.caelis.ar.CameraImageSink
import com.dazix.caelis.ar.FovResult
import com.dazix.caelis.ar.LumaFrame
import com.dazix.caelis.ar.PictureFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rig's decisions about the camera and the pose, with ARCore replaced by a script. What these
 * pin down is the *order* and the *conditions* of every hand-over, because a mistake there is not a
 * wrong pose but a camera that is open twice, or a session that outlives its consent.
 */
class SkyPoseRigTest {

    private val ms = 1_000_000L
    private val frame = 33 * ms

    private class FakeHost(override val frames: FakeFrameSource) : ArCoreHost {
        var startResult = true
        var starts = 0
        var pauses = 0
        var closes = 0
        var consentStillGiven = true
        var availabilityResult = ArAvailability.USABLE
        var availabilityAsks = 0

        override suspend fun availability(): ArAvailability {
            availabilityAsks++
            return availabilityResult
        }

        override suspend fun start(): Boolean {
            starts++
            if (startResult) frames.sessionEpoch++
            return startResult
        }

        override fun pause() { pauses++ }
        override fun close() { closes++ }

        override suspend fun revalidateConsent(): Boolean {
            if (!consentStillGiven) closes++
            return consentStillGiven
        }

    }

    private class FakePicture : PictureFeed {
        var latches = 0
        var latchResult = false
        override fun latch(sink: CameraImageSink): Boolean { latches++; return latchResult }
        override var hasImage = false
        override var bufferWidth = 0
        override var bufferHeight = 0
        override val rotationDegrees = 0
        override fun poseTimestampNs() = 0L
    }

    private class FakeSink : CameraBackgroundSink {
        var geometryCalls = 0
        var uvCalls = 0
        override fun submit(image: Image) = false
        override fun submitBuffer(buffer: android.hardware.HardwareBuffer, width: Int, height: Int) = false
        override fun setGeometry(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int, viewportWidth: Float, viewportHeight: Float) { geometryCalls++ }
        override fun setUv(u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float) { uvCalls++ }
    }

    private var now = 0L
    private var worldYaw = 80.0
    private val sensor = FakeSensorPose(worldYawDegrees = { worldYaw }, deviceAt = { PoseWorld.device((it / frame).toInt()) })
    private val frames = FakeFrameSource()
    private val host = FakeHost(frames)
    private val picture = FakePicture()
    private val sink = FakeSink()
    private var hostsCreated = 0
    private val fovs = mutableListOf<FovResult>()
    private val out = SkyPoseFrame()

    private val rig = SkyPoseRig(
        scope = CoroutineScope(Dispatchers.Unconfined),
        sensorPose = sensor,
        cameraX = picture,
        hostFactory = { hostsCreated++; host },
        onFov = { fovs += it },
        clockNs = { now },
    )

    private fun step(
        zoom: Float = 1f,
        pictureEnabled: Boolean = true,
        livePoseAllowed: Boolean = true,
        push: ArTrackingState? = null,
        withSink: Boolean = true,
    ) {
        now += frame
        if (push != null) frames.push(arSample(now, push, deviceIndex = (now / frame).toInt()))
        rig.advance(
            background = if (withSink) sink else null,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            displayRotation = 0,
            zoomRatio = zoom,
            pictureEnabled = pictureEnabled,
            livePoseAllowed = livePoseAllowed,
            out = out,
        )
    }

    private fun allow() {
        rig.consented = true
        rig.cameraPermission = true
    }

    /** Takes the camera from CameraX and starts ARCore, as the preview composable would report it. */
    private fun enterArCore() {
        allow()
        step() // ARCore is asked whether it can run here; CameraX still has the camera
        step()
        assertFalse("CameraX is asked to let go first", rig.cameraXEnabled.value)
        rig.onCameraXReleased()
        step()
    }

    /** Streams good frames until ARCore draws. */
    private fun converge() {
        for (i in 0 until 120) {
            step(push = ArTrackingState.TRACKING)
            if (rig.indicator.value == PoseIndicator.ARCORE) return
        }
        throw AssertionError("ARCore never took the pose")
    }

    @Test
    fun `with consent off ARCore is never created, started or asked for the camera`() {
        rig.cameraPermission = true
        for (i in 0 until 300) step(zoom = if (i % 50 < 25) 1f else 2f)
        assertEquals("the host is only built when ARCore is wanted", 0, hostsCreated)
        assertEquals(0, host.starts)
        assertTrue("CameraX keeps the camera", rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertEquals("the CameraX picture is latched every frame", 300, picture.latches)
        assertFalse(out.useRigMatrix)
    }

    @Test
    fun `without the camera permission ARCore does not start either`() {
        rig.consented = true
        for (i in 0 until 50) step()
        assertEquals(0, hostsCreated)
        assertTrue(rig.cameraXEnabled.value)
    }

    @Test
    fun `the camera goes from CameraX to ARCore in order, and ARCore waits until CameraX says it is free`() {
        allow()
        step()
        assertEquals("ARCore is asked whether it can run before anything moves", 1, host.availabilityAsks)
        assertTrue("CameraX still has the camera while that is asked", rig.cameraXEnabled.value)
        assertEquals(0, host.starts)

        step()
        assertFalse(rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SWITCHING, rig.indicator.value)
        assertEquals("not before the camera is released", 0, host.starts)
        repeat(20) { step() }
        assertEquals(0, host.starts)

        rig.onCameraXReleased()
        step()
        assertEquals(1, hostsCreated)
        assertEquals(1, host.starts)
        assertEquals("asked once, not on every start", 1, host.availabilityAsks)
        assertFalse("CameraX stays unbound while ARCore owns the camera", rig.cameraXEnabled.value)
    }

    @Test
    fun `a camera that never reports closed is assumed free after two seconds`() {
        allow()
        step()
        step() // CameraX is asked to let go; the clock starts here
        repeat(58) { step() }
        assertEquals(0, host.starts)
        repeat(10) { step() }
        assertEquals(1, host.starts)
    }

    @Test
    fun `a phone that cannot run ARCore keeps its camera and is not asked again`() {
        host.availabilityResult = ArAvailability.UNAVAILABLE
        allow()
        repeat(2000) { step() } // over a minute
        assertEquals(1, host.availabilityAsks)
        assertEquals(0, host.starts)
        assertTrue("CameraX never gave the camera up", rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
    }

    @Test
    fun `switching consent off and on asks a phone that could not run ARCore again`() {
        host.availabilityResult = ArAvailability.UNAVAILABLE
        allow()
        repeat(10) { step() }
        assertEquals(1, host.availabilityAsks)

        rig.consented = false
        step()
        host.availabilityResult = ArAvailability.USABLE
        rig.consented = true
        // (The owner policy's own pause before coming back to ARCORE applies here too.)
        repeat(100) { step() }
        assertEquals(2, host.availabilityAsks)
        assertFalse("now it can have the camera", rig.cameraXEnabled.value)
    }

    @Test
    fun `an availability that is not known yet is asked again after the pause a failed start gets`() {
        host.availabilityResult = ArAvailability.TRANSIENT
        allow()
        repeat(10) { step() }
        assertEquals(1, host.availabilityAsks)
        assertTrue(rig.cameraXEnabled.value)

        host.availabilityResult = ArAvailability.USABLE
        repeat(1000) { step() } // 33 s
        assertTrue("asked again", host.availabilityAsks >= 2)
        assertFalse(rig.cameraXEnabled.value)
    }

    @Test
    fun `ARCore is not even asked about while the zoom is not 1x`() {
        allow()
        repeat(10) { step(zoom = 2f) }
        assertEquals(0, host.availabilityAsks)
        assertEquals(0, hostsCreated)
        assertTrue(rig.cameraXEnabled.value)
    }

    @Test
    fun `ARCore owns the picture at once and the pose only once its heading has converged`() {
        enterArCore()
        // Tracking, but the heading has not converged: the sensors draw, at the ARCore image's instant.
        for (i in 0 until 10) step(push = ArTrackingState.TRACKING)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertFalse(out.useRigMatrix)
        assertEquals(now, out.poseTimestampNs)

        converge()
        assertEquals(PoseIndicator.ARCORE, rig.indicator.value)
        assertTrue(out.useRigMatrix)
        // The matrix is the sensors' north on ARCore's own frame.
        val expected = PoseWorld.as4x4(PoseWorld.truth(worldYaw, PoseWorld.device((now / frame).toInt())))
        for (k in 0 until 16) assertEquals("element $k", expected[k], out.matrix[k], 3e-3f)
    }

    @Test
    fun `a paused session hands the pose to the sensors without touching the camera`() {
        enterArCore()
        converge()
        val pausesBefore = host.pauses
        step(push = ArTrackingState.PAUSED)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertFalse(out.useRigMatrix)
        assertTrue("the sensors are asked for the image's own instant", out.poseTimestampNs > 0L)
        assertEquals("the camera stays with ARCore: only the pose changed", pausesBefore, host.pauses)
        assertFalse(rig.cameraXEnabled.value)
    }

    @Test
    fun `tracking that comes back is not trusted until the heading has been measured again`() {
        enterArCore()
        converge()
        step(push = ArTrackingState.PAUSED)
        worldYaw += 40.0 // relocalisation turned the world
        for (i in 0 until 10) {
            step(push = ArTrackingState.TRACKING)
            assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        }
        converge()
        val expected = PoseWorld.as4x4(PoseWorld.truth(worldYaw, PoseWorld.device((now / frame).toInt())))
        for (k in 0 until 16) assertEquals("element $k", expected[k], out.matrix[k], 3e-3f)
    }

    @Test
    fun `a zoom hands the camera back to CameraX after pausing the session`() {
        enterArCore()
        converge()
        step(zoom = 2f)
        assertEquals("the session is paused first", 1, host.pauses)
        assertTrue("then the camera is offered back", rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertEquals(1, hostsCreated)
    }

    @Test
    fun `back at 1x ARCore comes back after the wait, not at the first frame`() {
        enterArCore()
        step(zoom = 2f)
        assertTrue(rig.cameraXEnabled.value)
        step(zoom = 1f)
        assertTrue("still CameraX right after zooming back", rig.cameraXEnabled.value)
        repeat(60) { step(zoom = 1f) }
        assertFalse("after the wait ARCore wants the camera again", rig.cameraXEnabled.value)
    }

    @Test
    fun `a session that fails to start gives the camera back and is not retried for a while`() {
        host.startResult = false
        allow()
        step() // availability is asked
        step() // CameraX is asked to let go
        rig.onCameraXReleased()
        step()
        assertEquals(1, host.starts)
        assertTrue("CameraX has the camera back", rig.cameraXEnabled.value)

        repeat(300) { step() } // ~10 s
        assertEquals("not retried inside the backoff", 1, host.starts)

        for (i in 0 until 800) {
            step()
            if (!rig.cameraXEnabled.value) rig.onCameraXReleased()
        }
        assertTrue("retried once the backoff is over", host.starts >= 2)
    }

    @Test
    fun `withdrawing consent in Settings stops the session at the next frame`() {
        enterArCore()
        converge()
        rig.consented = false
        step()
        // Closed, not just paused: the phase has left ARCORE, so the guard tick would never look at
        // this session again, and a paused one is still bound to Google's component.
        assertEquals(1, host.closes)
        assertEquals(0, host.pauses)
        assertTrue(rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
    }

    @Test
    fun `withdrawing consent before the session starts leaves nothing open`() {
        allow()
        step()
        step()
        rig.consented = false
        rig.onCameraXReleased()
        step()
        assertEquals("never started, nothing to leave open", 0, host.starts)
        assertEquals(0, host.pauses)
        assertTrue(rig.cameraXEnabled.value)
    }

    @Test
    fun `a zoom pauses the session but keeps it, so coming back does not pay for a restart`() {
        enterArCore()
        converge()
        step(zoom = 2f)
        assertEquals(1, host.pauses)
        assertEquals("consent is intact: nothing is closed", 0, host.closes)
    }

    @Test
    fun `after ARCore gives the camera back CameraX's old geometry is not applied to ARCore's last image`() {
        picture.hasImage = true
        picture.bufferWidth = 1920
        picture.bufferHeight = 1080
        picture.latchResult = true
        enterArCore()
        converge()
        val before = sink.geometryCalls

        picture.latchResult = false // CameraX is not bound yet: no new image
        step(zoom = 2f)
        assertTrue(rig.cameraXEnabled.value)
        repeat(30) { step(zoom = 2f) }
        assertEquals("the stream still holds ARCore's image: CameraX's size must not be fitted to it", before, sink.geometryCalls)

        picture.latchResult = true // the first CameraX image arrives
        step(zoom = 2f)
        assertEquals("and now it describes what is on screen", before + 1, sink.geometryCalls)
    }

    @Test
    fun `while CameraX has the camera its geometry is applied every frame it has an image`() {
        picture.hasImage = true
        picture.bufferWidth = 1920
        picture.bufferHeight = 1080
        picture.latchResult = true
        repeat(5) { step() }
        assertEquals(5, sink.geometryCalls)
    }

    @Test
    fun `an opaque sky pauses ARCore after a grace and leaves the camera unbound`() {
        enterArCore()
        converge()
        repeat(30) { step(pictureEnabled = false) } // 1 s: inside the grace
        assertEquals(0, host.pauses)

        repeat(40) { step(pictureEnabled = false) }
        assertEquals("nothing consumes the session any more", 1, host.pauses)
        assertEquals(0, host.closes)
        assertFalse("CameraX has no use for the camera either", rig.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
        assertFalse(out.useRigMatrix)
        val polls = frames.polls
        repeat(10) { step(pictureEnabled = false) }
        assertEquals(polls, frames.polls)
        assertEquals("paused once, not on every frame", 1, host.pauses)
    }

    @Test
    fun `a sky that turns opaque only briefly does not thrash the session`() {
        enterArCore()
        converge()
        repeat(40) {
            repeat(30) { step(pictureEnabled = false) }
            step(pictureEnabled = true, push = ArTrackingState.TRACKING)
        }
        assertEquals(0, host.pauses)
        assertEquals(1, host.starts)
    }

    @Test
    fun `ARCore starts again when the picture comes back after the sky was opaque`() {
        enterArCore()
        converge()
        repeat(80) { step(pictureEnabled = false) }
        assertEquals(1, host.pauses)

        step(pictureEnabled = true)
        step(pictureEnabled = true)
        assertEquals("resumed straight away, CameraX was never bound in between", 2, host.starts)
        assertFalse(rig.cameraXEnabled.value)
        converge()
        assertEquals(PoseIndicator.ARCORE, rig.indicator.value)
    }

    @Test
    fun `a zoom while parked hands the camera to CameraX without closing the session`() {
        enterArCore()
        converge()
        repeat(80) { step(pictureEnabled = false) }
        step(zoom = 2f, pictureEnabled = false)
        assertTrue(rig.cameraXEnabled.value)
        assertEquals(0, host.closes)
        assertEquals(1, hostsCreated)
    }

    @Test
    fun `withdrawing consent while parked closes the session`() {
        enterArCore()
        converge()
        repeat(80) { step(pictureEnabled = false) }
        rig.consented = false
        step(pictureEnabled = false)
        assertEquals(1, host.closes)
        assertTrue(rig.cameraXEnabled.value)
    }

    @Test
    fun `the guard tick closes a session whose consent was withdrawn without waiting for a frame`() {
        enterArCore()
        converge()
        host.consentStillGiven = false
        runBlocking { rig.guardTick() }
        assertEquals("the host closed it itself when consent was gone", 1, host.closes)
        assertTrue("and the camera went back to CameraX", rig.cameraXEnabled.value)
    }

    @Test
    fun `the guard tick does nothing while ARCore is not running`() {
        host.consentStillGiven = false
        runBlocking { rig.guardTick() }
        assertEquals(0, host.closes)
        assertEquals(0, hostsCreated)
    }

    @Test
    fun `a session that delivers nothing is handed back to CameraX`() {
        enterArCore()
        repeat(100) { step() } // 3.3 s of silence
        assertFalse(rig.cameraXEnabled.value)
        repeat(30) { step() }
        assertTrue("after four seconds of silence the camera goes back", rig.cameraXEnabled.value)
        assertEquals(1, host.pauses)
    }

    @Test
    fun `leaving the activity pauses ARCore and returning starts it again with no CameraX in between`() {
        enterArCore()
        converge()
        val enabledValues = mutableListOf<Boolean>()
        rig.onLifecyclePause()
        enabledValues += rig.cameraXEnabled.value
        assertEquals(1, host.pauses)

        rig.onLifecycleResume()
        step()
        enabledValues += rig.cameraXEnabled.value
        assertEquals("started again", 2, host.starts)
        assertEquals("CameraX was never bound in between", listOf(false, false), enabledValues)
        assertEquals("a new session is a new world: the pose is the sensors' until re-measured", PoseIndicator.SENSOR, rig.indicator.value)
    }

    @Test
    fun `a pause before ARCore has started has nothing to stop`() {
        allow()
        step() // availability is asked
        step() // CameraX is asked to let go
        rig.onCameraXReleased()
        rig.onLifecyclePause() // phase is RELEASING: nothing to stop, and nothing started yet
        assertEquals(0, host.pauses + host.closes)
        assertEquals(0, host.starts)
    }

    @Test
    fun `CameraX's field of view is forwarded only while CameraX is the camera`() {
        rig.onCameraXFov(FovResult(60f, true))
        assertEquals(1, fovs.size)

        enterArCore()
        rig.onCameraXFov(FovResult(33f, true))
        assertEquals("a late callback of the unbound preview is dropped", 1, fovs.size)

        step(push = ArTrackingState.TRACKING)
        assertEquals("ARCore's own field of view arrives instead", 2, fovs.size)
        assertEquals(52f, fovs.last().horizontalDegrees, 0f)
        assertEquals(52f, fovs.last().baseHorizontalDegrees, 0f)
        assertTrue(fovs.last().isDynamicStreamCalculated)

        step(zoom = 2f)
        rig.onCameraXFov(FovResult(41f, true))
        assertEquals(3, fovs.size)
        assertEquals(41f, fovs.last().horizontalDegrees, 0f)
    }

    @Test
    fun `ARCore frames reach the background, and the display geometry reaches ARCore`() {
        enterArCore()
        step(push = ArTrackingState.TRACKING)
        assertEquals(0, frames.displayRotation)
        assertEquals(1080, frames.displayWidth)
        assertEquals(2340, frames.displayHeight)
        // Latched while CameraX still had the camera (the availability step and the release step), and
        // not once ARCore owns it.
        assertEquals("CameraX is not latched while ARCore owns the camera", 2, picture.latches)
    }

    @Test
    fun `over an opaque sky ARCore is not polled and the live sensor pose draws`() {
        enterArCore()
        converge()
        val polls = frames.polls
        step(pictureEnabled = false)
        assertEquals(polls, frames.polls)
        assertFalse(out.useRigMatrix)
        assertEquals(0L, out.poseTimestampNs)
    }

    @Test
    fun `a pose that is not the phone's (a manual pan) is never ARCore's`() {
        enterArCore()
        converge()
        step(push = ArTrackingState.TRACKING, livePoseAllowed = false)
        assertFalse(out.useRigMatrix)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
    }

    @Test
    fun `an automatic alignment gets ARCore's own frame with the pose it was drawn with`() {
        enterArCore()
        converge()
        val grabber = rig.frameGrabber.value
        assertNotNull("ARCore is the live camera", grabber)
        frames.lumaCapture = ArLumaCapture(ByteArray(6), 3, 2, 1L, 1.75f)
        var got: LumaFrame? = null
        grabber!!.grabNext { got = it }
        assertNull(got)

        step(push = ArTrackingState.TRACKING)
        val luma = got
        assertNotNull(luma)
        assertEquals(3, luma!!.width)
        assertEquals(2, luma.height)
        assertEquals(3, luma.rowStride)
        val pose = luma.pose!!
        assertEquals(1.75f, pose.fillScale, 0f)
        assertEquals(52f, pose.fovHorizontalDegrees, 0f)
        for (k in 0 until 16) assertEquals("matrix $k", out.matrix[k], pose.rotationMatrix[k], 1e-6f)
    }

    @Test
    fun `an alignment frame is asked of CameraX when CameraX has the camera`() {
        val cameraXGrabber = com.dazix.caelis.ar.FrameGrabber { }
        rig.onCameraXGrabber(cameraXGrabber)
        assertEquals(cameraXGrabber, rig.frameGrabber.value)
        enterArCore()
        assertTrue(rig.frameGrabber.value !== cameraXGrabber)
        step(zoom = 2f)
        assertEquals(cameraXGrabber, rig.frameGrabber.value)
    }

    @Test
    fun `releasing the rig closes the session and forgets the pose`() {
        enterArCore()
        converge()
        rig.release()
        assertEquals(1, host.closes)
        assertEquals(PoseIndicator.SENSOR, rig.indicator.value)
    }

    @Test
    fun `the consent flow follows the flag the settings write`() {
        assertFalse(rig.consentedFlow.value)
        rig.consented = true
        assertTrue(rig.consentedFlow.value)
    }

    @Test
    fun `a rig built without ARCore never leaves CameraX`() {
        val plain = SkyPoseRig(CoroutineScope(Dispatchers.Unconfined), sensor, picture, hostFactory = null, onFov = {}, clockNs = { now })
        plain.consented = true
        plain.cameraPermission = true
        repeat(100) {
            now += frame
            plain.advance(sink, 1080f, 2340f, 0, 1f, true, true, out)
        }
        assertTrue(plain.cameraXEnabled.value)
        assertEquals(PoseIndicator.SENSOR, plain.indicator.value)
    }
}

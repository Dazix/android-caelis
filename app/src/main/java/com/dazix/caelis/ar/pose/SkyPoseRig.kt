package com.dazix.caelis.ar.pose

import android.os.SystemClock
import com.dazix.caelis.ar.CameraBackgroundSink
import com.dazix.caelis.ar.FovResult
import com.dazix.caelis.ar.FrameGrabber
import com.dazix.caelis.ar.LumaFrame
import com.dazix.caelis.ar.LumaFramePose
import com.dazix.caelis.ar.PictureFeed
import com.dazix.caelis.domain.arframe.CameraOwnerPolicy
import com.dazix.caelis.domain.arframe.PoseTimestampPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the pose indicator says. Never conveyed by colour alone. */
enum class PoseIndicator { SENSOR, ARCORE, SWITCHING }

/**
 * What a frame loop needs from [SkyPoseRig.advance]: which instant to ask the sensors for, or a
 * finished matrix. One instance per loop, reused: nothing is allocated per frame.
 */
class SkyPoseFrame {
    /** True when [matrix] holds the pose to draw (ARCore's); false means ask the sensors at [poseTimestampNs]. */
    var useRigMatrix = false

    /** The sensor instant of the picture on screen; 0 = the live pose. */
    var poseTimestampNs = 0L

    /** device -> ENU row-major 4x4, calibration applied; valid when [useRigMatrix]. */
    val matrix = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
}

/**
 * The camera, the picture and the pose of one AR screen, decided together once per vsync.
 *
 * Two independent questions, because they have different costs:
 *
 *  1. **Who owns the camera** ([CameraOwnerPolicy]): CameraX (default; zoom works) or ARCore. They
 *     cannot both have it, so switching is *release one, wait until it is really released, start the
 *     other*, in a fixed order and with every step cancellable. A pinch above 1x, a withdrawn
 *     consent, a failed or silent session each send the camera back to CameraX at once.
 *  2. **Whose pose draws the sky** ([PoseHandoff] / `PoseArbiter`): ARCore's only when it is tracking,
 *     its north has converged and the zoom is 1x; otherwise the sensors'. This one switches with no
 *     visible camera change at all, because while ARCore owns the camera its images are the picture
 *     whichever pose is used: the sensor pose is simply taken at the ARCore image's own timestamp.
 *
 * With ARCore consent off this class never creates the ARCore host, never calls it and never touches
 * the camera ownership: [hostFactory] is only invoked when the policy asks for ARCore, which needs
 * [consented].
 *
 * Camera hand-over, step by step (the order is the point):
 *  - Before anything moves, ARCore is *asked* whether it can run here ([ArCoreHost.availability])
 *    while CameraX still has the camera: a phone that cannot run it keeps its picture and is not
 *    asked again until consent is flipped.
 *  - CameraX -> ARCore: [cameraXEnabled] goes false (the preview composable unbinds and reports once
 *    the camera device is closed via [onCameraXReleased]), then the host starts on the main
 *    dispatcher; if the start fails, the policy blocks ARCore for a while and CameraX is re-enabled.
 *  - ARCore -> CameraX: the session is paused first (which releases the camera), then
 *    [cameraXEnabled] goes true. CameraX itself retries while the camera is still being let go.
 *  - Activity pause/resume: the session is paused with the activity and, on resume, started again
 *    straight away without binding CameraX in between ([onLifecyclePause], [onLifecycleResume]).
 *  - Opaque sky (Night Sky's planetarium): nothing consumes the camera, so after a short grace the
 *    session is paused and the camera is left unbound (PARKED); it starts again when the picture is
 *    wanted again, or goes to CameraX if the owner policy no longer wants ARCore.
 *
 * The picture follows the owner. A frame of CameraX is latched as before ([PictureFeed]); a frame of
 * ARCore brings its own HardwareBuffer, fitted to the screen by ARCore's own display geometry, and
 * the field of view comes from ARCore's projection and is published through [onFov] so that the GPU
 * camera, the CPU projector and the drag gain all use one number.
 *
 * Main thread only. Zero allocations per frame apart from what ARCore itself allocates per update.
 */
class SkyPoseRig(
    private val scope: CoroutineScope,
    private val sensorPose: SensorPose,
    private val cameraX: PictureFeed,
    private val hostFactory: (() -> ArCoreHost)?,
    private val onFov: (FovResult) -> Unit,
    private val clockNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val ownerPolicy: CameraOwnerPolicy = CameraOwnerPolicy(),
    private val handoff: PoseHandoff = PoseHandoff(sensorPose),
) {

    private enum class Phase { CAMERAX, PROBING, RELEASING_CAMERAX, STARTING_ARCORE, ARCORE, SUSPENDED, PARKED }

    /** Whether the CameraX use cases should be bound. The preview composable follows it. */
    private val cameraXEnabledState = MutableStateFlow(true)
    val cameraXEnabled: StateFlow<Boolean> = cameraXEnabledState.asStateFlow()

    private val indicatorState = MutableStateFlow(PoseIndicator.SENSOR)
    val indicator: StateFlow<PoseIndicator> = indicatorState.asStateFlow()

    private val grabberState = MutableStateFlow<FrameGrabber?>(null)

    /** The brightness-frame source for automatic alignment: whichever camera is live right now. */
    val frameGrabber: StateFlow<FrameGrabber?> = grabberState.asStateFlow()

    private val consentedState = MutableStateFlow(false)

    /** [consented] for the UI, which shows the pose indicator only when ARCore is allowed at all. */
    val consentedFlow: StateFlow<Boolean> = consentedState.asStateFlow()

    /** Whether the user agreed to ARCore. Written from the settings flow; read per frame. */
    @Volatile
    var consented = false
        set(value) {
            field = value
            consentedState.value = value
        }

    /** Whether the camera permission is granted. Without it neither camera can start. */
    @Volatile
    var cameraPermission = false

    private var phase = Phase.CAMERAX
    private var host: ArCoreHost? = null
    private var generation = 0
    private var cameraXReleased = false

    /** ARCore was asked and said it can run here; the camera may now change hands. */
    private var probedUsable = false

    /** ARCore said it cannot run here (not supported, not installed): not asked again until consent is flipped. */
    private var knownUnavailable = false
    private var phaseSinceNs = 0L
    private var lastFrameAtNs = 0L

    private var cameraXGrabber: FrameGrabber? = null
    private var lumaRequest: ((LumaFrame) -> Unit)? = null
    private val arGrabber = FrameGrabber { onFrame -> lumaRequest = onFrame }

    private val sample = ArFrameSample()
    private var imageInstantNs = 0L
    private var lastFovDegrees = 0f

    private var arImageTaken = false

    /**
     * Whether the image the background shows came from CameraX. Cleared the moment an ARCore image
     * takes its place or ARCore lets go of the camera, set again by the next CameraX image.
     */
    private var cameraXImageInStream = false

    /** Since when the sky has been opaque while ARCore ran, or [NOT_OPAQUE]. */
    private var opaqueSinceNs = NOT_OPAQUE

    /** Whether any camera image has reached the background yet, from either camera. */
    val hasPicture: Boolean get() = cameraX.hasImage || arImageTaken

    /** The grabber of the CameraX preview, when bound. */
    fun onCameraXGrabber(grabber: FrameGrabber?) {
        cameraXGrabber = grabber
        refreshGrabber()
    }

    /** The CameraX preview reports its camera device closed (or that none was bound). */
    fun onCameraXReleased() {
        cameraXReleased = true
    }

    /**
     * CameraX measured a field of view. Used only while CameraX is the camera: once ARCore owns it a
     * late layout callback of the unbound preview would overwrite ARCore's number with a stale one.
     */
    fun onCameraXFov(result: FovResult) {
        if (phase == Phase.ARCORE || phase == Phase.SUSPENDED || phase == Phase.PARKED) return
        onFov(result)
    }

    /** The activity is going to the background: ARCore must give the camera up with it. */
    fun onLifecyclePause() {
        if (phase == Phase.STARTING_ARCORE || phase == Phase.ARCORE) {
            generation++
            stopHost(starting = phase == Phase.STARTING_ARCORE)
            handoff.onSessionEnded(clockNs())
            // The camera stays unbound by CameraX: on resume the session starts again directly.
            phase = Phase.SUSPENDED
            lumaRequest = null
            refreshGrabber()
        }
    }

    fun onLifecycleResume() {
        if (phase != Phase.SUSPENDED) return
        // CameraX is still unbound, so there is nothing to wait for: the next frame decides between
        // starting ARCore at once and re-binding CameraX.
        phase = Phase.RELEASING_CAMERAX
        cameraXReleased = true
        phaseSinceNs = clockNs()
    }

    /**
     * The guard tick: confirms the user still agrees while a session is running, which nothing else
     * does (a running session never asks again). Withdrawn consent closes it and returns the camera.
     */
    suspend fun guardTick() {
        val h = host ?: return
        if (phase != Phase.ARCORE && phase != Phase.STARTING_ARCORE) return
        if (!h.revalidateConsent()) leaveArCore(clockNs(), failed = false)
    }

    /** The screen is going away: nothing of ARCore may outlive it. */
    fun release() {
        generation++
        host?.close()
        host = null
        handoff.onSessionEnded(clockNs())
        phase = Phase.CAMERAX
        lumaRequest = null
        indicatorState.value = PoseIndicator.SENSOR
    }

    /**
     * One vsync: decide the owner, take the newest picture from it, and say how to pose the sky.
     *
     * @param background where the picture goes; null while the GPU surface is not up yet.
     * @param pictureEnabled false over an opaque sky (Night Sky's planetarium), where no picture is
     *   drawn and nothing is polled: the live sensor pose is the responsive one there. If it stays
     *   false past a short grace the ARCore session is paused (see [park]) and resumed when it is true.
     * @param livePoseAllowed false while the pose is not the phone's (a manual pan): the sensors'
     *   own logic owns the matrix, and ARCore must not.
     * @param out filled with the result; reused by the loop.
     */
    fun advance(
        background: CameraBackgroundSink?,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int,
        zoomRatio: Float,
        pictureEnabled: Boolean,
        livePoseAllowed: Boolean,
        out: SkyPoseFrame,
    ) {
        out.useRigMatrix = false
        out.poseTimestampNs = 0L
        val nowNs = clockNs()

        stepOwner(nowNs, zoomRatio)

        if (phase == Phase.PARKED && pictureEnabled) {
            // The picture is wanted again: the camera is still unbound, so start straight away.
            phase = Phase.RELEASING_CAMERAX
            cameraXReleased = true
            phaseSinceNs = nowNs
        }

        if (phase == Phase.ARCORE) {
            advanceArCore(nowNs, background, viewportWidth, viewportHeight, displayRotation, zoomRatio, pictureEnabled, livePoseAllowed, out)
        } else {
            if (pictureEnabled && background != null) {
                if (cameraX.latch(background)) cameraXImageInStream = true
                // Only while the image in the stream is a CameraX one: after ARCore had the camera the
                // stream still holds ARCore's last image, whose size and aspect are not the ones
                // CameraX remembers, and fitting that with CameraX's numbers crops or stretches it
                // until the first new CameraX image arrives.
                if (cameraXImageInStream && cameraX.hasImage) {
                    background.setGeometry(cameraX.bufferWidth, cameraX.bufferHeight, cameraX.rotationDegrees, viewportWidth, viewportHeight)
                }
            }
            if (pictureEnabled) out.poseTimestampNs = cameraX.poseTimestampNs()
        }

        indicatorState.value = when {
            phase == Phase.RELEASING_CAMERAX || phase == Phase.STARTING_ARCORE -> PoseIndicator.SWITCHING
            phase == Phase.ARCORE && handoff.kind == PoseKind.ARCORE -> PoseIndicator.ARCORE
            else -> PoseIndicator.SENSOR
        }
    }

    private fun advanceArCore(
        nowNs: Long,
        background: CameraBackgroundSink?,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int,
        zoomRatio: Float,
        pictureEnabled: Boolean,
        livePoseAllowed: Boolean,
        out: SkyPoseFrame,
    ) {
        val frames = host?.frames ?: return
        val handsPose = consented && livePoseAllowed
        if (!pictureEnabled) {
            // Over an opaque sky nothing is polled and the live sensor pose draws.
            if (opaqueSinceNs == NOT_OPAQUE) opaqueSinceNs = nowNs
            if (nowNs - opaqueSinceNs > OPAQUE_PARK_NANOS) {
                // Nobody sees the camera's picture, so nobody should be paying for it: a resumed
                // session keeps the camera open and, with the user's consent, Google's localisation
                // running, with no sign on screen that either is happening.
                park(nowNs)
                return
            }
            handoff.tick(nowNs, handsPose, zoomRatio)
            lastFrameAtNs = nowNs
            return
        }
        opaqueSinceNs = NOT_OPAQUE
        frames.setDisplayGeometry(displayRotation, viewportWidth.toInt(), viewportHeight.toInt())
        if (frames.poll(sample)) {
            lastFrameAtNs = nowNs
            takePicture(background)
            if (sample.fovHorizontalDegrees > 0f && abs(sample.fovHorizontalDegrees - lastFovDegrees) > FOV_EPSILON_DEGREES) {
                lastFovDegrees = sample.fovHorizontalDegrees
                // A measured field of view: it already is what the screen shows, at 1x.
                onFov(FovResult(lastFovDegrees, isDynamicStreamCalculated = true, baseHorizontalDegrees = lastFovDegrees))
            }
            imageInstantNs = PoseTimestampPolicy.frameInstant(sample.timestampNs, 0L, 0L)
            handoff.onSample(sample, nowNs, handsPose, zoomRatio)
            serveLumaRequest()
        } else {
            handoff.tick(nowNs, handsPose, zoomRatio)
        }

        if (livePoseAllowed && handoff.renderMatrix(imageInstantNs, out.matrix)) {
            out.useRigMatrix = true
        } else {
            // The sensors at the instant of the ARCore image on screen: the same principle as the
            // CameraX path, with the image's own timestamp.
            out.poseTimestampNs = PoseTimestampPolicy.renderTimestamp(imageInstantNs, nowNs, 0L)
        }
    }

    /**
     * Pauses the session while the sky is opaque, and keeps the camera unbound: with no picture to
     * draw, CameraX has no use for it either. [advance] starts again when the picture comes back.
     */
    private fun park(nowNs: Long) {
        generation++
        host?.pause()
        handoff.onSessionEnded(nowNs)
        lumaRequest = null
        opaqueSinceNs = NOT_OPAQUE
        phase = Phase.PARKED
        refreshGrabber()
    }

    /** Hands a new frame's image to the background, or lets it go if there is nowhere to put it. */
    private fun takePicture(background: CameraBackgroundSink?) {
        val buffer = sample.buffer ?: return
        sample.buffer = null
        val taken = background != null && background.submitBuffer(buffer, sample.imageWidth, sample.imageHeight)
        // Every buffer that was not taken goes back to ARCore: its pool is as small as ours.
        if (taken) {
            arImageTaken = true
            cameraXImageInStream = false
        } else {
            closeQuietly(buffer)
        }
        if (sample.uvChanged && background != null) {
            val uv = sample.uv
            background.setUv(uv[0], uv[1], uv[2], uv[3], uv[4], uv[5])
        }
    }

    /**
     * An automatic alignment asked for a frame: copy the brightness plane of the one just processed
     * and attach the pose that was drawn for it, so the aligner has nothing to interpolate.
     */
    private fun serveLumaRequest() {
        val request = lumaRequest ?: return
        val frames = host?.frames ?: return
        val capture = frames.copyLuma() ?: return
        lumaRequest = null
        val matrix = FloatArray(16)
        if (!handoff.renderMatrix(imageInstantNs, matrix)) sensorPose.renderMatrix(imageInstantNs, matrix)
        request(
            LumaFrame(
                data = capture.data,
                width = capture.width,
                height = capture.height,
                rowStride = capture.width,
                timestampNs = capture.timestampNs,
                pose = LumaFramePose(matrix, lastFovDegrees, capture.fillScale),
            ),
        )
    }

    private fun stepOwner(nowNs: Long, zoomRatio: Float) {
        if (hostFactory == null) return
        // A user who flips consent off and on again is asking for a fresh look at ARCore.
        if (!consented) {
            probedUsable = false
            knownUnavailable = false
        }
        val desired = ownerPolicy.update(
            nowNs = nowNs,
            consented = consented,
            cameraPermission = cameraPermission,
            zoomRatio = zoomRatio,
            arCoreUsable = !knownUnavailable,
        )
        val wantsArCore = desired == CameraOwnerPolicy.Owner.ARCORE
        when (phase) {
            Phase.CAMERAX -> if (wantsArCore) {
                if (probedUsable) {
                    cameraXReleased = false
                    phase = Phase.RELEASING_CAMERAX
                    phaseSinceNs = nowNs
                    cameraXEnabledState.value = false
                } else {
                    probeArCore()
                }
            }
            Phase.PROBING -> if (!wantsArCore) {
                // The answer to a question nobody is waiting for any more is dropped.
                generation++
                phase = Phase.CAMERAX
            }
            Phase.RELEASING_CAMERAX -> when {
                !wantsArCore -> {
                    cameraXEnabledState.value = true
                    phase = Phase.CAMERAX
                }
                cameraXReleased || nowNs - phaseSinceNs > RELEASE_TIMEOUT_NANOS -> startArCore(nowNs)
            }
            Phase.STARTING_ARCORE -> if (!wantsArCore) {
                // Changed our mind while the session was still starting: its result will be discarded.
                leaveArCore(nowNs, failed = false, consentWithdrawn = !consented)
            }
            Phase.ARCORE -> when {
                !wantsArCore -> leaveArCore(nowNs, failed = false, consentWithdrawn = !consented)
                // A session that delivers nothing is worse than none: hand the camera back.
                nowNs - lastFrameAtNs > SILENCE_TIMEOUT_NANOS -> leaveArCore(nowNs, failed = true)
            }
            Phase.PARKED -> if (!wantsArCore) {
                // Zoomed or consent withdrawn while parked: the session is only paused, so a withdrawn
                // consent still has to close it here (guardTick looks at running sessions only).
                if (!consented) host?.close()
                phase = Phase.CAMERAX
                phaseSinceNs = nowNs
                cameraXEnabledState.value = true
                refreshGrabber()
            }
            Phase.SUSPENDED -> Unit
        }
    }

    /**
     * Asks whether ARCore can run here *before* touching the camera. CameraX -> ARCore costs the
     * picture a rebind either way; finding out that the phone cannot run ARCore by a failed start
     * would charge it for nothing, on every screen opening.
     */
    private fun probeArCore() {
        val factory = hostFactory ?: return
        val h = host ?: factory().also { host = it }
        phase = Phase.PROBING
        val asked = ++generation
        scope.launch {
            val answer = try {
                h.availability()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ArAvailability.TRANSIENT
            }
            if (asked != generation) return@launch
            phase = Phase.CAMERAX
            when (answer) {
                ArAvailability.USABLE -> probedUsable = true
                ArAvailability.UNAVAILABLE -> knownUnavailable = true
                // Still being worked out: try again after the same pause a failed start gets.
                ArAvailability.TRANSIENT -> ownerPolicy.noteStartFailed(clockNs())
            }
        }
    }

    private fun startArCore(nowNs: Long) {
        val factory = hostFactory ?: return
        val h = host ?: factory().also { host = it }
        phase = Phase.STARTING_ARCORE
        phaseSinceNs = nowNs
        val started = ++generation
        scope.launch {
            val ok = try {
                h.start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (started != generation) {
                // Abandoned while starting (zoomed, backgrounded, consent withdrawn): whatever got
                // as far as resuming must not keep the camera.
                // (A newer start for the same host is not ours to close.)
                if (ok && phase != Phase.STARTING_ARCORE && phase != Phase.ARCORE) h.close()
                return@launch
            }
            val now = clockNs()
            if (ok) {
                phase = Phase.ARCORE
                lastFrameAtNs = now
                lastFovDegrees = 0f
                imageInstantNs = 0L
                opaqueSinceNs = NOT_OPAQUE
                // ARCore's images are about to replace CameraX's in the stream, and from here on
                // CameraX's remembered geometry describes nothing on screen (see advance).
                cameraXImageInStream = false
                refreshGrabber()
            } else {
                leaveArCore(now, failed = true)
            }
        }
    }

    /** ARCore -> CameraX, in the safe order: the session first, then the camera is offered back. */
    private fun leaveArCore(nowNs: Long, failed: Boolean, consentWithdrawn: Boolean = false) {
        generation++
        stopHost(starting = phase == Phase.STARTING_ARCORE, close = consentWithdrawn)
        handoff.onSessionEnded(nowNs)
        lumaRequest = null
        if (failed) ownerPolicy.noteStartFailed(nowNs)
        phase = Phase.CAMERAX
        phaseSinceNs = nowNs
        cameraXEnabledState.value = true
        refreshGrabber()
    }

    /**
     * Gives the camera up. A session that is still *starting* is closed rather than paused: closing
     * is what makes the controller's suspended start give up before it opens the camera, whereas a
     * pause would be followed by that start resuming it. A running one is only paused, which keeps
     * the session so that coming back does not pay for a restart. Withdrawn consent is the exception:
     * the session is closed, because nothing else would ever close it once the phase has left ARCORE
     * ([guardTick] only looks at running sessions) and a paused session is still a live binding to
     * Google's component.
     */
    private fun stopHost(starting: Boolean, close: Boolean = false) {
        if (starting || close) host?.close() else host?.pause()
    }

    private fun refreshGrabber() {
        grabberState.value = if (phase == Phase.ARCORE) arGrabber else cameraXGrabber
    }

    private fun closeQuietly(buffer: android.hardware.HardwareBuffer) {
        try {
            buffer.close()
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        /** CameraX reports closing within a few hundred ms; past this the camera is assumed free. */
        const val RELEASE_TIMEOUT_NANOS = 2_000_000_000L

        /** A running session that has produced no frame for this long has failed. */
        const val SILENCE_TIMEOUT_NANOS = 4_000_000_000L

        const val FOV_EPSILON_DEGREES = 0.02f

        /** How long the sky may stay opaque before the session is paused; a toggled switch must not thrash the camera. */
        const val OPAQUE_PARK_NANOS = 2_000_000_000L

        const val NOT_OPAQUE = Long.MIN_VALUE
    }
}

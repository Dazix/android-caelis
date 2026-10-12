package com.dazix.caelis.ar.pose

import com.dazix.caelis.domain.arframe.ArCoreFov
import com.dazix.caelis.domain.arframe.ArWorldAlignment
import com.dazix.caelis.domain.arframe.CameraBackgroundUv
import com.dazix.caelis.domain.arframe.LumaRotation
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import kotlin.math.hypot

/**
 * The live ARCore session as an [ArFrameSource]: one `Session.update()` per call, reduced to an
 * [ArFrameSample].
 *
 * The session itself is never created here; it belongs to [ArCoreSessionController], which is the
 * only thing that may start it and which has already checked consent. This class only *reads* a
 * session that exists and reports nothing when there is none, so with consent off it has no way
 * to start anything even if it were called.
 *
 * Main thread only: `update`, `pause` and `resume` of one session must not overlap.
 *
 * Allocation: ARCore's Java API returns a new `Frame`, `Camera` and `Pose` per call and a new array
 * for the Geospatial quaternion; those are the platform's and cannot be pooled. Everything this class
 * adds is written into the caller's [ArFrameSample] and its own scratch arrays.
 */
class ArCoreFrameSource(private val controller: ArCoreSessionController) : ArFrameSource {

    override var sessionEpoch = 0
        private set

    private var lastFrame: Frame? = null
    private var lastFrameTimestamp = Long.MIN_VALUE

    private var displayRotation = 0
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDirty = false
    private var uvPending = false

    private val quaternion = FloatArray(4)
    private val projection = FloatArray(16)
    private val cornerSamples = FloatArray(6)
    private val textureSamples = FloatArray(6)
    private val edgeSamples = FloatArray(4)
    private val edgeView = FloatArray(4)

    /** A new session or a resume: the world is a new one and the display geometry has to be told again. */
    fun onSessionStarted() {
        sessionEpoch++
        lastFrame = null
        lastFrameTimestamp = Long.MIN_VALUE
        displayDirty = true
    }

    /** The session was paused or closed: nothing about the last frame is usable any more. */
    fun onSessionEnded() {
        lastFrame = null
        lastFrameTimestamp = Long.MIN_VALUE
    }

    override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) {
        if (displayRotation == this.displayRotation && width == displayWidth && height == displayHeight) return
        this.displayRotation = displayRotation
        displayWidth = width
        displayHeight = height
        displayDirty = true
    }

    override fun poll(out: ArFrameSample): Boolean {
        val session = controller.session ?: return false
        if (controller.status.value !is ArCoreSessionController.Status.Running) return false
        if (displayWidth <= 0 || displayHeight <= 0) return false

        val geometryForced = displayDirty
        if (displayDirty) {
            session.setDisplayGeometry(displayRotation, displayWidth, displayHeight)
            displayDirty = false
        }
        val frame = try {
            session.update()
        } catch (_: CameraNotAvailableException) {
            return false
        } catch (_: SessionPausedException) {
            return false
        } catch (_: RuntimeException) {
            // A session that fails to update (fatal error, resources exhausted) delivers nothing; the
            // rig notices the silence and hands the camera back to CameraX.
            return false
        }
        // LATEST_CAMERA_IMAGE returns the same frame until the camera produces another one.
        val timestamp = frame.timestamp
        // The newest wrapper replaces the old one even when nothing is new: a Frame is only good for
        // the update that returned it, and copyLuma reads from it.
        lastFrame = frame
        if (timestamp == lastFrameTimestamp && !geometryForced) return false
        lastFrameTimestamp = timestamp
        if (geometryForced || frame.hasDisplayGeometryChanged()) uvPending = true

        out.clear()
        out.sessionEpoch = sessionEpoch
        out.timestampNs = frame.androidCameraTimestamp

        val camera = frame.camera
        out.tracking = when (camera.trackingState) {
            TrackingState.TRACKING -> ArTrackingState.TRACKING
            TrackingState.PAUSED -> ArTrackingState.PAUSED
            else -> ArTrackingState.STOPPED
        }

        // The device's axes in ARCore's world (Android sensor axes, unaffected by display rotation —
        // the same axes SensorManager uses), and the camera's own for Geospatial's yaw.
        frame.androidSensorPose.getRotationQuaternion(quaternion, 0)
        ArWorldAlignment.quaternionToMatrix(quaternion[0], quaternion[1], quaternion[2], quaternion[3], out.devicePose)
        camera.pose.getRotationQuaternion(quaternion, 0)
        ArWorldAlignment.quaternionToMatrix(quaternion[0], quaternion[1], quaternion[2], quaternion[3], out.cameraPose)

        // The projection is built for the display geometry set above, so its first diagonal entry is
        // the viewport's horizontal field of view. Unknown (0) until ARCore has intrinsics.
        camera.getProjectionMatrix(projection, 0, PROJECTION_NEAR, PROJECTION_FAR)
        out.fovHorizontalDegrees = ArCoreFov.horizontalDegrees(projection[0])

        if (out.tracking == ArTrackingState.TRACKING && controller.status.value.let { it is ArCoreSessionController.Status.Running && it.geospatial }) {
            readGeospatial(session, out)
        }

        try {
            val buffer = frame.hardwareBuffer
            out.buffer = buffer
            out.imageWidth = buffer.width
            out.imageHeight = buffer.height
        } catch (_: NotYetAvailableException) {
            // The first frames can arrive before the first camera image does.
        } catch (_: IllegalStateException) {
            // Not configured to expose a hardware buffer; the rig then has no picture and says so.
        }

        // Pending rather than "changed this frame": the first frames after a geometry change can
        // arrive without a camera image, and the coordinates are only useful alongside one.
        if (uvPending && out.buffer != null) {
            uvPending = false
            // Where the screen's corners land in the camera texture, as ARCore fits it to the display.
            cornerSamples[0] = 0f; cornerSamples[1] = 0f
            cornerSamples[2] = 1f; cornerSamples[3] = 0f
            cornerSamples[4] = 0f; cornerSamples[5] = 1f
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, cornerSamples, Coordinates2d.TEXTURE_NORMALIZED, textureSamples)
            out.uvChanged = CameraBackgroundUv.fromCornerSamples(textureSamples, TEXTURE_V_IS_DOWN, out.uv)
        }
        return true
    }

    private fun readGeospatial(session: com.google.ar.core.Session, out: ArFrameSample) {
        try {
            val earth = session.earth ?: return
            if (earth.trackingState != TrackingState.TRACKING || earth.earthState != Earth.EarthState.ENABLED) return
            val pose = earth.cameraGeospatialPose
            val q = pose.eastUpSouthQuaternion
            ArWorldAlignment.quaternionToMatrix(q[0], q[1], q[2], q[3], out.geospatialEus)
            out.geospatialYawAccuracyDegrees = pose.orientationYawAccuracy.toFloat()
            out.geospatialValid = true
        } catch (_: RuntimeException) {
            // Geospatial is a bonus: any trouble with it leaves the compass as the north.
            out.geospatialValid = false
        }
    }

    override fun copyLuma(): ArLumaCapture? {
        val frame = lastFrame ?: return null
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val width = image.width
            val height = image.height
            val stride = plane.rowStride

            // ARCore's CPU image is in the sensor's orientation. Where its top edge runs on the screen
            // says how far to turn it, and how many screen pixels one image pixel covers.
            edgeSamples[0] = 0f; edgeSamples[1] = 0f
            edgeSamples[2] = 1f; edgeSamples[3] = 0f
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, edgeSamples, Coordinates2d.VIEW_NORMALIZED, edgeView)
            val rotation = CameraBackgroundUv.rotationFromTopEdge(edgeView[2] - edgeView[0], edgeView[3] - edgeView[1])

            edgeSamples[2] = width.toFloat(); edgeSamples[3] = 0f
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, edgeSamples, Coordinates2d.VIEW, edgeView)
            val fillScale = hypot(edgeView[2] - edgeView[0], edgeView[3] - edgeView[1]) / width

            val buffer = plane.buffer
            val source = ByteArray(buffer.remaining())
            buffer.get(source)
            val upright = ByteArray(width * height)
            if (!LumaRotation.rotate(source, width, height, stride, rotation, upright)) return null
            return ArLumaCapture(
                data = upright,
                width = LumaRotation.uprightWidth(width, height, rotation),
                height = LumaRotation.uprightHeight(width, height, rotation),
                timestampNs = frame.androidCameraTimestamp,
                fillScale = fillScale,
            )
        } finally {
            image.close()
        }
    }

    private companion object {
        const val PROJECTION_NEAR = 0.1f
        const val PROJECTION_FAR = 100f

        /**
         * Whether ARCore's TEXTURE_NORMALIZED has v running down the image as stored, like the
         * [CameraBackgroundUv] convention. Not documented by ARCore and checked by nothing but a
         * device: if the camera appears upside down in ARCore mode, this is the one value to flip.
         */
        const val TEXTURE_V_IS_DOWN = true
    }
}

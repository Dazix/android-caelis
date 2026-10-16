package com.dazix.caelis.ar

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.dazix.caelis.ar.scene.CameraBackground
import com.dazix.caelis.domain.arframe.PoseTimestampPolicy
import java.util.concurrent.Executor

/** Takes the camera image that is about to be drawn and owns it until it is done with it. */
interface CameraImageSink {
    /**
     * Called on the main thread with the newest camera image. Returns true when the sink took it
     * and will close it, false when it cannot take it now (the image stays with the caller, which
     * offers it again on the next frame unless a newer one has replaced it).
     */
    fun submit(image: Image): Boolean
}

/**
 * Where the CameraX `Preview` use case sends its frames when the app draws the camera picture itself
 * (see [com.dazix.caelis.ar.scene.CameraBackground]) instead of handing it to a `PreviewView`.
 *
 * Why not `PreviewView`: its picture reaches the screen by a route the app cannot see into, tens of
 * milliseconds after exposure and composited apart from the overlay, so the sun's path and the
 * terrain silhouette ran ahead of the photographed ridge on every turn. Here every image is held by
 * the app: the frame loop latches the newest one, asks for the pose at *that image's* instant and
 * draws image and overlay in one Filament frame, so the pose is always the one the picture was
 * taken with.
 *
 * Frames arrive through an `ImageReader` of `PRIVATE` images the GPU can sample, which are handed to
 * Filament as `HardwareBuffer`s without a copy. Everything here runs on the main thread: the reader's
 * listener, CameraX's callbacks and [latch] all use the main looper, so no state needs a lock, and
 * Filament, which has to be driven from the thread that owns its engine, never sees another thread.
 * The one unavoidable per-camera-frame allocation is the platform's own (`Image`, and the
 * `HardwareBuffer` wrapper the sink takes from it); nothing is allocated per render frame.
 *
 * @param frameClock supplies the exposure and rolling-shutter skew that turn an image's start-of-
 *   exposure timestamp into the middle of its light gathering. Null leaves the overlay on the live pose.
 */
class CameraFrameSource(private val frameClock: PreviewFrameClock?) : Preview.SurfaceProvider, PictureFeed {

    /** One `Preview` surface request and the reader that answers it. */
    private class Feed(val reader: ImageReader)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    private var current: Feed? = null
    private var pending: Image? = null
    private var pendingFeed: Feed? = null
    private var released = false

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        if (reader === current?.reader) acquireNewest(reader)
    }

    /**
     * Clockwise degrees that turn the camera buffer upright for the display, as CameraX reports them
     * (`TransformationInfo.rotationDegrees`; it follows the preview's target rotation too).
     */
    override var rotationDegrees = 0
        private set

    /** Size in pixels of the image most recently handed to a sink, as the camera wrote it. */
    override var bufferWidth = 0
        private set
    override var bufferHeight = 0
        private set

    /** Whether at least one image has been handed to a sink; until then there is no picture to match. */
    override var hasImage = false
        private set

    /**
     * The sensor instant of the image on screen ([PoseTimestampPolicy.frameInstant]), 0 when the
     * camera's clock is not the sensors' and the overlay has to follow the phone live.
     */
    var displayedInstantNs = 0L
        private set

    override fun onSurfaceRequested(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        val reader = try {
            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.PRIVATE,
                MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
        } catch (_: RuntimeException) {
            // A device that cannot make a GPU-sampled PRIVATE reader of this size has no use for us.
            request.willNotProvideSurface()
            return
        }
        val feed = Feed(reader)
        // An earlier feed is not closed here: the camera may still be writing to it until CameraX
        // reports its surface released, and that report (below) is what closes it.
        current = feed
        reader.setOnImageAvailableListener(onImageAvailable, mainHandler)
        request.setTransformationInfoListener(mainExecutor) { info -> rotationDegrees = info.rotationDegrees }
        request.provideSurface(reader.surface, mainExecutor) { retire(feed) }
    }

    /**
     * Hands the newest image that has not been drawn yet to [sink]. Call once per frame, on the main
     * thread, before asking for the pose. Returns whether a new image went out; when it did not, the
     * previous one is still on screen and [poseTimestampNs] still names its instant.
     */
    override fun latch(sink: CameraImageSink): Boolean {
        val feed = current ?: return false
        if (!released) acquireNewest(feed.reader)
        val image = pending ?: return false
        val timestamp = image.timestamp
        val width = image.width
        val height = image.height
        if (!sink.submit(image)) return false
        pending = null
        pendingFeed = null
        bufferWidth = width
        bufferHeight = height
        displayedInstantNs = frameClock?.frameInstantFor(timestamp) ?: 0L
        hasImage = true
        return true
    }

    /**
     * The sensor instant to take the pose at for the picture on screen, or 0 for the live pose.
     * Until the first image has been drawn it is what the `PreviewView` path used. Allocation-free.
     */
    override fun poseTimestampNs(): Long {
        if (!hasImage) return frameClock?.renderTimestampNs() ?: 0L
        return PoseTimestampPolicy.renderTimestamp(displayedInstantNs, SystemClock.elapsedRealtimeNanos(), 0L)
    }

    /**
     * Stops using frames: the screen is going away. The reader itself is closed when CameraX reports
     * its surface released, because the camera may still be writing to it until then.
     */
    fun release() {
        released = true
        dropPending()
    }

    private fun acquireNewest(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            // Not reached with [MAX_IMAGES] as sized; kept as a safety net for a reader that hands
            // out fewer than it was asked for. Every image the reader allows is out. The unlatched one we hold is the kind of frame
            // that is about to be superseded anyway, so give it back and try once more.
            dropPending()
            try {
                reader.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            }
        } ?: return
        dropPending()
        pending = image
        pendingFeed = current
    }

    private fun dropPending() {
        val image = pending ?: return
        pending = null
        pendingFeed = null
        try {
            image.close()
        } catch (_: RuntimeException) {
            // Its reader was closed first, which already released the image.
        }
    }

    private fun retire(feed: Feed) {
        if (pendingFeed === feed) dropPending()
        if (current === feed) current = null
        feed.reader.close()
    }

    companion object {
        /**
         * Every image the app can hold at once, plus one for the camera to write into. The app holds
         * [CameraBackground.POOL_SIZE] inside Filament, the unlatched one in `pending`, and, for the
         * instant [acquireNewest] has taken a newer image and not yet dropped the older, one more.
         * Sized to the pool's own sum (1 pending + the pool = 4) the camera had no buffer left as
         * soon as the GPU was a couple of frames behind, and `acquireLatestImage` then threw on
         * every vsync, allocating a stack trace on the render path on exactly the frames that were
         * already late.
         */
        internal const val MAX_IMAGES = CameraBackground.POOL_SIZE + 3
    }
}

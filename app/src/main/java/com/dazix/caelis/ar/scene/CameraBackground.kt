package com.dazix.caelis.ar.scene

import android.hardware.HardwareBuffer
import android.media.Image
import android.os.Handler
import android.os.Looper
import com.dazix.caelis.ar.CameraBackgroundSink
import com.dazix.caelis.domain.arframe.CameraBackgroundUv
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler

/**
 * The camera picture, drawn by Filament underneath the sky in the same frame as everything else.
 *
 * Images come from [com.dazix.caelis.ar.CameraFrameSource] (CameraX) or from an ARCore frame
 * ([com.dazix.caelis.ar.pose.SkyPoseRig]) as `HardwareBuffer`s and go into an ACQUIRED Filament
 * stream behind an external texture, so the GPU samples the camera's own buffer with no copy. A
 * full-screen triangle (`camera_background.mat`) shows it, drawn before every other layer
 * ([SkyPriority.CAMERA]). The UVs reproduce `PreviewView` FILL_CENTER exactly for a CameraX image
 * ([setGeometry], [CameraBackgroundUv]) and are ARCore's own display-geometry mapping for an ARCore
 * one ([setUv]); only one camera feeds it at a time, so the two paths share the stream and the
 * release pool.
 *
 * Night vision does not touch the picture. The Canvas renderer only ever tinted the sky layers, and
 * the camera showed through unchanged under any theme, so this material reads no night-vision
 * global; the planetarium's opaque sky hides the picture with [enabled] instead.
 *
 * Ownership of images: [submit] takes an image and its `HardwareBuffer`, and Filament says when it
 * has finished with them by running the callback it was given. Those callbacks are a small fixed
 * pool of preallocated [Runnable]s — one per image that can be in flight — so no lambda is created
 * per camera frame. Each closes the buffer wrapper and then the image, which is what returns the
 * buffer to the camera. Main thread only, like the engine.
 */
class CameraBackground(surface: SkySurface) : CameraBackgroundSink {

    private val engine = surface.engine
    private val handler = Handler(Looper.getMainLooper())

    private val material = surface.loadMaterial("camera_background")
    private val layer = FullScreenLayer(surface, material, SkyPriority.CAMERA, withUv = true)

    // No .stream(...) on the builder is what makes it an ACQUIRED stream, fed with setAcquiredImage.
    private val stream: Stream = Stream.Builder().build(engine)
    private val texture: Texture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)

    private val slots = Array(POOL_SIZE) { ReleaseSlot() }
    private val uv = FloatArray(CameraBackgroundUv.UV_COUNT)

    private var hasImage = false
    private var uvValid = false
    private var streamWidth = 0
    private var streamHeight = 0
    private var geometryBufferWidth = 0
    private var geometryBufferHeight = 0
    private var geometryRotation = Int.MIN_VALUE
    private var geometryViewportWidth = 0f
    private var geometryViewportHeight = 0f
    private var destroyed = false

    /**
     * Whether the picture is drawn at all. Off for the planetarium's opaque sky; on otherwise. The
     * layer also stays hidden until the first image has been submitted, because an external texture
     * with nothing acquired samples whatever the driver leaves there.
     */
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyVisibility()
        }

    init {
        texture.setExternalStream(engine, stream)
        // External textures are never mip-mapped and take no wrap other than clamp.
        val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
        layer.materialInstance.setParameter("cameraFeed", texture, sampler)
        // The material's default instance too, though nothing draws with it. The Java Material creates
        // it on load, Filament commits every instance in the engine each frame, and a sampler left
        // unset is patched with a dummy texture only for 2D, array and cubemap types. An external one
        // stays unset and logged "Sampler descriptor 1 of MaterialInstance is not set. Please report
        // this issue." once per screen.
        material.defaultInstance.setParameter("cameraFeed", texture, sampler)
        layer.setVisible(false)
    }

    override fun submit(image: Image): Boolean {
        if (destroyed) return false
        // Every buffer is still inside Filament: wait for a frame to give one back.
        val free = freeSlot() ?: return false
        val buffer = image.hardwareBuffer ?: return false
        return accept(free, buffer, image, image.width, image.height)
    }

    /**
     * An ARCore frame's image: the same stream, the same pool, the same release rule. There is no
     * `Image` to close behind it, only the buffer ARCore handed over.
     */
    override fun submitBuffer(buffer: HardwareBuffer, width: Int, height: Int): Boolean {
        if (destroyed) return false
        val free = freeSlot() ?: return false
        return accept(free, buffer, null, width, height)
    }

    private fun freeSlot(): ReleaseSlot? {
        for (candidate in slots) if (!candidate.busy) return candidate
        return null
    }

    private fun accept(free: ReleaseSlot, buffer: HardwareBuffer, image: Image?, width: Int, height: Int): Boolean {
        free.image = image
        free.buffer = buffer
        free.busy = true
        if (width != streamWidth || height != streamHeight) {
            streamWidth = width
            streamHeight = height
            stream.setDimensions(streamWidth, streamHeight)
        }
        stream.setAcquiredImage(buffer, handler, free)
        hasImage = true
        applyVisibility()
        return true
    }

    /**
     * Fits the picture to the screen. Call every frame with the latest values; it only touches the
     * GPU when one of them changed. [rotationDegrees] is CameraX's clockwise turn that makes the
     * buffer upright, and the viewport is the size of the surface this layer covers.
     */
    override fun setGeometry(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int, viewportWidth: Float, viewportHeight: Float) {
        if (bufferWidth == geometryBufferWidth && bufferHeight == geometryBufferHeight &&
            rotationDegrees == geometryRotation &&
            viewportWidth == geometryViewportWidth && viewportHeight == geometryViewportHeight
        ) {
            return
        }
        geometryBufferWidth = bufferWidth
        geometryBufferHeight = bufferHeight
        geometryRotation = rotationDegrees
        geometryViewportWidth = viewportWidth
        geometryViewportHeight = viewportHeight
        uvValid = CameraBackgroundUv.compute(bufferWidth, bufferHeight, rotationDegrees, viewportWidth, viewportHeight, uv)
        if (uvValid) layer.setUv(uv[0], uv[1], uv[2], uv[3], uv[4], uv[5])
        applyVisibility()
    }

    /**
     * Fits an ARCore image: the buffer coordinates of the triangle's corners as ARCore mapped them
     * for its display geometry. Forgets the CameraX geometry cached by [setGeometry], so that when
     * the camera goes back to CameraX the next [setGeometry] recomputes rather than finding "nothing
     * changed" and keeping ARCore's coordinates for a CameraX image.
     */
    override fun setUv(u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float) {
        geometryRotation = Int.MIN_VALUE
        uvValid = true
        layer.setUv(u0, v0, u1, v1, u2, v2)
        applyVisibility()
    }

    /** Whether the frame rendered next shows a camera picture (on, an image submitted and fitted). */
    val isPictureShown: Boolean
        get() = enabled && hasImage && uvValid

    private fun applyVisibility() {
        layer.setVisible(isPictureShown)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        layer.destroy()
        engine.destroyTexture(texture)
        engine.destroyStream(stream)
        engine.destroyMaterial(material)
        // Filament runs each image's callback when it lets go of it, which for the images still
        // in flight is only after the GPU is done; wait for that, then close whatever it did not
        // report so no buffer stays with a camera that is no longer ours.
        engine.flushAndWait()
        for (slot in slots) slot.release()
    }

    /** The callback Filament runs when it no longer needs one submitted image. */
    private class ReleaseSlot : Runnable {
        var image: Image? = null
        var buffer: HardwareBuffer? = null
        var busy = false

        override fun run() = release()

        fun release() {
            val b = buffer
            val i = image
            buffer = null
            image = null
            busy = false
            // The buffer wrapper goes first: it must not outlive the image it came from. Either
            // can already be invalid when the camera's reader was closed before Filament was done
            // (the camera was unbound), which is a release that already happened.
            try {
                b?.close()
            } catch (_: RuntimeException) {
            }
            try {
                i?.close()
            } catch (_: RuntimeException) {
            }
        }
    }

    companion object {
        /** Images that can be inside Filament at once: the one sampled, the one queued, one spare. */
        const val POOL_SIZE = 3
    }
}

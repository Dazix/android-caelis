package com.dazix.caelis.ar

import android.hardware.HardwareBuffer

/**
 * Where a camera picture goes to be drawn: the Filament background ([com.dazix.caelis.ar.scene.CameraBackground]).
 *
 * An interface so that the code deciding *what* to draw (the pose rig) can be driven by a test
 * without a GPU. Main thread only, like the engine behind it.
 */
interface CameraBackgroundSink : CameraImageSink {

    /**
     * Takes an ARCore frame's camera image. Returns true when it took ownership: [buffer] is then
     * closed by the sink when the GPU is done with it. False leaves it with the caller, who must close it.
     */
    fun submitBuffer(buffer: HardwareBuffer, width: Int, height: Int): Boolean

    /**
     * Fits a CameraX image to the screen from its size and rotation, with `PreviewView` FILL_CENTER
     * semantics. Cheap to call every frame: it only touches the GPU when a value changed.
     */
    fun setGeometry(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int, viewportWidth: Float, viewportHeight: Float)

    /**
     * Fits an ARCore image to the screen from the buffer coordinates ARCore reported for the
     * full-screen triangle's corners (`CameraBackgroundUv.fromCornerSamples`).
     */
    fun setUv(u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float)
}

/**
 * The CameraX preview's frames as the pose rig sees them: [CameraFrameSource], or a script in a test.
 * Everything runs on the main thread.
 */
interface PictureFeed {
    /** Hands the newest image not yet drawn to [sink]; true when one went out. */
    fun latch(sink: CameraImageSink): Boolean

    /** Whether at least one image has been handed over; until then there is no picture to match. */
    val hasImage: Boolean
    val bufferWidth: Int
    val bufferHeight: Int

    /** Clockwise degrees that turn the buffer upright. */
    val rotationDegrees: Int

    /** The sensor instant to take the pose at for the picture on screen; 0 = the live pose. */
    fun poseTimestampNs(): Long
}

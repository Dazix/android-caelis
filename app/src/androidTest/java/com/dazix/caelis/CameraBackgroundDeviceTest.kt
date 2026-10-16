package com.dazix.caelis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.CameraBackground
import com.dazix.caelis.ar.scene.SkySurface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * What the camera background really puts on the screen: a known picture goes through the real
 * `CameraBackground` (ACQUIRED stream, external texture, `camera_background.mat`) on the GPU,
 * offscreen, and the colours at known pixels are read back.
 *
 * This is what settles the texture-origin convention. `CameraBackgroundUv` assumes that texture
 * coordinate (0, 0) is the first row of the buffer in memory, top-left of the picture as the camera
 * wrote it, with v growing downwards; a GPU that sampled the other way up would show every camera
 * picture mirrored top to bottom and nothing in the JVM tests could notice.
 *
 * The expectations are worked out from the picture, not from the code. The buffer is drawn with four
 * quadrants — top-left red, top-right green, bottom-left blue, bottom-right yellow — all pure
 * channel values, so no colour-space conversion can change them. When a buffer is turned 90 degrees
 * clockwise its top row becomes the right-hand column, so its red and green corners end up top-right
 * and bottom-right and its blue and yellow corners top-left and bottom-left. A narrower or wider
 * viewport than the picture is filled by the longer side and loses the overflow evenly from both
 * ends (FILL_CENTER); a white strip around the buffer's edge makes that loss visible.
 *
 * Pixels come back top row first (see `SkySurface.renderAndRead`); [pixelAt] takes the row counted
 * from the top.
 */
@RunWith(AndroidJUnit4::class)
class CameraBackgroundDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val red = Color.rgb(255, 0, 0)
    private val green = Color.rgb(0, 255, 0)
    private val blue = Color.rgb(0, 0, 255)
    private val yellow = Color.rgb(255, 255, 0)
    private val white = Color.rgb(255, 255, 255)

    private val bufferWidth = 64
    private val bufferHeight = 48
    private val borderPx = 4

    /** A buffer, the offscreen sky it is drawn on, and the picture on the buffer. */
    private inner class Rig(viewportWidth: Int, viewportHeight: Int, maxImages: Int = 3) {
        val surface: SkySurface = SkySurface.offscreen(context, viewportWidth, viewportHeight)
        val background = CameraBackground(surface)
        val reader: ImageReader = ImageReader.newInstance(
            bufferWidth,
            bufferHeight,
            PixelFormat.RGBA_8888,
            maxImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )

        init {
            surface.setClearColor(0f, 0f, 0f, 0f)
        }

        fun paint(draw: (Canvas) -> Unit) {
            val canvas = reader.surface.lockHardwareCanvas()
            try {
                draw(canvas)
            } finally {
                reader.surface.unlockCanvasAndPost(canvas)
            }
        }

        /** The newest image the reader has, waiting for the producer to deliver it. */
        fun acquire(): Image {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                // The reader refuses a new image while every one it allows is still out; Filament
                // gives them back from the main thread, so wait for that instead of failing.
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: IllegalStateException) {
                    null
                }
                if (image != null) return image
                Thread.sleep(10)
            }
            throw AssertionError("the image reader delivered no image within $TIMEOUT_MS ms")
        }

        /** Hands [image] to the background, waiting for a release slot like the frame loop would. */
        fun submit(image: Image) {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (!background.submit(image)) {
                if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("the background never took the image")
                Thread.sleep(10)
            }
        }

        fun show(rotationDegrees: Int, viewportWidth: Int, viewportHeight: Int): ByteArray {
            background.setGeometry(bufferWidth, bufferHeight, rotationDegrees, viewportWidth.toFloat(), viewportHeight.toFloat())
            return surface.renderAndRead()
        }

        fun close() {
            background.destroy()
            surface.destroy()
            reader.close()
        }
    }

    private fun withRig(viewportWidth: Int, viewportHeight: Int, maxImages: Int = 3, body: (Rig) -> Unit) {
        val rig = Rig(viewportWidth, viewportHeight, maxImages)
        try {
            body(rig)
        } finally {
            rig.close()
        }
    }

    private fun drawQuadrants(canvas: Canvas, border: Boolean) {
        val paint = Paint()
        val halfW = bufferWidth / 2f
        val halfH = bufferHeight / 2f
        paint.color = red
        canvas.drawRect(0f, 0f, halfW, halfH, paint)
        paint.color = green
        canvas.drawRect(halfW, 0f, bufferWidth.toFloat(), halfH, paint)
        paint.color = blue
        canvas.drawRect(0f, halfH, halfW, bufferHeight.toFloat(), paint)
        paint.color = yellow
        canvas.drawRect(halfW, halfH, bufferWidth.toFloat(), bufferHeight.toFloat(), paint)
        if (border) {
            paint.color = white
            val b = borderPx.toFloat()
            canvas.drawRect(0f, 0f, bufferWidth.toFloat(), b, paint)
            canvas.drawRect(0f, bufferHeight - b, bufferWidth.toFloat(), bufferHeight.toFloat(), paint)
            canvas.drawRect(0f, 0f, b, bufferHeight.toFloat(), paint)
            canvas.drawRect(bufferWidth - b, 0f, bufferWidth.toFloat(), bufferHeight.toFloat(), paint)
        }
    }

    private fun showQuadrants(rig: Rig, border: Boolean, rotation: Int, viewportWidth: Int, viewportHeight: Int): ByteArray {
        rig.paint { drawQuadrants(it, border) }
        rig.submit(rig.acquire())
        return rig.show(rotation, viewportWidth, viewportHeight)
    }

    private fun pixelAt(pixels: ByteArray, viewportWidth: Int, viewportHeight: Int, x: Int, yFromTop: Int): Int {
        require(yFromTop in 0 until viewportHeight)
        val i = (yFromTop * viewportWidth + x) * 4
        return Color.argb(pixels[i + 3].toInt() and 0xFF, pixels[i].toInt() and 0xFF, pixels[i + 1].toInt() and 0xFF, pixels[i + 2].toInt() and 0xFF)
    }

    private fun assertColour(expected: Int, actual: Int, what: String) {
        val close = abs(Color.red(expected) - Color.red(actual)) <= TOLERANCE &&
            abs(Color.green(expected) - Color.green(actual)) <= TOLERANCE &&
            abs(Color.blue(expected) - Color.blue(actual)) <= TOLERANCE
        assertTrue(
            "$what: expected rgb(${Color.red(expected)}, ${Color.green(expected)}, ${Color.blue(expected)}) " +
                "but the screen shows rgb(${Color.red(actual)}, ${Color.green(actual)}, ${Color.blue(actual)})",
            close,
        )
        assertTrue("$what: the camera picture must be opaque, alpha was ${Color.alpha(actual)}", Color.alpha(actual) >= 250)
    }

    @Test
    fun anUprightBufferKeepsItsQuadrantsWhereTheCameraWroteThem() {
        withRig(64, 48) { rig ->
            val px = showQuadrants(rig, border = false, rotation = 0, viewportWidth = 64, viewportHeight = 48)
            assertColour(red, pixelAt(px, 64, 48, 16, 12), "top-left")
            assertColour(green, pixelAt(px, 64, 48, 48, 12), "top-right")
            assertColour(blue, pixelAt(px, 64, 48, 16, 36), "bottom-left")
            assertColour(yellow, pixelAt(px, 64, 48, 48, 36), "bottom-right")
        }
    }

    @Test
    fun aBufferTurnedNinetyDegreesClockwiseHasItsTopRowOnTheRight() {
        // The portrait case of a phone camera: the buffer is landscape, the screen is portrait.
        withRig(48, 64) { rig ->
            val px = showQuadrants(rig, border = false, rotation = 90, viewportWidth = 48, viewportHeight = 64)
            assertColour(blue, pixelAt(px, 48, 64, 12, 16), "top-left shows the buffer's bottom-left")
            assertColour(red, pixelAt(px, 48, 64, 36, 16), "top-right shows the buffer's top-left")
            assertColour(yellow, pixelAt(px, 48, 64, 12, 48), "bottom-left shows the buffer's bottom-right")
            assertColour(green, pixelAt(px, 48, 64, 36, 48), "bottom-right shows the buffer's top-right")
        }
    }

    @Test
    fun aViewportNarrowerThanThePictureLosesTheSidesAndKeepsTopAndBottom() {
        // 32 x 48 against a 64 x 48 picture: the height fits, the width keeps half of the picture,
        // columns 16..47 of the buffer, so viewport column x shows buffer column x + 16.
        withRig(32, 48) { rig ->
            val px = showQuadrants(rig, border = true, rotation = 0, viewportWidth = 32, viewportHeight = 48)
            assertColour(red, pixelAt(px, 32, 48, 8, 12), "top-left quadrant")
            assertColour(green, pixelAt(px, 32, 48, 24, 12), "top-right quadrant")
            assertColour(blue, pixelAt(px, 32, 48, 8, 36), "bottom-left quadrant")
            assertColour(yellow, pixelAt(px, 32, 48, 24, 36), "bottom-right quadrant")
            assertColour(white, pixelAt(px, 32, 48, 16, 1), "the top border is inside the visible part")
            assertColour(white, pixelAt(px, 32, 48, 16, 46), "the bottom border is inside the visible part")
            assertColour(red, pixelAt(px, 32, 48, 0, 12), "the left border is cropped away (buffer column 16)")
            assertColour(green, pixelAt(px, 32, 48, 31, 12), "the right border is cropped away (buffer column 47)")
        }
    }

    @Test
    fun aRotatedBufferInAWideViewportLosesTheTopAndBottomOfTheUprightPicture() {
        // Turned 90 degrees the picture is 48 x 64 upright; a 48 x 32 viewport keeps the middle half
        // of its height, which is the middle half of the buffer's columns (16..47). The buffer's top
        // and bottom rows are the upright picture's right and left columns, so those stay visible;
        // its left and right columns are the upright top and bottom, which are cropped away.
        withRig(48, 32) { rig ->
            val px = showQuadrants(rig, border = true, rotation = 90, viewportWidth = 48, viewportHeight = 32)
            assertColour(blue, pixelAt(px, 48, 32, 12, 8), "top-left shows the buffer's bottom-left")
            assertColour(red, pixelAt(px, 48, 32, 36, 8), "top-right shows the buffer's top-left")
            assertColour(yellow, pixelAt(px, 48, 32, 12, 24), "bottom-left shows the buffer's bottom-right")
            assertColour(green, pixelAt(px, 48, 32, 36, 24), "bottom-right shows the buffer's top-right")
            assertColour(white, pixelAt(px, 48, 32, 1, 16), "the buffer's bottom border is the left edge")
            assertColour(white, pixelAt(px, 48, 32, 46, 16), "the buffer's top border is the right edge")
            assertColour(blue, pixelAt(px, 48, 32, 20, 1), "the buffer's left border is cropped away from the top edge")
        }
    }

    @Test
    fun everyImageIsGivenBackSoTheCameraNeverRunsDry() {
        // The reader allows three images. Twelve frames only get through if each one is closed once
        // Filament has finished with it, and each must show the image just submitted, not an old one.
        val colours = intArrayOf(red, green, blue, yellow)
        withRig(64, 48, maxImages = 3) { rig ->
            for (frame in 0 until 12) {
                val colour = colours[frame % colours.size]
                rig.paint { canvas ->
                    val paint = Paint().apply { color = colour }
                    canvas.drawRect(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat(), paint)
                }
                rig.submit(rig.acquire())
                val px = rig.show(0, 64, 48)
                assertColour(colour, pixelAt(px, 64, 48, 32, 24), "frame $frame")
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val TOLERANCE = 8
    }
}

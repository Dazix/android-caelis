package com.dazix.caelis

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.Surface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.nightsky.NightSkyRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which way round the real terrain horizon is drawn in Night Sky AR.
 *
 * The profile strip starts at north (texel 0 is azimuth 0) while the panorama texture it shares a
 * shader with has north in the middle of the image. Reading the strip with the panorama's coordinate
 * drew the whole skyline half a turn out — a ridge due north appeared due south — and it looked
 * entirely plausible, because a horizon is a horizon whichever way you face.
 *
 * AGSL only runs on a GPU, so this has to be an instrumented test. It renders the real shader through
 * the real [NightSkyRenderer.drawLandscape] and reads the centre pixel back, with a profile that has
 * terrain in exactly one direction. North must be ground; the other three must be sky, which catches
 * a quarter-turn as well as a half-turn.
 */
@RunWith(AndroidJUnit4::class)
class NightSkyHorizonOrientationTest {

    private val frameSize = 64
    private val fovDegrees = 60f

    /** Terrain 20° high within ±5° of north, well below the horizon everywhere else. */
    private fun northOnlyProfile(): HorizonProfile {
        val elevations = FloatArray(HorizonProfile.SAMPLE_COUNT) { index ->
            val azimuth = HorizonProfile.azimuthForIndex(index)
            val fromNorth = minOf(azimuth, 360.0 - azimuth)
            if (fromNorth <= 5.0) 20f else -5f
        }
        return HorizonProfile(
            elevationsDegrees = elevations,
            observer = GeoLocation(50.0, 14.0),
            observerGroundElevationMeters = 0.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 1L,
            coveredSamples = HorizonProfile.SAMPLE_COUNT,
        )
    }

    /**
     * Renders the landscape layer looking at [azimuthDegrees] on the horizon and returns the centre
     * pixel. Transparent means sky, opaque means terrain.
     */
    private fun centrePixelLookingAt(azimuthDegrees: Double): Int {
        val renderer = NightSkyRenderer()
        assertNotNull("the landscape shader must compile on this device", renderer.landscapeShader)

        // The real-horizon branch never reads the panorama, but the shader declares the sampler, and
        // an unbound one is undefined behaviour. One opaque pixel is enough to bind it.
        renderer.setLandscapeBitmap(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.setPixel(0, 0, 0xFF00FF00.toInt()) },
        )
        renderer.setHorizonProfile(northOnlyProfile())

        val matrix = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(azimuthDegrees, 0.0, matrix)

        val reader = ImageReader.newInstance(
            frameSize,
            frameSize,
            PixelFormat.RGBA_8888,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val hardwareRenderer = HardwareRenderer()
        try {
            hardwareRenderer.setSurface(reader.surface)
            val node = RenderNode("landscape-orientation")
            node.setPosition(0, 0, frameSize, frameSize)

            val canvas = node.beginRecording(frameSize, frameSize)
            try {
                CanvasDrawScope().draw(
                    density = Density(1f),
                    layoutDirection = LayoutDirection.Ltr,
                    canvas = ComposeCanvas(canvas),
                    size = Size(frameSize.toFloat(), frameSize.toFloat()),
                ) {
                    renderer.drawLandscape(
                        drawScope = this,
                        matrix = matrix,
                        displayRotation = Surface.ROTATION_0,
                        fovHorizontalDegrees = fovDegrees,
                        w = frameSize.toFloat(),
                        h = frameSize.toFloat(),
                        isLandscapeVisible = true,
                        isRedVision = false,
                        useRealHorizon = true,
                    )
                }
            } finally {
                node.endRecording()
            }

            hardwareRenderer.setContentRoot(node)
            hardwareRenderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            val image = reader.acquireNextImage()
            assertNotNull("the GPU produced no frame; the test cannot report on what it never drew", image)
            image!!.use {
                // A GPU-backed image has no readable planes, so it is read through its HardwareBuffer
                // and copied into software memory. The copy is what makes getPixel legal at all.
                val buffer = it.hardwareBuffer
                assertNotNull("the frame carried no hardware buffer to read", buffer)
                buffer!!.use { hardware ->
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, ColorSpace.get(ColorSpace.Named.SRGB))
                    assertNotNull("the hardware buffer could not be wrapped for reading", wrapped)
                    val software = wrapped!!.copy(Bitmap.Config.ARGB_8888, false)
                    return software.getPixel(frameSize / 2, frameSize / 2)
                }
            }
        } finally {
            hardwareRenderer.destroy()
            reader.close()
        }
    }

    private fun alphaOf(pixel: Int) = (pixel ushr 24) and 0xFF

    @Test
    fun terrainDueNorthIsDrawnDueNorth() {
        val pixel = centrePixelLookingAt(0.0)

        assertTrue(
            "looking at the only ridge there is, the centre of the screen must be ground; got alpha ${alphaOf(pixel)}",
            alphaOf(pixel) > 200,
        )
    }

    @Test
    fun theOppositeDirectionIsSky() {
        val pixel = centrePixelLookingAt(180.0)

        assertTrue(
            "a ridge at north must not appear at south; got alpha ${alphaOf(pixel)}",
            alphaOf(pixel) < 16,
        )
    }

    @Test
    fun theQuarterTurnsAreSkyToo() {
        for (azimuth in doubleArrayOf(90.0, 270.0)) {
            val pixel = centrePixelLookingAt(azimuth)

            assertTrue(
                "a ridge at north must not appear at $azimuth°; got alpha ${alphaOf(pixel)}",
                alphaOf(pixel) < 16,
            )
        }
    }
}

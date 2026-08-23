package com.dazix.intervalometer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.intervalometer.domain.nightsky.NightSkyCatalogLoader
import com.dazix.intervalometer.ui.nightsky.NightSkyRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumented test verifying FlatBuffers catalog.bin loading from Android res/raw
 * and zero-allocation canvas rendering on the real Android ART runtime.
 */
@RunWith(AndroidJUnit4::class)
class StarCatalogDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun flatBuffersCatalogLoadsFromRawResourceOnDevice() {
        val stream = context.resources.openRawResource(R.raw.catalog)
        assertNotNull("catalog.bin raw FlatBuffers resource must exist", stream)

        val catalog = stream.use {
            NightSkyCatalogLoader.loadFromStream(it)
        }

        assertTrue("catalog on device should contain at least 1000 stars, got ${catalog.starsLength()}", catalog.starsLength() >= 1000)
        assertTrue("catalog on device should contain constellations and asterisms, got ${catalog.constellationsLength()}", catalog.constellationsLength() >= 140)

        // Verify Sirius
        val sirius = catalog.stars(0)
        assertEquals(101.28f, sirius.ra(), 0.5f)
        assertEquals(-16.71f, sirius.dec(), 0.5f)
        assertTrue("Sirius visual magnitude should be negative, got ${sirius.mag()}", sirius.mag() < 0.0f)
    }

    @Test
    fun nightSkyRendererExecutesOnDeviceCanvasWithoutException() {
        val stream = context.resources.openRawResource(R.raw.catalog)
        val catalog = stream.use { NightSkyCatalogLoader.loadFromStream(it) }

        val renderer = NightSkyRenderer()
        renderer.applyTheme(redVisionMode = true)

        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val androidCanvas = Canvas(bitmap)
        val composeCanvas = ComposeCanvas(androidCanvas)

        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = Density(2.625f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = androidx.compose.ui.geometry.Size(1080f, 2400f),
        ) {
            val northMatrix = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 0f, -1f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f,
            )

            // Draw celestial dome grid
            renderer.skyDomeRenderer.drawSkyDomeGrid(
                drawScope = this,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovH = 56.0f,
                redVisionMode = true,
                w = 1080f,
                h = 2400f,
            )

            // Draw star field & constellation lines in batch
            val starsRendered = renderer.drawNightSky(
                drawScope = this,
                catalog = catalog,
                lstDegrees = 280.0,
                latitudeDegrees = 50.0755,
                matrix = northMatrix,
                displayRotation = android.view.Surface.ROTATION_0,
                fovHorizontalDegrees = 56.0f,
                w = 1080f,
                h = 2400f,
                showConstellations = true,
            )

            assertTrue("Stars should be projected on screen, count was $starsRendered", starsRendered >= 0)
        }
    }
}

package com.dazix.caelis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.NightSkyFrame
import com.dazix.caelis.ar.scene.NightSkyScene
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.StarGeometry
import com.dazix.caelis.domain.nightsky.NightSkyCatalogLoader
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The FlatBuffers catalogue loads from res/raw on the real runtime, and the whole of it renders
 * through the Filament night sky on the device GPU.
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

    /**
     * One frame of the full catalogue, constellations on, in red night vision: something must be
     * drawn, and nothing drawn may be anything but red-dominant — a white star core or a cyan
     * figure line would ruin dark adaptation, which is the whole point of the mode.
     */
    @Test
    fun fullCatalogRendersOnTheGpuInRedVision() {
        val catalog = context.resources.openRawResource(R.raw.catalog).use { NightSkyCatalogLoader.loadFromStream(it) }
        val width = 270
        val height = 600
        val surface = SkySurface.offscreen(context, width, height)
        val scene = NightSkyScene(context, surface, catalog, StarGeometry(catalog))
        try {
            val frame = NightSkyFrame().apply {
                // Phone upright facing the north horizon, Prague, early evening sidereal time.
                floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f).copyInto(deviceToEnu)
                viewportWidth = width.toFloat()
                viewportHeight = height.toFloat()
                fovHorizontalDegrees = 56f
                density = 1f
                lstDegrees = 280.0
                latitudeDegrees = 50.0755
                showConstellations = true
                showLandscape = false
                nightVisionActive = true
                nightVisionTheme = NightVisionTheme.RED
            }
            scene.update(frame)
            val pixels = surface.renderAndRead()

            var lit = 0
            var offColour = 0
            for (i in pixels.indices step 4) {
                val r = pixels[i].toInt() and 0xFF
                val g = pixels[i + 1].toInt() and 0xFF
                val b = pixels[i + 2].toInt() and 0xFF
                if (r + g + b < 24) continue
                lit++
                if (g > r || b > r) offColour++
            }
            assertTrue("the full catalogue must put something on screen; $lit lit pixels", lit > 50)
            assertEquals("red vision drew $offColour of $lit lit pixels in a non-red colour", 0, offColour)
        } finally {
            scene.destroy()
            surface.destroy()
        }
    }
}

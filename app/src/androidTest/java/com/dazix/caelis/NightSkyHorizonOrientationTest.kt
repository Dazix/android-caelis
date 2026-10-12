package com.dazix.caelis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.TerrainRing
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which way round the real terrain horizon is drawn in Night Sky AR.
 *
 * The AGSL landscape shader once read the profile strip with the panorama's texture coordinate,
 * which has north in the middle of the image, and drew the whole skyline half a turn out — a ridge
 * due north appeared due south, and it looked entirely plausible. The Filament terrain ring builds
 * the orientation into its geometry (`HorizonMeshBuilder`, vertex i at azimuth i / 4°), and this
 * renders that real ring through the real `terrain.mat` on the GPU, offscreen, with a profile that has
 * terrain in exactly one direction. North must be ground; the other three cardinal points must be
 * sky, which catches a quarter-turn as well as a half-turn.
 */
@RunWith(AndroidJUnit4::class)
class NightSkyHorizonOrientationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val frameSize = 64
    private val fovDegrees = 60.0

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

    /** Alpha of the centre pixel looking at [azimuthDegrees] on the horizon: 0 is sky, 255 is ground. */
    private fun centreAlphaLookingAt(azimuthDegrees: Double): Int {
        val surface = SkySurface.offscreen(context, frameSize, frameSize)
        val material = surface.loadMaterial("terrain")
        val ring = TerrainRing(surface, material, priority = 6)
        try {
            ring.setProfile(northOnlyProfile())
            ring.setVisible(true)
            val deviceToEnu = FloatArray(16)
            CelestialMatrixCalculator.computeManualRotationMatrix(azimuthDegrees, 0.0, deviceToEnu)
            surface.setCameraPose(deviceToEnu, 0)
            surface.setFovHorizontal(fovDegrees)
            surface.setClearColor(0f, 0f, 0f, 0f)
            val pixels = surface.renderAndRead()
            val centre = ((frameSize / 2) * frameSize + frameSize / 2) * 4
            return pixels[centre + 3].toInt() and 0xFF
        } finally {
            ring.destroy()
            surface.engine.destroyMaterial(material)
            surface.destroy()
        }
    }

    @Test
    fun terrainDueNorthIsDrawnDueNorth() {
        val alpha = centreAlphaLookingAt(0.0)
        assertTrue("looking at the only ridge there is, the centre must be ground; got alpha $alpha", alpha > 200)
    }

    @Test
    fun theOppositeDirectionIsSky() {
        val alpha = centreAlphaLookingAt(180.0)
        assertTrue("a ridge at north must not appear at south; got alpha $alpha", alpha < 16)
    }

    /**
     * Pins the row order of `SkySurface.renderAndRead`, which `CameraBackgroundDeviceTest` reads
     * camera pictures with: looking level at the ridge, the top row is above the 20° crest (sky) and
     * the bottom row is ground. Assuming GL's bottom-up order made a correct camera picture read as
     * upside down.
     */
    @Test
    fun readBackPixelsComeTopRowFirst() {
        val surface = SkySurface.offscreen(context, frameSize, frameSize)
        val material = surface.loadMaterial("terrain")
        val ring = TerrainRing(surface, material, priority = 6)
        try {
            ring.setProfile(northOnlyProfile())
            ring.setVisible(true)
            val deviceToEnu = FloatArray(16)
            CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 0.0, deviceToEnu)
            surface.setCameraPose(deviceToEnu, 0)
            // 60° wide and square: the frame spans ±30°, so the top row is above the 20° ridge.
            surface.setFovHorizontal(fovDegrees)
            surface.setClearColor(0f, 0f, 0f, 0f)
            val pixels = surface.renderAndRead()
            val firstRow = pixels[(frameSize / 2) * 4 + 3].toInt() and 0xFF
            val lastRow = pixels[((frameSize - 1) * frameSize + frameSize / 2) * 4 + 3].toInt() and 0xFF
            assertTrue("the first row read back must be the top of the screen (sky); alpha $firstRow", firstRow < 16)
            assertTrue("the last row read back must be the bottom of the screen (ground); alpha $lastRow", lastRow > 200)
        } finally {
            ring.destroy()
            surface.engine.destroyMaterial(material)
            surface.destroy()
        }
    }

    @Test
    fun theQuarterTurnsAreSkyToo() {
        for (azimuth in doubleArrayOf(90.0, 270.0)) {
            val alpha = centreAlphaLookingAt(azimuth)
            assertTrue("a ridge at north must not appear at $azimuth°; got alpha $alpha", alpha < 16)
        }
    }
}

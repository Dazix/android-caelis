package com.dazix.caelis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.LabelBatch
import com.dazix.caelis.ar.scene.LandmarkMarks
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.SpriteAtlas
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A peak name's tick stands on the projector's point for the peak's azimuth and apparent altitude.
 *
 * The names are sprites placed on the GPU, while the hit test, the clearance rectangles and the
 * off-screen test use [SkyProjector]. If the foot of the tick drifted from that point, a name would
 * label the sky a few pixels away from the summit it claims. Same offscreen method as
 * [OverlaySpriteDeviceTest]: render, read back (top row first) and look at the pixels.
 */
@RunWith(AndroidJUnit4::class)
class LandmarkMarksDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val width = 240
    private val height = 160
    private val fov = 60f
    private val density = 2f

    private fun alpha(pixels: ByteArray, x: Int, y: Int) = pixels[(y * width + x) * 4 + 3].toInt() and 0xFF

    private fun landmark(name: String, az: Double, el: Double) = HorizonLandmark(
        name = name, nameCs = "", nameEn = "", elevationMeters = 1000,
        distanceMeters = 10_000.0, azimuthDegrees = az, apparentElevationDegrees = el,
    )

    /** Renders [landmark] with the camera looking at (0, 0) and returns the pixels and the projector. */
    private fun render(landmark: HorizonLandmark): Pair<ByteArray, SkyProjector> {
        val surface = SkySurface.offscreen(context, width, height)
        val material = surface.loadMaterial("label")
        val atlas = SpriteAtlas(surface.engine, 512, 128, LandmarkMarks.MAX_LANDMARKS)
        val labels = LabelBatch(surface, material, atlas, LandmarkMarks.MAX_LANDMARKS, priority = 7, blendOrder = 0)
        try {
            val deviceToEnu = FloatArray(16)
            CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 0.0, deviceToEnu)
            surface.setCameraPose(deviceToEnu, 0)
            surface.setFovHorizontal(fov.toDouble())
            surface.setClearColor(0f, 0f, 0f, 0f)
            val model = FloatArray(16)
            SkyFrameMath.cameraModelMatrix(deviceToEnu, 0, model)
            val projector = SkyProjector().also { it.update(model, width.toFloat(), height.toFloat(), fov) }

            val marks = LandmarkMarks()
            marks.update(listOf(landmark), "en", 0xFFFFFFFF.toInt(), density, 1f)
            atlas.begin()
            marks.addSprites(atlas)
            atlas.end()
            assertEquals("the name fitted in the atlas", 1, marks.spriteCount)
            marks.beginClearance()
            labels.begin()
            marks.emit(labels, projector)
            labels.end()
            return surface.renderAndRead() to projector
        } finally {
            labels.destroy()
            atlas.destroy()
            surface.engine.destroyMaterial(material)
            surface.destroy()
        }
    }

    @Test
    fun theFootOfTheTickIsOnTheProjectedPeak() {
        for ((az, el) in listOf(0.0 to 0.0, 8.0 to -3.0, -10.0 to 6.0)) {
            val (pixels, projector) = render(landmark("Mt", az, el))
            val expected = FloatArray(3)
            assertTrue(projector.projectAltAz(az, el, expected))

            // The lowest row that carries any of the sprite is the tick's foot (plus its casing).
            var bottom = -1
            for (y in height - 1 downTo 0) {
                if ((0 until width).any { alpha(pixels, it, y) > 128 }) {
                    bottom = y
                    break
                }
            }
            check(bottom >= 0) { "the name at ($az, $el) was not drawn at all" }
            var sx = 0.0
            var n = 0
            for (x in 0 until width) if (alpha(pixels, x, bottom) > 128) { sx += x + 0.5; n++ }
            val where = "peak ($az, $el)"
            assertEquals("x of the tick's foot, $where", expected[0], (sx / n).toFloat(), 1.5f)
            // The round cap and the casing reach about 3 px past the anchor at this density.
            assertTrue(
                "the tick's foot ($bottom) is at the projected y (${expected[1]}), $where",
                bottom >= expected[1] - 1f && bottom <= expected[1] + 4f,
            )
        }
    }

    @Test
    fun aVeryLongNameIsStillDrawn() {
        val (pixels, _) = render(landmark("Dlouhé jméno ".repeat(60), 0.0, 0.0))
        assertTrue("an over-long OSM name is cut, not dropped", (0 until height).any { y -> (0 until width).any { alpha(pixels, it, y) > 128 } })
    }
}

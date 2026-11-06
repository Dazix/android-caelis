package com.dazix.caelis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.LabelBatch
import com.dazix.caelis.ar.scene.NightSkyScene
import com.dazix.caelis.ar.scene.SkyPriority
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.SpriteAtlas
import com.dazix.caelis.ar.scene.TexturedQuad
import com.dazix.caelis.ar.scene.createBitmapTexture
import com.dazix.caelis.domain.arframe.SkyQuadMath
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Night Sky draws a peak name under the constellation artwork, not over it.
 *
 * The two are separate renderables of the same priority, and Filament's blend order orders only the
 * primitives of one renderable unless it is made global: with the local default the artwork's higher
 * blend order changed nothing, and which of the two ended on top was left to the sort of equal keys.
 * Renders a white block with the scene's peak-label settings and an opaque red quad with the
 * artwork's, in both creation orders, and reads the pixel where both lie.
 */
@RunWith(AndroidJUnit4::class)
class PeakLabelLayerDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val width = 200
    private val height = 200

    @Test
    fun theArtworkCoversAPeakNameCreatedAfterIt() = assertArtOnTop(artFirst = true)

    @Test
    fun theArtworkCoversAPeakNameCreatedBeforeIt() = assertArtOnTop(artFirst = false)

    private fun assertArtOnTop(artFirst: Boolean) {
        val surface = SkySurface.offscreen(context, width, height)
        val labelMaterial = surface.loadMaterial("label")
        val texturedMaterial = surface.loadMaterial("textured")
        val atlas = SpriteAtlas(surface.engine, 128, 128, 1)
        fun art() = TexturedQuad(
            surface, texturedMaterial, SkyPriority.CELESTIAL, NightSkyScene.ART_BLEND_ORDER,
            globalBlendOrder = NightSkyScene.GLOBAL_BLEND_ORDER,
        )
        fun labels() = LabelBatch(
            surface, labelMaterial, atlas, 1, NightSkyScene.PEAK_LABEL_PRIORITY, NightSkyScene.PEAK_LABEL_BLEND_ORDER,
            globalBlendOrder = NightSkyScene.GLOBAL_BLEND_ORDER,
        )
        val art: TexturedQuad
        val labels: LabelBatch
        if (artFirst) {
            art = art()
            labels = labels()
        } else {
            labels = labels()
            art = art()
        }
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val texture = createBitmapTexture(surface.engine, red)
        try {
            val deviceToEnu = FloatArray(16).also { CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 0.0, it) }
            surface.setCameraPose(deviceToEnu, 0)
            surface.setFovHorizontal(60.0)
            surface.setClearColor(0f, 0f, 0f, 1f)

            // North on the horizon (ENU +Y), where the camera looks.
            val corners = FloatArray(12)
            check(SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 0f, 1f, 0.3f, 0.3f, corners))
            art.setCorners(corners)
            art.setTexture(texture)
            art.setOpacity(1f)
            art.setTransform(FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f })

            val white = Paint().apply { color = Color.WHITE }
            atlas.begin()
            val block = atlas.add(40, 40, 20f, 20f) { canvas, left, top ->
                canvas.drawRect(left, top, left + 40f, top + 40f, white)
            }
            atlas.end()
            check(block >= 0)
            labels.begin()
            labels.add(block, 0f, 1f, 0f)
            labels.end()

            val pixels = surface.renderAndRead()
            val i = ((height / 2) * width + width / 2) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            assertTrue(
                "art created ${if (artFirst) "first" else "second"}: the centre is the artwork's red, " +
                    "not the name's white (r=$r g=$g)",
                r > 200 && g < 60,
            )
        } finally {
            labels.destroy()
            art.destroy()
            surface.engine.destroyTexture(texture)
            atlas.destroy()
            surface.engine.destroyMaterial(labelMaterial)
            surface.engine.destroyMaterial(texturedMaterial)
            surface.destroy()
        }
    }
}

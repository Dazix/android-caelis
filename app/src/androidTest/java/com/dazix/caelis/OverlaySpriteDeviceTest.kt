package com.dazix.caelis

import android.content.Context
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.scene.GlyphBatch
import com.dazix.caelis.ar.scene.LabelBatch
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.SpriteAtlas
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The marks drawn on the GPU (`glyph.mat`, `label.mat`) land where the CPU projector says.
 *
 * Names and glyphs moved from a Compose overlay into the Filament frame because the TextureView
 * showed the GPU frame 2-3 vsyncs after the overlay. They are still placed by the same geometry the
 * hit test and the off-screen chevron use ([SkyProjector]); if the shaders' pixel arithmetic
 * disagreed with it, a tap would select something a few pixels from where it is drawn. This renders
 * them offscreen and reads the pixels back (top row first, as `NightSkyHorizonOrientationTest` pins).
 */
@RunWith(AndroidJUnit4::class)
class OverlaySpriteDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val width = 200
    private val height = 120
    private val fov = 60f

    private class Rig(val surface: SkySurface, val projector: SkyProjector)

    private fun <T> withSurface(
        cameraAz: Double,
        cameraEl: Double,
        rotationDegrees: Int,
        block: (Rig) -> T,
    ): T {
        val surface = SkySurface.offscreen(context, width, height)
        try {
            val deviceToEnu = FloatArray(16)
            CelestialMatrixCalculator.computeManualRotationMatrix(cameraAz, cameraEl, deviceToEnu)
            surface.setCameraPose(deviceToEnu, rotationDegrees)
            surface.setFovHorizontal(fov.toDouble())
            surface.setClearColor(0f, 0f, 0f, 0f)
            val model = FloatArray(16)
            SkyFrameMath.cameraModelMatrix(deviceToEnu, rotationDegrees, model)
            val projector = SkyProjector().also { it.update(model, width.toFloat(), height.toFloat(), fov) }
            return block(Rig(surface, projector))
        } finally {
            surface.destroy()
        }
    }

    private fun alpha(pixels: ByteArray, x: Int, y: Int) = pixels[(y * width + x) * 4 + 3].toInt() and 0xFF

    /** Centroid of every pixel with alpha above half, or null if there is none. */
    private fun centroid(pixels: ByteArray): FloatArray? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha(pixels, x, y) > 128) {
                sx += x + 0.5
                sy += y + 0.5
                n++
            }
        }
        return if (n == 0) null else floatArrayOf((sx / n).toFloat(), (sy / n).toFloat())
    }

    private fun renderDisc(rig: Rig, az: Double, el: Double): ByteArray {
        val material = rig.surface.loadMaterial("glyph")
        val glyphs = GlyphBatch(rig.surface, material, 4, priority = 7, blendOrder = 0)
        try {
            val d = FloatArray(3)
            AltAzGridBuilder.direction(az, el, d)
            glyphs.begin()
            glyphs.add(GlyphBatch.Shape.DISC, d[0], d[1], d[2], radiusPx = 4f, strokePx = 0f, argb = 0xFFFFFFFF.toInt())
            glyphs.end()
            return rig.surface.renderAndRead()
        } finally {
            glyphs.destroy()
            rig.surface.engine.destroyMaterial(material)
        }
    }

    private fun assertDiscAtProjection(cameraAz: Double, cameraEl: Double, rotation: Int, az: Double, el: Double) {
        withSurface(cameraAz, cameraEl, rotation) { rig ->
            val expected = FloatArray(3)
            assertTrue(rig.projector.projectAltAz(az, el, expected))
            val drawn = centroid(renderDisc(rig, az, el))
            checkNotNull(drawn) { "the disc at ($az, $el) was not drawn at all" }
            val where = "camera ($cameraAz, $cameraEl, rot $rotation), disc ($az, $el)"
            assertEquals("x of $where", expected[0], drawn[0], 1f)
            assertEquals("y of $where", expected[1], drawn[1], 1f)
        }
    }

    @Test
    fun aGlyphLandsWhereTheProjectorPutsItsDirection() {
        assertDiscAtProjection(0.0, 0.0, 0, 0.0, 0.0)
        assertDiscAtProjection(0.0, 0.0, 0, 20.0, 8.0)
        assertDiscAtProjection(0.0, 0.0, 0, -25.0, -12.0)
        assertDiscAtProjection(130.0, 35.0, 0, 145.0, 30.0)
    }

    @Test
    fun aGlyphFollowsTheDisplayRotationLikeTheProjector() {
        assertDiscAtProjection(0.0, 0.0, 90, 10.0, 5.0)
        assertDiscAtProjection(0.0, 0.0, 270, -12.0, 4.0)
    }

    @Test
    fun aGlyphBehindTheCameraIsNotDrawn() {
        withSurface(0.0, 0.0, 0) { rig ->
            val pixels = renderDisc(rig, 180.0, 0.0)
            assertEquals("a disc dead behind the camera must not be mirrored onto the screen", null, centroid(pixels))
        }
    }

    /**
     * A sprite with red on the left, green on the right and blue below, anchored at its top-left and
     * offset by (10, 6) pixels: the colours must come out where they were drawn, so the atlas is
     * neither flipped nor mirrored and the offset is in screen pixels, Y down.
     */
    @Test
    fun aLabelIsDrawnUprightAndOffsetInScreenPixels() {
        withSurface(0.0, 0.0, 0) { rig ->
            val material = rig.surface.loadMaterial("label")
            val atlas = SpriteAtlas(rig.surface.engine, 64, 64, 4)
            val labels = LabelBatch(rig.surface, material, atlas, 4, priority = 7, blendOrder = 0)
            try {
                val paint = Paint()
                atlas.begin()
                val sprite = atlas.add(16, 8, 0f, 0f) { c, left, top ->
                    paint.color = 0xFFFF0000.toInt(); c.drawRect(left, top, left + 8, top + 4, paint)
                    paint.color = 0xFF00FF00.toInt(); c.drawRect(left + 8, top, left + 16, top + 4, paint)
                    paint.color = 0xFF0000FF.toInt(); c.drawRect(left, top + 4, left + 16, top + 8, paint)
                }
                atlas.end()
                labels.begin()
                assertTrue(labels.add(sprite, 0f, 1f, 0f, offsetXPx = 10f, offsetYPx = 6f))
                labels.end()
                val pixels = rig.surface.renderAndRead()
                val ox = width / 2 + 10
                val oy = height / 2 + 6
                fun rgb(x: Int, y: Int): Int {
                    val i = (y * width + x) * 4
                    return ((pixels[i].toInt() and 0xFF) shl 16) or ((pixels[i + 1].toInt() and 0xFF) shl 8) or (pixels[i + 2].toInt() and 0xFF)
                }
                assertEquals("top-left of the sprite is red", 0xFF0000, rgb(ox + 3, oy + 1))
                assertEquals("top-right of the sprite is green", 0x00FF00, rgb(ox + 12, oy + 1))
                assertEquals("bottom of the sprite is blue", 0x0000FF, rgb(ox + 6, oy + 6))
                assertEquals("left of the offset sprite is empty", 0, alpha(pixels, ox - 3, oy + 2))
                assertEquals("above the offset sprite is empty", 0, alpha(pixels, ox + 6, oy - 3))
            } finally {
                labels.destroy()
                atlas.destroy()
                rig.surface.engine.destroyMaterial(material)
            }
        }
    }

    /** The grid's altitude labels are kept off the screen edge; the margin test runs on the GPU. */
    @Test
    fun aLabelWithAMarginIsHiddenNearTheEdge() {
        withSurface(0.0, 0.0, 0) { rig ->
            val material = rig.surface.loadMaterial("label")
            val atlas = SpriteAtlas(rig.surface.engine, 64, 64, 4)
            val labels = LabelBatch(rig.surface, material, atlas, 4, priority = 7, blendOrder = 0)
            try {
                val paint = Paint().apply { color = 0xFFFFFFFF.toInt() }
                atlas.begin()
                val sprite = atlas.add(6, 6, 3f, 3f) { c, left, top -> c.drawRect(left, top, left + 6, top + 6, paint) }
                atlas.end()
                val nearEdge = FloatArray(3)
                rig.projector.unproject(15f, height / 2f, nearEdge)
                labels.begin()
                labels.add(sprite, nearEdge[0], nearEdge[1], nearEdge[2], marginXPx = 20f, marginYPx = 20f)
                labels.add(sprite, 0f, 1f, 0f, marginXPx = 20f, marginYPx = 20f)
                labels.end()
                val pixels = rig.surface.renderAndRead()
                assertEquals("15 px from the edge with a 20 px margin: hidden", 0, alpha(pixels, 15, height / 2))
                assertTrue("in the centre: shown", alpha(pixels, width / 2, height / 2) > 200)
            } finally {
                labels.destroy()
                atlas.destroy()
                rig.surface.engine.destroyMaterial(material)
            }
        }
    }
}

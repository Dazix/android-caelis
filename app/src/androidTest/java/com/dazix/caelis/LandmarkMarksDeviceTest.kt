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
import com.dazix.caelis.domain.arframe.TiltedLabel
import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    private val width = 320
    private val height = 240
    private val fov = 60f
    private val density = 2f

    private fun alpha(pixels: ByteArray, x: Int, y: Int) = pixels[(y * width + x) * 4 + 3].toInt() and 0xFF

    private fun landmark(name: String, az: Double, el: Double) = HorizonLandmark(
        name = name, nameCs = "", nameEn = "", elevationMeters = 1000,
        distanceMeters = 10_000.0, azimuthDegrees = az, apparentElevationDegrees = el,
    )

    /** A phone looking north at the horizon, rolled by [rollDeg] (its right edge raised). */
    private fun rolledDeviceToEnu(rollDeg: Double): FloatArray {
        val r = Math.toRadians(rollDeg)
        val m = FloatArray(16)
        // Device right, up and back in ENU, laid out as computeManualRotationMatrix writes them.
        m[0] = cos(r).toFloat(); m[4] = 0f; m[8] = sin(r).toFloat()
        m[1] = -sin(r).toFloat(); m[5] = 0f; m[9] = cos(r).toFloat()
        m[2] = 0f; m[6] = -1f; m[10] = 0f
        m[15] = 1f
        return m
    }

    /** Renders [landmark] with the camera looking at (0, 0) and returns the pixels and the projector. */
    private fun render(
        landmark: HorizonLandmark,
        rollDeg: Double = 0.0,
        fadeWholeScreen: Boolean = false,
    ): Pair<ByteArray, SkyProjector> {
        val surface = SkySurface.offscreen(context, width, height)
        val material = surface.loadMaterial("label")
        val atlas = SpriteAtlas(surface.engine, 512, 128, LandmarkMarks.ATLAS_SPRITES)
        val labels = LabelBatch(surface, material, atlas, LandmarkMarks.LISTED_SPRITES, priority = 7, blendOrder = 0)
        try {
            val deviceToEnu = if (rollDeg == 0.0) {
                FloatArray(16).also { CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 0.0, it) }
            } else {
                rolledDeviceToEnu(rollDeg)
            }
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
            if (fadeWholeScreen) marks.fadeUnder(width / 2f, height / 2f, width / 2f, height / 2f)
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
    fun aNameUnderAConstellationDrawingIsFaded() {
        val maxAlpha = { pixels: ByteArray ->
            var m = 0
            for (y in 0 until height) for (x in 0 until width) m = maxOf(m, alpha(pixels, x, y))
            m
        }
        val clear = maxAlpha(render(landmark("Mt", 0.0, 0.0)).first)
        val faded = maxAlpha(render(landmark("Mt", 0.0, 0.0), fadeWholeScreen = true).first)
        assertTrue("the name is drawn at full strength on clear sky (alpha $clear)", clear > 230)
        // FADED_ALPHA (0.3) of the strongest pixel, with a little room for filtering.
        assertTrue("faded under a drawing to ~30 % (alpha $faded)", faded in 50..90)
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

    /**
     * Rolled, the tick still stands on the local vertical and the name starts at the tick's top and
     * rises at 60° from the horizon, turned with it. Held screen-upright, a name swung against the
     * ridge whenever the phone rolled; a wrong rotation sign would put the text on the other side.
     */
    @Test
    fun theTickAndTheNameTurnWithTheHorizon() {
        val az = 0.0
        val el = -6.0
        for (rollDeg in listOf(0.0, 30.0, -30.0)) {
            val (pixels, projector) = render(landmark("Testovací vrch", az, el), rollDeg)
            val anchor = FloatArray(3)
            assertTrue(projector.projectAltAz(az, el, anchor))
            val up = FloatArray(2)
            TiltedLabel.screenUp(projector, az, el, anchor[0], anchor[1], up, FloatArray(3))
            // The vertical leans with the roll: the phone's right edge raised turns the sky clockwise.
            val roll = Math.toRadians(rollDeg)
            assertEquals("local vertical x, roll $rollDeg", sin(roll).toFloat(), up[0], 0.03f)
            assertEquals("local vertical y, roll $rollDeg", -cos(roll).toFloat(), up[1], 0.03f)

            val tick = LandmarkMarks.TICK_DP * density
            var tickAcross = 0.0
            var tickN = 0
            var sx = 0.0
            var sy = 0.0
            var n = 0
            for (y in 0 until height) for (x in 0 until width) {
                if (alpha(pixels, x, y) <= 128) continue
                val ox = x + 0.5f - anchor[0]
                val oy = y + 0.5f - anchor[1]
                val along = ox * up[0] + oy * up[1]
                val across = ox * -up[1] + oy * up[0]
                if (along in 2f..tick - 2f && abs(across) < 6f) {
                    tickAcross += across; tickN++
                } else if (along > tick) {
                    sx += x + 0.5; sy += y + 0.5; n++
                }
            }
            val where = "roll $rollDeg"
            check(tickN > 0) { "no tick drawn, $where" }
            assertEquals("the tick is centred on the local vertical, $where", 0.0, tickAcross / tickN, 1.0)
            check(n > 0) { "no name drawn, $where" }

            // The name's ink lies along the expected axis, starting at the tick's top.
            val a = Math.toRadians(60.0)
            val rightX = -up[1]
            val rightY = up[0]
            val axisX = rightX * cos(a) + up[0] * sin(a)
            val axisY = rightY * cos(a) + up[1] * sin(a)
            val cx = sx / n - anchor[0]
            val cy = sy / n - anchor[1]
            val centroidAngle = Math.toDegrees(atan2(cy * axisX - cx * axisY, cx * axisX + cy * axisY))
            // The centroid sits on the text's axis from the pivot (lifted by the tick and the gap) within a few degrees.
            val liftX = up[0] * (LandmarkMarks.TICK_DP + LandmarkMarks.TEXT_GAP_DP) * density
            val liftY = up[1] * (LandmarkMarks.TICK_DP + LandmarkMarks.TEXT_GAP_DP) * density
            val px = cx - liftX
            val py = cy - liftY
            val offAxis = abs(px * -axisY + py * axisX)
            val alongAxis = px * axisX + py * axisY
            assertTrue("the name runs along 60° from the horizon ($offAxis px off its axis), $where", offAxis < 4.0)
            assertTrue("the name runs away from the tick, not back over it ($alongAxis px), $where", alongAxis > 20.0)
            assertTrue("the centroid is on the name's side of the tick ($centroidAngle°), $where", abs(centroidAngle) < 30.0)
        }
    }

    @Test
    fun aVeryLongNameIsStillDrawn() {
        val (pixels, _) = render(landmark("Dlouhé jméno ".repeat(60), 0.0, 0.0))
        assertTrue("an over-long OSM name is cut, not dropped", (0 until height).any { y -> (0 until width).any { alpha(pixels, it, y) > 128 } })
    }
}

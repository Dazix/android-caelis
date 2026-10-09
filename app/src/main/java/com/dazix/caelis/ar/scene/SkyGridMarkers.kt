package com.dazix.caelis.ar.scene

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.nightsky.NightVisionTheme

/**
 * The text and dots that go with the GPU alt-az grid: altitude labels, cardinal points, zenith and
 * nadir. The lattice itself is geometry ([AltAzGridBuilder]); Filament has no text, so these are
 * drawn by the Compose overlay through the [SkyProjector] the frame was rendered with.
 *
 * Offsets, sizes and colours are the ones the Canvas grid used. Zero allocations per draw.
 */
class SkyGridMarkers {

    var zenithLabel = "ZENITH (+90°)"
        private set
    var nadirLabel = "NADIR (-90°)"
        private set
    private var cardinalLabels = arrayOf(
        "N (0°)", "NE (45°)", "E (90°)", "SE (135°)",
        "S (180°)", "SW (225°)", "W (270°)", "NW (315°)",
    )
    private val altitudeLabels = AltAzGridBuilder.ALTITUDE_LEVELS
        .map { "${if (it > 0) "+" else ""}${it.toInt()}°" }
        .toTypedArray()

    val horizonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = 10.5f
    }
    val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9.0f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var horizonArgb = NormalHorizon.toArgb()
    private var minorArgb = NormalGrid.toArgb()
    private val projected = FloatArray(3)

    init {
        applyTheme(nightVision = false, theme = NightVisionTheme.AMBER)
    }

    fun updateLabels(zenith: String, nadir: String, cardinals: Array<String>) {
        zenithLabel = zenith
        nadirLabel = nadir
        cardinalLabels = cardinals
    }

    fun applyTheme(nightVision: Boolean, theme: NightVisionTheme) {
        val palette = paletteForTheme(theme)
        if (nightVision) {
            horizonTextPaint.color = withAlpha(palette.primary.toArgb(), 143)
            gridTextPaint.color = withAlpha(palette.secondary.toArgb(), 143)
            horizonArgb = palette.secondary.toArgb()
            minorArgb = palette.grid.toArgb()
        } else {
            horizonTextPaint.color = AndroidColor.argb(143, 255, 255, 255)
            gridTextPaint.color = AndroidColor.argb(143, 77, 208, 225)
            horizonArgb = NormalHorizon.toArgb()
            minorArgb = NormalGrid.toArgb()
        }
    }

    fun draw(canvas: Canvas, projector: SkyProjector, w: Float, h: Float) {
        val levels = AltAzGridBuilder.ALTITUDE_LEVELS
        for (li in levels.indices) {
            val elevation = levels[li]
            for (az in LABEL_AZIMUTHS) {
                if (!projector.projectAltAz(az, elevation, projected)) continue
                val px = projected[0]
                val py = projected[1]
                if (px in 20f..(w - 20f) && py in 40f..(h - 40f)) canvas.drawText(altitudeLabels[li], px, py - 4f, gridTextPaint)
            }
        }
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = horizonArgb
        for (i in CARDINAL_AZIMUTHS.indices) {
            if (!projector.projectAltAz(CARDINAL_AZIMUTHS[i], 0.0, projected)) continue
            val px = projected[0]
            val py = projected[1]
            if (px !in -50f..(w + 50f) || py !in -50f..(h + 50f)) continue
            canvas.drawCircle(px, py, 3.5f, markerPaint)
            canvas.drawText(cardinalLabels[i], px, py - 10f, horizonTextPaint)
        }
        if (projector.projectAltAz(0.0, 90.0, projected)) {
            val zx = projected[0]
            val zy = projected[1]
            if (zx in -50f..(w + 50f) && zy in -50f..(h + 50f)) {
                drawCross(canvas, zx, zy, 12f, 6f, 1.5f, 1.4f, horizonArgb)
                canvas.drawText(zenithLabel, zx, zy - 15f, horizonTextPaint)
            }
        }
        if (projector.projectAltAz(0.0, -90.0, projected)) {
            val nx = projected[0]
            val ny = projected[1]
            if (nx in -50f..(w + 50f) && ny in -50f..(h + 50f)) {
                drawCross(canvas, nx, ny, 10f, 5f, 1.2f, 1.0f, minorArgb)
                canvas.drawText(nadirLabel, nx, ny - 13f, gridTextPaint)
            }
        }
    }

    private fun drawCross(
        canvas: Canvas, x: Float, y: Float,
        arm: Float, ring: Float, armWidth: Float, ringWidth: Float, argb: Int,
    ) {
        markerPaint.color = argb
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = armWidth
        canvas.drawLine(x - arm, y, x + arm, y, markerPaint)
        canvas.drawLine(x, y - arm, x, y + arm, markerPaint)
        markerPaint.strokeWidth = ringWidth
        canvas.drawCircle(x, y, ring, markerPaint)
    }

    private fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        val CARDINAL_AZIMUTHS = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        val LABEL_AZIMUTHS = doubleArrayOf(0.0, 90.0, 180.0, 270.0, 45.0, 135.0, 225.0, 315.0)
    }
}

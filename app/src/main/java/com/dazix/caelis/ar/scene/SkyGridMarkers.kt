package com.dazix.caelis.ar.scene

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.nightsky.NightVisionTheme

/**
 * The text and dots that go with the GPU alt-az grid: altitude labels, cardinal points, zenith and
 * nadir. The lattice itself is geometry ([AltAzGridBuilder]); these are sprites of a [SpriteAtlas]
 * and glyphs, drawn in the same Filament frame (they were a Compose overlay once, which the
 * TextureView showed a few vsyncs apart from the grid lines).
 *
 * Offsets, sizes and colours are the ones the Canvas grid used. The owner sets the paints' text
 * sizes, calls [addSprites] while its atlas is being rebuilt and [emit] while its batches are
 * listed; both are allocation-free apart from the atlas rebuild itself.
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
    private var horizonArgb = NormalHorizon.toArgb()
    private var minorArgb = NormalGrid.toArgb()

    private val altitudeSprites = IntArray(altitudeLabels.size) { -1 }
    private val cardinalSprites = IntArray(CARDINAL_AZIMUTHS.size) { -1 }
    private var zenithSprite = -1
    private var nadirSprite = -1
    private val direction = FloatArray(3)

    /** What the sprites were last drawn with; a change means the owner must rebuild its atlas. */
    var contentKey = 0
        private set

    init {
        applyTheme(nightVision = false, theme = NightVisionTheme.AMBER)
    }

    fun updateLabels(zenith: String, nadir: String, cardinals: Array<String>) {
        if (zenith == zenithLabel && nadir == nadirLabel && cardinals.contentEquals(cardinalLabels)) return
        zenithLabel = zenith
        nadirLabel = nadir
        cardinalLabels = cardinals
        contentKey++
    }

    fun applyTheme(nightVision: Boolean, theme: NightVisionTheme) {
        val palette = paletteForTheme(theme)
        val horizonText: Int
        val gridText: Int
        if (nightVision) {
            horizonText = withAlpha(palette.primary.toArgb(), 143)
            gridText = withAlpha(palette.secondary.toArgb(), 143)
            horizonArgb = palette.secondary.toArgb()
            minorArgb = palette.grid.toArgb()
        } else {
            horizonText = AndroidColor.argb(143, 255, 255, 255)
            gridText = AndroidColor.argb(143, 77, 208, 225)
            horizonArgb = NormalHorizon.toArgb()
            minorArgb = NormalGrid.toArgb()
        }
        if (horizonText != horizonTextPaint.color || gridText != gridTextPaint.color) {
            horizonTextPaint.color = horizonText
            gridTextPaint.color = gridText
            contentKey++
        }
    }

    /** Draws every label into [atlas], which the owner has begun. Call again after any change. */
    fun addSprites(atlas: SpriteAtlas) {
        for (i in altitudeLabels.indices) altitudeSprites[i] = atlas.addText(altitudeLabels[i], gridTextPaint)
        for (i in cardinalLabels.indices) cardinalSprites[i] = atlas.addText(cardinalLabels[i], horizonTextPaint)
        zenithSprite = atlas.addText(zenithLabel, horizonTextPaint)
        nadirSprite = atlas.addText(nadirLabel, gridTextPaint)
    }

    /** Lists the markers in ENU into batches the owner has begun (identity transform). */
    fun emit(labels: LabelBatch, glyphs: GlyphBatch) {
        val levels = AltAzGridBuilder.ALTITUDE_LEVELS
        val d = direction
        for (li in levels.indices) {
            for (az in LABEL_AZIMUTHS) {
                AltAzGridBuilder.direction(az, levels[li], d)
                // Kept off the screen edges, where the labels of every meridian would crowd.
                labels.add(altitudeSprites[li], d[0], d[1], d[2], offsetYPx = -4f, marginXPx = 20f, marginYPx = 40f)
            }
        }
        for (i in CARDINAL_AZIMUTHS.indices) {
            AltAzGridBuilder.direction(CARDINAL_AZIMUTHS[i], 0.0, d)
            glyphs.add(GlyphBatch.Shape.DISC, d[0], d[1], d[2], radiusPx = 3.5f, strokePx = 0f, argb = horizonArgb)
            labels.add(cardinalSprites[i], d[0], d[1], d[2], offsetYPx = -10f)
        }
        AltAzGridBuilder.direction(0.0, 90.0, d)
        glyphs.add(GlyphBatch.Shape.GRID_CROSS, d[0], d[1], d[2], radiusPx = 6f, strokePx = 1.4f, argb = horizonArgb, a = 12f, b = 1.5f)
        labels.add(zenithSprite, d[0], d[1], d[2], offsetYPx = -15f)
        AltAzGridBuilder.direction(0.0, -90.0, d)
        glyphs.add(GlyphBatch.Shape.GRID_CROSS, d[0], d[1], d[2], radiusPx = 5f, strokePx = 1.0f, argb = minorArgb, a = 10f, b = 1.2f)
        labels.add(nadirSprite, d[0], d[1], d[2], offsetYPx = -13f)
    }

    private fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        /** Sprites [addSprites] adds, glyphs and labels [emit] lists at most. */
        val SPRITE_COUNT = AltAzGridBuilder.ALTITUDE_LEVELS.size + 8 + 2
        val LABEL_COUNT = AltAzGridBuilder.ALTITUDE_LEVELS.size * 8 + 8 + 2
        const val GLYPH_COUNT = 8 + 2

        private val CARDINAL_AZIMUTHS = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        private val LABEL_AZIMUTHS = doubleArrayOf(0.0, 90.0, 180.0, 270.0, 45.0, 135.0, 225.0, 315.0)
    }
}

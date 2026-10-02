package com.dazix.caelis.ar.scene

import com.dazix.caelis.ar.paletteForTheme
import com.dazix.caelis.domain.nightsky.NightVisionTheme

/**
 * The four night-vision colours the materials read (see [SkySurface.setNightVision]), taken from
 * the same palette the Canvas renderer's Paints and shaders used.
 */
class NightVisionColors(
    val shaderTint: FloatArray,
    val primary: FloatArray,
    val secondary: FloatArray,
    val artMultiply: FloatArray,
) {
    companion object {
        val NONE = NightVisionColors(FloatArray(3), FloatArray(3), FloatArray(3), FloatArray(3))

        private val cache = NightVisionTheme.entries.associateWith { theme ->
            val palette = paletteForTheme(theme)
            val primary = floatArrayOf(palette.primary.red, palette.primary.green, palette.primary.blue)
            NightVisionColors(
                shaderTint = floatArrayOf(palette.shaderTintR, palette.shaderTintG, palette.shaderTintB),
                primary = primary,
                secondary = floatArrayOf(palette.secondary.red, palette.secondary.green, palette.secondary.blue),
                // The red art filter was Color.RED, not the palette primary (FF1744).
                artMultiply = if (theme == NightVisionTheme.RED) floatArrayOf(1f, 0f, 0f) else primary,
            )
        }

        fun forTheme(theme: NightVisionTheme): NightVisionColors = cache.getValue(theme)
    }
}

package com.dazix.caelis.ar.scene

import androidx.compose.ui.graphics.Color
import com.dazix.caelis.domain.nightsky.NightVisionTheme

// The colours every AR layer is drawn in, GPU geometry and Compose overlay alike, so a line and the
// label next to it always agree.

// Normal Color Palette
val NormalGoldSolar = Color(0xFFFFB300)
val NormalSunrise = Color(0xFFFF7043)
val NormalSunset = Color(0xFFE64A19)
val NormalHorizon = Color(0xDD4DD0E1)
val NormalGrid = Color(0x664DD0E1)
val NormalNightArc = Color(0x44FFB300)

/**
 * The real terrain skyline. Deliberately a different hue from [NormalHorizon]: the two lines are
 * drawn together and the whole point is telling the mathematical horizon apart from the ground.
 */
val NormalTerrain = Color(0xFF8BC34A)

/** Casing drawn under the silhouette so it stays readable against a bright sky or a pale field. */
val SilhouetteCasing = Color(0x99000000)

// Red Vision (Astrophotography Night Mode) Palette
val RedPrimary = Color(0xFFFF1744)
val RedSecondary = Color(0xFFD32F2F)
val RedGrid = Color(0x33FF1744)
val RedNightArc = Color(0x33FF1744)

data class NightVisionPalette(
    val primary: Color,
    val secondary: Color,
    val grid: Color,
    val nightArc: Color,
    val shaderTintR: Float,
    val shaderTintG: Float,
    val shaderTintB: Float,
)

val RedNightVisionPalette = NightVisionPalette(
    primary = Color(0xFFFF1744),
    secondary = Color(0xFFD32F2F),
    grid = Color(0x33FF1744),
    nightArc = Color(0x33FF1744),
    shaderTintR = 1.0f,
    shaderTintG = 0.0f,
    shaderTintB = 0.0f,
)

val AmberNightVisionPalette = NightVisionPalette(
    primary = Color(0xFFFFB300),
    secondary = Color(0xFFFF8F00),
    grid = Color(0x33FFB300),
    nightArc = Color(0x33FFB300),
    shaderTintR = 1.0f,
    shaderTintG = 0.7f,
    shaderTintB = 0.0f,
)

val GreenNightVisionPalette = NightVisionPalette(
    primary = Color(0xFF00E676),
    secondary = Color(0xFF00C853),
    grid = Color(0x3300E676),
    nightArc = Color(0x3300E676),
    shaderTintR = 0.0f,
    shaderTintG = 1.0f,
    shaderTintB = 0.0f,
)

fun paletteForTheme(theme: NightVisionTheme): NightVisionPalette = when (theme) {
    NightVisionTheme.RED -> RedNightVisionPalette
    NightVisionTheme.AMBER -> AmberNightVisionPalette
    NightVisionTheme.GREEN -> GreenNightVisionPalette
}

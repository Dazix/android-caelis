package com.dazix.caelis.domain.nightsky

import kotlin.math.tan

/**
 * How big and how bright a planet, the Sun or the Moon is drawn, in screen pixels.
 *
 * Lifted out of the Canvas renderer unchanged so the GPU scene, the label overlay and the tap hit
 * test all size a body the same way:
 * - the pinhole diameter `2 f tan(d / 2)`, with a missing or implausible lunar diameter replaced by
 *   0.518 deg;
 * - the "planetarium trick": everything but the Moon is enlarged by `0.013 / d`, clamped to 1..10,
 *   so Neptune is not a single pixel next to Jupiter;
 * - below 15 px a body is an optical point (core + magnitude halo), above it a textured disc with an
 *   atmospheric rim glow that fades as the disc grows.
 *
 * Pure Kotlin; [compute] writes into a caller-owned instance, so the frame loop allocates nothing.
 */
class PlanetAppearance {

    /** True when the body is large enough to be drawn as its texture. */
    var textured = false
        private set

    /** Body diameter after the boost, at least [MIN_TEXTURE_DIAMETER_PX] when [textured]. */
    var diameterPx = 0f
        private set

    /** Point state: radius of the solid core. */
    var coreRadiusPx = 0f
        private set

    /** Point state: the magnitude halo; texture state: the rim glow. */
    var glowRadiusPx = 0f
        private set

    /** Alpha of [glowRadiusPx]'s glow, 0..1 (the Canvas Paint alpha / 255). */
    var glowAlpha = 0f
        private set

    /** Hit and selection radius: the larger of the drawn extent and a minimum touch size. */
    var hitRadiusPx = 0f
        private set

    /** Texture width over the body's width: Saturn's image includes its rings. */
    var imageToBodyRatio = 1f
        private set

    fun compute(
        id: String,
        isMoon: Boolean,
        angularDiameterDeg: Float,
        magnitude: Float,
        focalLengthPx: Float,
        density: Float,
        zoomFactor: Float,
    ) {
        val moon = isMoon || id.equals("moon", ignoreCase = true)
        val safeDiameterDeg = when {
            moon -> if (angularDiameterDeg in 0.3f..1.5f) angularDiameterDeg else MEAN_MOON_DIAMETER_DEG
            angularDiameterDeg > 0f -> angularDiameterDeg
            else -> 0.01f
        }
        var diameter = 2f * focalLengthPx * tan(Math.toRadians(safeDiameterDeg.toDouble() * 0.5)).toFloat()
        if (!moon) diameter *= (0.013f / safeDiameterDeg).coerceIn(1.0f, 10.0f)

        // Brighter (more negative) magnitudes get larger, stronger halos; -5 (Venus) .. 8 (Neptune).
        val intensity = 1.0f - ((magnitude.coerceIn(-5.0f, 8.0f) + 5.0f) / 13.0f)
        imageToBodyRatio = if (id.equals("Saturn", ignoreCase = true)) SATURN_IMAGE_RATIO else 1f

        if (diameter < MIN_TEXTURE_DIAMETER_PX) {
            textured = false
            diameterPx = diameter
            val opticScale = StarOptics.computeOpticScale(zoomFactor)
            coreRadiusPx = 2.5f * density * opticScale
            glowRadiusPx = (10.0f + 25.0f * intensity) * (2f / 3f) * density * opticScale
            glowAlpha = (140 * intensity).toInt().coerceIn(25, 200) / 255f
            hitRadiusPx = maxOf(10f * density, glowRadiusPx)
        } else {
            textured = true
            diameterPx = diameter.coerceAtLeast(MIN_TEXTURE_DIAMETER_PX)
            coreRadiusPx = diameterPx * 0.5f
            // The glow is sized to what the disc visibly occupies, rings included.
            val ringMultiplier = when {
                id.equals("Saturn", ignoreCase = true) -> 2.38f
                id.equals("Uranus", ignoreCase = true) -> 1.4f
                else -> 1.2f
            }
            glowRadiusPx = diameterPx * 0.5f * ringMultiplier
            val zoomFade = (30f / diameterPx).coerceIn(0.1f, 1.0f)
            glowAlpha = (140 * intensity * zoomFade).toInt().coerceIn(15, 200) / 255f
            hitRadiusPx = maxOf(12f * density, diameterPx * imageToBodyRatio * 0.5f)
        }
    }

    /** Hit radius when the texture could not be loaded and a plain disc stands in for it. */
    fun fallbackHitRadiusPx(density: Float): Float = maxOf(10f * density, diameterPx * 0.5f)

    companion object {
        const val MIN_TEXTURE_DIAMETER_PX = 15f
        const val MEAN_MOON_DIAMETER_DEG = 0.518f
        const val SATURN_IMAGE_RATIO = 2.33f

        /** The characteristic glow colour of a body, 0xRRGGBB; unknown bodies glow white. */
        fun glowColorRgb(id: String): Int = when {
            id.equals("Sun", ignoreCase = true) -> 0xFFF5D7
            id.equals("Moon", ignoreCase = true) -> 0xE6E8EA
            id.equals("Mercury", ignoreCase = true) -> 0xB0AEAD
            id.equals("Venus", ignoreCase = true) -> 0xFFF8E7
            id.equals("Mars", ignoreCase = true) -> 0xFF6B36
            id.equals("Jupiter", ignoreCase = true) -> 0xE0C4A8
            id.equals("Saturn", ignoreCase = true) -> 0xF4D596
            id.equals("Uranus", ignoreCase = true) -> 0xA8E5F0
            id.equals("Neptune", ignoreCase = true) -> 0x4B70DD
            else -> 0xFFFFFF
        }
    }
}

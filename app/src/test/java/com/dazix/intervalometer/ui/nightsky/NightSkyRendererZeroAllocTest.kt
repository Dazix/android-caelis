package com.dazix.intervalometer.ui.nightsky

import org.junit.Assert.assertNotNull
import org.junit.Test

class NightSkyRendererZeroAllocTest {

    @Test
    fun `verifies renderer pre-allocated paints and red vision theme`() {
        val renderer = NightSkyRenderer()

        assertNotNull(renderer.tier1Paint)
        assertNotNull(renderer.tier2Paint)
        assertNotNull(renderer.tier3Paint)
        assertNotNull(renderer.constellationPaint)
        assertNotNull(renderer.constellationLabelPaint)
        assertNotNull(renderer.skyDomeRenderer)

        // Apply Red Vision mode
        renderer.applyTheme(redVisionMode = true)

        // Apply Normal mode
        renderer.applyTheme(redVisionMode = false)

        // Update stroke widths
        renderer.updateStrokeWidths(tier1Px = 7.5f, tier2Px = 5.0f, tier3Px = 3.0f, constellationPx = 1.8f)
    }
}

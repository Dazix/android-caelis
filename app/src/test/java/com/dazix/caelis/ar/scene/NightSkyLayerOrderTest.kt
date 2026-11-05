package com.dazix.caelis.ar.scene

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Night Sky's peak names are drawn under the constellation figures: on top of them, a name on the
 * ridge competed with a figure standing there. Filament orders by priority first and, within one
 * priority, transparent renderables by blend order.
 */
class NightSkyLayerOrderTest {

    @Test
    fun `peak names are drawn before the constellation lines`() {
        assertTrue(NightSkyScene.PEAK_LABEL_PRIORITY < SkyPriority.CELESTIAL_OVERLAY)
    }

    @Test
    fun `peak names are blended before the artwork when they share its priority`() {
        assertTrue(NightSkyScene.PEAK_LABEL_PRIORITY <= SkyPriority.CELESTIAL)
        assertTrue(NightSkyScene.PEAK_LABEL_BLEND_ORDER < NightSkyScene.ART_BLEND_ORDER)
    }

    @Test
    fun `peak names are drawn after the sky background`() {
        assertTrue(NightSkyScene.PEAK_LABEL_PRIORITY > SkyPriority.ATMOSPHERE)
    }
}

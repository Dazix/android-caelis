package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSkyCameraPolicyTest {

    @Test
    fun `the default sky with the Milky Way on needs no camera`() {
        assertFalse(NightSkyCameraPolicy.isPictureVisible(planetariumMode = false, milkyWayVisible = true))
    }

    @Test
    fun `turning the Milky Way off leaves a dark dome, not the camera`() {
        assertFalse(NightSkyCameraPolicy.isPictureVisible(planetariumMode = false, milkyWayVisible = false))
    }

    @Test
    fun `planetarium mode shows the camera`() {
        assertTrue(NightSkyCameraPolicy.isPictureVisible(planetariumMode = true, milkyWayVisible = false))
    }

    @Test
    fun `the Milky Way turned back on in planetarium mode covers the picture, so the camera stays off`() {
        assertFalse(NightSkyCameraPolicy.isPictureVisible(planetariumMode = true, milkyWayVisible = true))
    }

    @Test
    fun `a virtual place keeps the camera off even in planetarium mode`() {
        assertFalse(
            NightSkyCameraPolicy.isPictureVisible(planetariumMode = true, milkyWayVisible = false, virtualPlace = true),
        )
    }
}

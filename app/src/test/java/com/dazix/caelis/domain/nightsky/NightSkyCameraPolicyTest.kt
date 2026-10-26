package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSkyCameraPolicyTest {

    @Test
    fun `the default sky with the Milky Way on needs no camera`() {
        assertFalse(NightSkyCameraPolicy.isPictureVisible(opaqueBackground = false, milkyWayVisible = true))
    }

    @Test
    fun `the planetarium needs no camera with or without the Milky Way`() {
        assertFalse(NightSkyCameraPolicy.isPictureVisible(opaqueBackground = true, milkyWayVisible = true))
        assertFalse(NightSkyCameraPolicy.isPictureVisible(opaqueBackground = true, milkyWayVisible = false))
    }

    @Test
    fun `the camera runs only when nothing opaque covers its picture`() {
        assertTrue(NightSkyCameraPolicy.isPictureVisible(opaqueBackground = false, milkyWayVisible = false))
    }
}

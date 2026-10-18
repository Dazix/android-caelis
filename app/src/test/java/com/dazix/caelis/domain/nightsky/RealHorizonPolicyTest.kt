package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealHorizonPolicyTest {

    @Test
    fun `wanted while the profile is on its way`() {
        assertTrue(RealHorizonPolicy.isWanted(true, true, hasProfile = false, profileUnavailable = false))
    }

    @Test
    fun `wanted with a profile even if a later refresh failed`() {
        assertTrue(RealHorizonPolicy.isWanted(true, true, hasProfile = true, profileUnavailable = true))
    }

    @Test
    fun `not wanted once no profile is coming`() {
        assertFalse(RealHorizonPolicy.isWanted(true, true, hasProfile = false, profileUnavailable = true))
    }

    @Test
    fun `not wanted when either switch is off`() {
        assertFalse(RealHorizonPolicy.isWanted(false, true, hasProfile = true, profileUnavailable = false))
        assertFalse(RealHorizonPolicy.isWanted(true, false, hasProfile = true, profileUnavailable = false))
    }

    @Test
    fun `stylised landscape stands in when the real skyline is not coming`() {
        assertTrue(
            RealHorizonPolicy.showsStylisedLandscape(
                landscapeSwitch = false,
                featureEnabled = true,
                realHorizonInNightSky = true,
                hasProfile = false,
                profileUnavailable = true,
            ),
        )
    }

    @Test
    fun `no stylised landscape while the real skyline is drawn or on its way`() {
        assertFalse(RealHorizonPolicy.showsStylisedLandscape(false, true, true, hasProfile = true, profileUnavailable = false))
        assertFalse(RealHorizonPolicy.showsStylisedLandscape(false, true, true, hasProfile = false, profileUnavailable = false))
        assertFalse(RealHorizonPolicy.showsStylisedLandscape(false, true, true, hasProfile = true, profileUnavailable = true))
    }

    @Test
    fun `stylised landscape follows its own switch when the real skyline is not asked for`() {
        assertTrue(RealHorizonPolicy.showsStylisedLandscape(true, false, false, hasProfile = false, profileUnavailable = false))
        assertFalse(RealHorizonPolicy.showsStylisedLandscape(false, false, true, hasProfile = false, profileUnavailable = true))
        assertFalse(RealHorizonPolicy.showsStylisedLandscape(false, true, false, hasProfile = false, profileUnavailable = true))
    }
}

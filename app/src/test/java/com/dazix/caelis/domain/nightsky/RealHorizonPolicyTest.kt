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
}

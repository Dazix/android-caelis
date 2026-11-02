package com.dazix.caelis.ui.nightsky

import com.dazix.caelis.ar.DeviceOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeDegreesPolicyTest {

    private fun orientation(az: Float, el: Float) = DeviceOrientation(
        rotationMatrix = FloatArray(16),
        cameraAzimuthDegrees = az,
        cameraElevationDegrees = el,
        cameraRollDegrees = 0f,
        accuracy = 3,
    )

    @Test
    fun `sensor noise within a degree does not change what the HUD prints`() {
        assertTrue(WholeDegreesPolicy.equivalent(orientation(172.1f, 26.2f), orientation(172.9f, 26.8f)))
    }

    @Test
    fun `a new whole degree of azimuth or elevation does`() {
        assertFalse(WholeDegreesPolicy.equivalent(orientation(172.9f, 26.2f), orientation(173.0f, 26.2f)))
        assertFalse(WholeDegreesPolicy.equivalent(orientation(172.5f, 26.9f), orientation(172.5f, 27.0f)))
    }

    @Test
    fun `losing or gaining an orientation is a change`() {
        assertFalse(WholeDegreesPolicy.equivalent(null, orientation(0f, 0f)))
        assertFalse(WholeDegreesPolicy.equivalent(orientation(0f, 0f), null))
        assertTrue(WholeDegreesPolicy.equivalent(null, null))
    }
}

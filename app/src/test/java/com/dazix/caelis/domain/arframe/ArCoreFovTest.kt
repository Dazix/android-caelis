package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreFovTest {

    @Test
    fun `projection of 1 is a 90 degree field of view`() {
        assertEquals(90f, ArCoreFov.horizontalDegrees(1f), 1e-3f)
    }

    @Test
    fun `a longer lens narrows the field of view`() {
        // 2 fx / width = 2 means tan(fov / 2) = 0.5, i.e. about 53.13 degrees.
        assertEquals(53.1301f, ArCoreFov.horizontalDegrees(2f), 1e-3f)
    }

    @Test
    fun `round trips through the projection entry`() {
        for (fov in floatArrayOf(25f, 32f, 56f, 67f, 100f)) {
            assertEquals(fov, ArCoreFov.horizontalDegrees(ArCoreFov.projection00For(fov)), 1e-3f)
        }
    }

    @Test
    fun `a value that cannot be a camera is not a field of view`() {
        assertEquals(0f, ArCoreFov.horizontalDegrees(0f), 0f)
        assertEquals(0f, ArCoreFov.horizontalDegrees(-1f), 0f)
        assertEquals(0f, ArCoreFov.horizontalDegrees(Float.NaN), 0f)
        assertEquals(0f, ArCoreFov.horizontalDegrees(Float.POSITIVE_INFINITY), 0f)
    }
}

package com.dazix.caelis.ui.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonViewModelTest {

    @Test
    fun `initial state has zero offsets and is not locked`() {
        val viewModel = HorizonViewModel()
        val state = viewModel.offsetState.value

        assertEquals(0f, state.azimuthOffset, 1e-4f)
        assertEquals(0f, state.elevationOffset, 1e-4f)
        assertFalse(state.isLocked)
        assertEquals(Float.MAX_VALUE, state.residualDegrees, 1e-4f)
    }

    @Test
    fun `simulateAlignment with residual under 0_5 degrees locks state`() {
        val viewModel = HorizonViewModel()
        viewModel.simulateAlignment(
            azimuthOffset = 2.45f,
            elevationOffset = -0.85f,
            residualDegrees = 0.22f,
        )

        val state = viewModel.offsetState.value
        assertEquals(2.45f, state.azimuthOffset, 1e-4f)
        assertEquals(-0.85f, state.elevationOffset, 1e-4f)
        assertEquals(0.22f, state.residualDegrees, 1e-4f)
        assertTrue("Expected isLocked to be true for residual <= 0.5°", state.isLocked)
    }

    @Test
    fun `simulateAlignment with residual over 0_5 degrees does not lock`() {
        val viewModel = HorizonViewModel()
        viewModel.simulateAlignment(
            azimuthOffset = 5.10f,
            elevationOffset = 1.20f,
            residualDegrees = 0.85f,
        )

        val state = viewModel.offsetState.value
        assertEquals(5.10f, state.azimuthOffset, 1e-4f)
        assertEquals(1.20f, state.elevationOffset, 1e-4f)
        assertEquals(0.85f, state.residualDegrees, 1e-4f)
        assertFalse("Expected isLocked to be false for residual > 0.5°", state.isLocked)
    }

    @Test
    fun `resetAlignment resets back to default initial values`() {
        val viewModel = HorizonViewModel()
        viewModel.simulateAlignment(
            azimuthOffset = 3.0f,
            elevationOffset = -1.0f,
            residualDegrees = 0.15f,
        )
        assertTrue(viewModel.offsetState.value.isLocked)

        viewModel.resetAlignment()
        val resetState = viewModel.offsetState.value
        assertEquals(0f, resetState.azimuthOffset, 1e-4f)
        assertEquals(0f, resetState.elevationOffset, 1e-4f)
        assertFalse(resetState.isLocked)
    }
}

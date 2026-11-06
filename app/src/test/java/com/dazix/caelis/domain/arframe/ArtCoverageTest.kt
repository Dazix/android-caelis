package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.ArtCoverage.Companion.MASK_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtCoverageTest {

    // A drawing facing +Y, its top towards +Z: corners TL, TR, BR, BL at ±0.2.
    private val corners = FloatArray(12).also {
        check(SkyQuadMath.orientedCorners(0f, 1f, 0f, 0f, 0f, 1f, 0.2f, 0.2f, it))
    }
    private val coverage = ArtCoverage()
    private val uv = FloatArray(2)

    @Test
    fun `the quad's centre is the middle of the picture`() {
        assertTrue(coverage.uvOnQuad(0f, 1f, 0f, corners, 0, uv))
        assertEquals(0.5f, uv[0], 1e-4f)
        assertEquals(0.5f, uv[1], 1e-4f)
    }

    @Test
    fun `up and left on the sky is the picture's top left`() {
        // Halfway to the top-left corner: u a quarter in from the left, v a quarter down from the top.
        assertTrue(coverage.uvOnQuad(-0.1f, 1f, 0.1f, corners, 0, uv))
        assertEquals(0.25f, uv[0], 1e-3f)
        assertEquals(0.25f, uv[1], 1e-3f)
    }

    @Test
    fun `down and right on the sky is the picture's bottom right, in either triangle`() {
        assertTrue(coverage.uvOnQuad(0.15f, 1f, -0.05f, corners, 0, uv))
        assertEquals(0.875f, uv[0], 1e-3f)
        assertEquals(0.625f, uv[1], 1e-3f)
        assertTrue(coverage.uvOnQuad(0.05f, 1f, -0.15f, corners, 0, uv))
        assertEquals(0.625f, uv[0], 1e-3f)
        assertEquals(0.875f, uv[1], 1e-3f)
    }

    @Test
    fun `a direction beside the drawing or behind the viewer misses it`() {
        assertFalse(coverage.uvOnQuad(0.5f, 1f, 0f, corners, 0, uv))
        assertFalse(coverage.uvOnQuad(0f, -1f, 0f, corners, 0, uv))
    }

    @Test
    fun `corners are read from the given offset`() {
        val shifted = FloatArray(24).also { corners.copyInto(it, 12) }
        assertTrue(coverage.uvOnQuad(0f, 1f, 0f, shifted, 12, uv))
        assertEquals(0.5f, uv[0], 1e-4f)
    }

    @Test
    fun `the mask is read row by row from the picture's top`() {
        val mask = BooleanArray(MASK_SIZE * MASK_SIZE)
        mask[1 * MASK_SIZE + 2] = true // row 1, column 2
        assertTrue(ArtCoverage.covers(mask, 2.5f / MASK_SIZE, 1.5f / MASK_SIZE))
        assertFalse(ArtCoverage.covers(mask, 1.5f / MASK_SIZE, 2.5f / MASK_SIZE))
        assertFalse(ArtCoverage.covers(mask, 1.2f, 0.5f))
    }
}

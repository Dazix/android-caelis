package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.SkyProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ConstellationFocusStateTest {

    private val w = 1000f
    private val h = 2000f
    private val fov = 60f

    /** Celestial == ENU, so a centroid is placed directly by azimuth / elevation. */
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private val projector = SkyProjector().also {
        val uprightNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)
        val model = FloatArray(16)
        SkyFrameMath.cameraModelMatrix(uprightNorth, 0, model)
        it.update(model, w, h, fov)
    }

    private val focalPx = (w / 2) / tan(Math.toRadians(fov / 2.0)).toFloat()

    /** Centroid on the horizon, [px] pixels right of the screen centre. */
    private fun centroidAt(px: Float): FloatArray {
        val az = atan(px / focalPx).toDouble()
        return floatArrayOf(sin(az).toFloat(), cos(az).toFloat(), 0f)
    }

    private val behind = floatArrayOf(0f, -1f, 0f)

    private fun ConstellationFocusState.step(centroid: FloatArray, selected: Int = -1, dt: Float = 0.016f) =
        update(1, centroid, identity, projector, selected, dt)

    @Test
    fun `hysteresis ring absorbs jitter at the focus boundary`() {
        // Radii for m = 0.48: in = 450 px, out = 550 px.
        val s = ConstellationFocusState(1)
        s.step(centroidAt(500f))
        assertFalse("entering the ring from outside does not lock on", s.focused[0])
        s.step(centroidAt(400f))
        assertTrue("inside the inner radius locks on", s.focused[0])
        s.step(centroidAt(500f))
        assertTrue("inside the ring after locking stays on", s.focused[0])
        s.step(centroidAt(549f))
        assertTrue(s.focused[0])
        s.step(centroidAt(560f))
        assertFalse("beyond the outer radius lets go", s.focused[0])
    }

    @Test
    fun `projected centroid is published for the label overlay`() {
        val s = ConstellationFocusState(1)
        s.step(centroidAt(123f))
        assertTrue(s.centroidInFront[0])
        assertEquals(w / 2 + 123f, s.centroidScreenX[0], 0.05f)
        assertEquals(h / 2, s.centroidScreenY[0], 0.05f)
    }

    @Test
    fun `progress grows at two per second and dissolves one and a half times faster`() {
        val s = ConstellationFocusState(1)
        s.step(centroidAt(0f), dt = 0.1f)
        assertEquals(0.2f, s.progress[0], 1e-6f)
        s.step(centroidAt(0f), dt = 0.1f)
        assertEquals(0.4f, s.progress[0], 1e-6f)
        s.step(centroidAt(900f), dt = 0.1f)
        assertEquals(0.1f, s.progress[0], 1e-6f)
        s.step(centroidAt(900f), dt = 0.1f)
        assertEquals(0f, s.progress[0], 0f)
    }

    @Test
    fun `a stalled frame cannot jump a figure to fully drawn`() {
        val s = ConstellationFocusState(1)
        s.step(centroidAt(0f), dt = 5f)
        assertEquals(0.2f, s.progress[0], 1e-6f)
        s.step(centroidAt(0f), dt = 0f)
        assertEquals(0.202f, s.progress[0], 1e-6f)
    }

    @Test
    fun `a constellation behind the observer is not focused unless selected`() {
        val s = ConstellationFocusState(1)
        s.step(centroidAt(0f))
        assertTrue(s.focused[0])
        s.step(behind)
        assertFalse(s.centroidInFront[0])
        assertFalse(s.focused[0])
        s.step(behind, selected = 0)
        assertTrue("the selected constellation is always focused", s.focused[0])
    }

    @Test
    fun `the selected constellation stays focused at the screen edge`() {
        val s = ConstellationFocusState(1)
        s.step(centroidAt(495f), selected = 0)
        assertTrue(s.focused[0])
    }

    @Test
    fun `reset clears progress and focus`() {
        val s = ConstellationFocusState(2)
        s.update(2, centroidAt(0f) + centroidAt(0f), identity, projector, -1, 0.1f)
        s.reset()
        assertEquals(0f, s.progress[0], 0f)
        assertFalse(s.focused[1])
    }
}

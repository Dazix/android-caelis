package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.PoseArbiter.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseArbiterTest {

    private val ms = 1_000_000L

    private fun PoseArbiter.all(now: Long, zoom: Float = 1f) =
        update(now, consented = true, arCoreTracking = true, headingConverged = true, zoomRatio = zoom)

    @Test
    fun `starts on the sensor tracker`() {
        assertEquals(Mode.SENSOR, PoseArbiter().mode)
    }

    @Test
    fun `hands to ARCore only after every condition held for the enter delay`() {
        val a = PoseArbiter(enterDelayNanos = 500 * ms)
        assertEquals(Mode.SENSOR, a.all(0))
        assertEquals(Mode.SENSOR, a.all(499 * ms))
        assertEquals(Mode.ARCORE, a.all(500 * ms))
    }

    @Test
    fun `each missing condition keeps or returns the sensor tracker immediately`() {
        val conditions = listOf<(PoseArbiter, Long) -> Mode>(
            { a, t -> a.update(t, consented = false, arCoreTracking = true, headingConverged = true, zoomRatio = 1f) },
            { a, t -> a.update(t, consented = true, arCoreTracking = false, headingConverged = true, zoomRatio = 1f) },
            { a, t -> a.update(t, consented = true, arCoreTracking = true, headingConverged = false, zoomRatio = 1f) },
            { a, t -> a.update(t, consented = true, arCoreTracking = true, headingConverged = true, zoomRatio = 2f) },
        )
        for ((i, missing) in conditions.withIndex()) {
            val a = PoseArbiter(enterDelayNanos = 500 * ms)
            a.all(0)
            assertEquals("condition $i precondition", Mode.ARCORE, a.all(600 * ms))
            assertEquals("condition $i drops", Mode.SENSOR, missing(a, 601 * ms))
        }
    }

    @Test
    fun `a flickering tracking state restarts the enter delay`() {
        val a = PoseArbiter(enterDelayNanos = 500 * ms)
        a.all(0)
        a.update(400 * ms, consented = true, arCoreTracking = false, headingConverged = true, zoomRatio = 1f)
        assertEquals(Mode.SENSOR, a.all(600 * ms))
        assertEquals(Mode.SENSOR, a.all(1099 * ms))
        assertEquals(Mode.ARCORE, a.all(1100 * ms))
    }

    @Test
    fun `any zoom other than 1x belongs to the camera that can zoom`() {
        val a = PoseArbiter(enterDelayNanos = 0)
        assertEquals(Mode.ARCORE, a.all(0, zoom = 1f))
        assertEquals(Mode.SENSOR, a.all(1, zoom = 1.01f))
        // Below 1x is the ultrawide lens, which only CameraX can select.
        assertEquals(Mode.SENSOR, a.all(2, zoom = 0.7f))
    }
}

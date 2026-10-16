package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.CameraOwnerPolicy.Owner
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOwnerPolicyTest {

    private val s = 1_000_000_000L

    private fun CameraOwnerPolicy.at(
        t: Long,
        consented: Boolean = true,
        permission: Boolean = true,
        zoom: Float = 1f,
        usable: Boolean = true,
    ) = update(t, consented, permission, zoom, usable)

    @Test
    fun `starts on CameraX and stays there without consent`() {
        val p = CameraOwnerPolicy()
        assertEquals(Owner.CAMERAX, p.owner)
        for (t in 0..10) assertEquals(Owner.CAMERAX, p.at(t * s, consented = false))
    }

    @Test
    fun `the first entry into ARCore has no wait`() {
        val p = CameraOwnerPolicy(reenterDelayNanos = 2 * s)
        assertEquals(Owner.ARCORE, p.at(0))
    }

    @Test
    fun `each missing condition is CameraX at once`() {
        val missing = listOf<(CameraOwnerPolicy, Long) -> Owner>(
            { p, t -> p.at(t, consented = false) },
            { p, t -> p.at(t, permission = false) },
            { p, t -> p.at(t, zoom = 1.5f) },
            { p, t -> p.at(t, zoom = 0.7f) },
            { p, t -> p.at(t, usable = false) },
        )
        for ((i, drop) in missing.withIndex()) {
            val p = CameraOwnerPolicy()
            assertEquals("precondition $i", Owner.ARCORE, p.at(0))
            assertEquals("condition $i", Owner.CAMERAX, drop(p, s))
        }
    }

    @Test
    fun `coming back after a zoom waits so that pinching around 1x does not rebind the camera`() {
        val p = CameraOwnerPolicy(reenterDelayNanos = 1_500_000_000L)
        p.at(0)
        assertEquals(Owner.CAMERAX, p.at(1 * s, zoom = 2f))
        assertEquals(Owner.CAMERAX, p.at(2 * s))
        assertEquals(Owner.CAMERAX, p.at(2 * s + 1_499_000_000L))
        assertEquals(Owner.ARCORE, p.at(2 * s + 1_500_000_000L))
    }

    @Test
    fun `a zoom that crosses 1x back and forth restarts the wait each time`() {
        val p = CameraOwnerPolicy(reenterDelayNanos = 1 * s)
        p.at(0)
        p.at(1 * s, zoom = 1.2f)
        p.at(2 * s)
        p.at(2 * s + 900_000_000L, zoom = 1.2f)
        assertEquals(Owner.CAMERAX, p.at(3 * s))
        assertEquals(Owner.CAMERAX, p.at(3 * s + 900_000_000L))
        assertEquals(Owner.ARCORE, p.at(4 * s))
    }

    @Test
    fun `a failed start keeps ARCore away for the backoff and then lets it try again`() {
        val p = CameraOwnerPolicy(reenterDelayNanos = 0, failureBackoffNanos = 30 * s)
        assertEquals(Owner.ARCORE, p.at(0))
        p.noteStartFailed(1 * s)
        assertEquals(Owner.CAMERAX, p.owner)
        assertEquals(Owner.CAMERAX, p.at(2 * s))
        assertEquals(Owner.CAMERAX, p.at(30 * s))
        assertEquals(Owner.ARCORE, p.at(31 * s))
    }

    @Test
    fun `switching consent off and on is a request for a fresh attempt`() {
        val p = CameraOwnerPolicy(reenterDelayNanos = 0, failureBackoffNanos = 60 * s)
        p.at(0)
        p.noteStartFailed(1 * s)
        assertEquals(Owner.CAMERAX, p.at(2 * s))
        p.at(3 * s, consented = false)
        assertEquals(Owner.ARCORE, p.at(4 * s))
    }

    @Test
    fun `a zoom float that is 1x up to its last digit still counts as 1x`() {
        val p = CameraOwnerPolicy()
        assertEquals(Owner.ARCORE, p.at(0, zoom = 1.0004f))
    }
}

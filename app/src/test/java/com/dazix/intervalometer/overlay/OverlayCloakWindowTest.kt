package com.dazix.intervalometer.overlay

import com.dazix.intervalometer.domain.SessionSnapshot
import com.dazix.intervalometer.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloak window against the interval it has to fit inside.
 *
 * The regression this pins down: the window was a fixed 600 + 1500 ms, so a periodic tap running faster
 * than 2.1 s never left it. The panel then held `FLAG_NOT_TOUCHABLE` for the entire session — Pause and
 * Stop did not respond at all, and every touch aimed at them was delivered to the app underneath.
 */
class OverlayCloakWindowTest {

    private fun running(nextShotAtElapsed: Long?, intervalMs: Long) = SessionSnapshot(
        state = SessionState.Running,
        nextShotAtElapsed = nextShotAtElapsed,
        lastIntervalMs = intervalMs,
    )

    @Test
    fun `a camera-paced session keeps the full window`() {
        assertEquals(
            OverlayPolicy.CLOAK_LEAD_MS to OverlayPolicy.CLOAK_TAIL_MS,
            OverlayPolicy.cloakWindowMs(10_000L),
        )
        // Exactly twice the window is the boundary: it still fits inside half the interval.
        assertEquals(
            OverlayPolicy.CLOAK_LEAD_MS to OverlayPolicy.CLOAK_TAIL_MS,
            OverlayPolicy.cloakWindowMs((OverlayPolicy.CLOAK_LEAD_MS + OverlayPolicy.CLOAK_TAIL_MS) * 2),
        )
    }

    @Test
    fun `the window never takes more than half of the interval`() {
        for (interval in listOf(500L, 800L, 1_200L, 2_000L, 3_000L, 4_199L)) {
            val (lead, tail) = OverlayPolicy.cloakWindowMs(interval)
            assertTrue(
                "window ${lead + tail} ms must fit in half of $interval ms",
                lead + tail <= interval / 2,
            )
        }
    }

    @Test
    fun `a fast periodic tap is not cloaked at all`() {
        assertEquals(0L to 0L, OverlayPolicy.cloakWindowMs(300L))
        val snapshot = running(nextShotAtElapsed = 100_000L, intervalMs = 300L)
        // Not at the shot, not just before it, and not even with no shot scheduled — which is the state
        // that used to mean "cloaked forever".
        assertFalse(OverlayPolicy.isCloaked(snapshot, 100_000L, 300L))
        assertFalse(OverlayPolicy.isCloaked(snapshot, 99_900L, 300L))
        assertFalse(OverlayPolicy.isCloaked(running(null, 300L), 100_000L, 300L))
        assertNull(OverlayPolicy.nextCloakChangeDelayMs(snapshot, 99_000L, 300L))
    }

    @Test
    fun `a session with a scaled window is touchable for at least half of every interval`() {
        val interval = 3_000L
        val next = 100_000L
        val snapshot = running(next, interval)
        val touchable = (next - interval..next).count {
            !OverlayPolicy.isCloaked(snapshot, it, interval)
        }
        assertTrue(
            "only $touchable of $interval ms were touchable",
            touchable >= interval / 2,
        )
    }

    @Test
    fun `a cadence that has never been reported gets the full window`() {
        // Only before anything has been dispatched. Erring towards cloaked is right there, because an
        // unknown cadence could be a fast one and a swallowed shutter tap is the worse fault.
        assertEquals(
            OverlayPolicy.CLOAK_LEAD_MS to OverlayPolicy.CLOAK_TAIL_MS,
            OverlayPolicy.cloakWindowMs(0L),
        )
        assertTrue(OverlayPolicy.isCloaked(running(100_000L, 0L), 100_000L, 0L))
    }

    @Test
    fun `a zero reported mid-run keeps the cadence the session was actually running at`() {
        // The regression this closes: `achievedIntervalMs` reports 0 whenever the next target is at or
        // before the last tap — a shot that slipped by more than one interval — and reading that as
        // "unknown" handed a 300 ms periodic tap the full 2.1 s window, i.e. a permanently untouchable
        // panel, for exactly the reason the window was scaled in the first place.
        val cadence = OverlayPolicy.Cadence()
        assertEquals(300L, cadence.observe(running(100_000L, 300L)))
        assertEquals(300L, cadence.observe(running(100_300L, 0L)))
        assertEquals(0L to 0L, OverlayPolicy.cloakWindowMs(cadence.lastKnownMs))
        assertFalse(
            OverlayPolicy.isCloaked(running(100_300L, 0L), 100_300L, cadence.lastKnownMs),
        )

        // And a session that speeds up is followed rather than remembered forever.
        assertEquals(8_000L, cadence.observe(running(108_000L, 8_000L)))
        cadence.reset()
        assertEquals(0L, cadence.lastKnownMs)
    }
}

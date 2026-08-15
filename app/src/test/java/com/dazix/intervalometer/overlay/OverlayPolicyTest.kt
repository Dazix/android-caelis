package com.dazix.intervalometer.overlay

import com.dazix.intervalometer.domain.SessionSnapshot
import com.dazix.intervalometer.domain.SessionState
import com.dazix.intervalometer.domain.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPolicyTest {

    private fun running(nextShotAt: Long?) = SessionSnapshot(
        state = SessionState.Running,
        nextShotAtElapsed = nextShotAt,
    )

    /**
     * A camera-paced cadence, where the full cloak window fits inside half the interval. The window's
     * behaviour *at other cadences* is [OverlayCloakWindowTest]'s subject, not this file's.
     */
    private val cadenceMs = 30_000L

    // ---- keep screen on -------------------------------------------------------------------------

    @Test
    fun `screen is kept on while running`() {
        assertTrue(OverlayPolicy.keepScreenOn(SessionState.Running, optionEnabled = true))
    }

    @Test
    fun `screen is released while waiting to start and while paused`() {
        assertFalse(
            OverlayPolicy.keepScreenOn(SessionState.WaitingToStart(10_000L), optionEnabled = true)
        )
        assertFalse(OverlayPolicy.keepScreenOn(SessionState.Paused, optionEnabled = true))
    }

    @Test
    fun `screen is released once the session is over`() {
        assertFalse(OverlayPolicy.keepScreenOn(SessionState.Idle, optionEnabled = true))
        assertFalse(
            OverlayPolicy.keepScreenOn(SessionState.Stopped(StopReason.COMPLETED), optionEnabled = true)
        )
    }

    @Test
    fun `the user option wins over everything`() {
        assertFalse(OverlayPolicy.keepScreenOn(SessionState.Running, optionEnabled = false))
    }

    // ---- cloaking -------------------------------------------------------------------------------

    @Test
    fun `panel is touchable well before the next shot`() {
        val now = 100_000L
        assertFalse(OverlayPolicy.isCloaked(running(now + 10_000L), now, cadenceMs))
    }

    @Test
    fun `panel goes untouchable before the shot and stays so afterwards`() {
        val next = 100_000L
        val opens = next - OverlayPolicy.CLOAK_LEAD_MS
        val closes = next + OverlayPolicy.CLOAK_TAIL_MS
        assertFalse(OverlayPolicy.isCloaked(running(next), opens - 1, cadenceMs))
        assertTrue(OverlayPolicy.isCloaked(running(next), opens, cadenceMs))
        assertTrue(OverlayPolicy.isCloaked(running(next), next, cadenceMs))
        assertTrue(OverlayPolicy.isCloaked(running(next), closes, cadenceMs))
        assertFalse(OverlayPolicy.isCloaked(running(next), closes + 1, cadenceMs))
    }

    @Test
    fun `a running session with no scheduled shot is treated as shooting right now`() {
        assertTrue(OverlayPolicy.isCloaked(running(null), 100_000L, cadenceMs))
    }

    @Test
    fun `a paused or waiting session never cloaks`() {
        val now = 100_000L
        assertFalse(
            OverlayPolicy.isCloaked(
                SessionSnapshot(state = SessionState.Paused, nextShotAtElapsed = now),
                now,
                cadenceMs,
            )
        )
        assertFalse(
            OverlayPolicy.isCloaked(
                SessionSnapshot(
                    state = SessionState.WaitingToStart(now),
                    nextShotAtElapsed = now,
                ),
                now,
                cadenceMs,
            )
        )
    }

    @Test
    fun `the timer is armed for the moment the cloak opens`() {
        val next = 100_000L
        val now = next - 5_000L
        assertEquals(
            5_000L - OverlayPolicy.CLOAK_LEAD_MS,
            OverlayPolicy.nextCloakChangeDelayMs(running(next), now, cadenceMs),
        )
    }

    @Test
    fun `the timer is armed for the moment the cloak closes`() {
        val next = 100_000L
        val delay = OverlayPolicy.nextCloakChangeDelayMs(running(next), next, cadenceMs)
        assertEquals(OverlayPolicy.CLOAK_TAIL_MS + 1, delay)
    }

    @Test
    fun `no timer is armed once the window has passed or when nothing is scheduled`() {
        val next = 100_000L
        assertNull(
            OverlayPolicy.nextCloakChangeDelayMs(
                running(next),
                next + OverlayPolicy.CLOAK_TAIL_MS + 1,
                cadenceMs,
            )
        )
        assertNull(OverlayPolicy.nextCloakChangeDelayMs(running(null), next, cadenceMs))
        assertNull(
            OverlayPolicy.nextCloakChangeDelayMs(
                SessionSnapshot(state = SessionState.Paused, nextShotAtElapsed = next),
                next,
                cadenceMs,
            )
        )
    }

    @Test
    fun `the panel stands down over our own UI and stays up over everything else`() {
        val self = "com.dazix.intervalometer"
        assertTrue(OverlayPolicy.suppressForOwnUi(self, self))
        assertFalse(OverlayPolicy.suppressForOwnUi("com.google.android.GoogleCamera", self))
        // An unknown foreground keeps the panel up: the session is presumably still driving another
        // app, and hiding the only visible stop button on a guess is the worse failure.
        assertFalse(OverlayPolicy.suppressForOwnUi(null, self))
        assertFalse(OverlayPolicy.suppressForOwnUi("com.dazix.intervalometer.debug", self))
    }

    @Test
    fun `the armed timer always lands inside the state it is scheduled for`() {
        val next = 100_000L
        for (now in listOf(next - 9_000L, next - OverlayPolicy.CLOAK_LEAD_MS, next, next + 1_000L)) {
            val snapshot = running(next)
            val delay = OverlayPolicy.nextCloakChangeDelayMs(snapshot, now, cadenceMs) ?: continue
            assertTrue("delay $delay from $now must be positive", delay > 0L)
            assertTrue(
                "cloak state must actually flip at now+$delay",
                OverlayPolicy.isCloaked(snapshot, now, cadenceMs) !=
                    OverlayPolicy.isCloaked(snapshot, now + delay, cadenceMs),
            )
        }
    }
}

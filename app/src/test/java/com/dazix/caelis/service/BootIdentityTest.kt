package com.dazix.caelis.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored run is anchored to `SystemClock.elapsedRealtime`, which restarts at zero on every boot.
 * Resuming against an anchor from a previous boot would put every shot target in the past and fire the
 * whole remaining series at once, so "did the device reboot?" has to be answered correctly from two
 * saved numbers and two current ones.
 */
class BootIdentityTest {

    @Test
    fun `a process that died and came back is the same boot`() {
        // Saved 30 s into the boot, read 90 s in: an hour of wall clock has not passed, uptime grew.
        assertTrue(
            BootIdentity.sameBoot(
                savedElapsedMs = 30_000L,
                savedWallMs = WALL,
                nowElapsedMs = 90_000L,
                nowWallMs = WALL + 60_000L,
            ),
        )
    }

    @Test
    fun `a reboot is rejected`() {
        // Device was up for four hours, then rebooted: wall clock kept going, uptime restarted.
        assertFalse(
            BootIdentity.sameBoot(
                savedElapsedMs = 4 * 3_600_000L,
                savedWallMs = WALL,
                nowElapsedMs = 20_000L,
                nowWallMs = WALL + 5 * 3_600_000L,
            ),
        )
    }

    /**
     * The pathological reboot: the device came back up and reached the same uptime again, but the wall
     * clock moved on. Uptime alone cannot tell these apart, which is why the wall clock is stored too.
     */
    @Test
    fun `a reboot that lands back on the same uptime is still rejected`() {
        assertFalse(
            BootIdentity.sameBoot(
                savedElapsedMs = 60_000L,
                savedWallMs = WALL,
                nowElapsedMs = 61_000L,
                nowWallMs = WALL + 600_000L,
            ),
        )
    }

    /** Uptime cannot go backwards without a reboot, whatever the wall clock says. */
    @Test
    fun `uptime running backwards is a reboot even when the wall clock agrees`() {
        assertFalse(
            BootIdentity.sameBoot(
                savedElapsedMs = 500_000L,
                savedWallMs = WALL,
                nowElapsedMs = 10_000L,
                nowWallMs = WALL - 490_000L,
            ),
        )
    }

    /** An NTP correction of a few seconds must not throw a live session away. */
    @Test
    fun `a small clock correction is tolerated`() {
        assertTrue(
            BootIdentity.sameBoot(
                savedElapsedMs = 100_000L,
                savedWallMs = WALL,
                nowElapsedMs = 160_000L,
                nowWallMs = WALL + 60_000L + 4_000L,
            ),
        )
        assertFalse(
            "a jump past the tolerance is treated as a reboot, which only costs a resume",
            BootIdentity.sameBoot(
                savedElapsedMs = 100_000L,
                savedWallMs = WALL,
                nowElapsedMs = 160_000L,
                nowWallMs = WALL + 60_000L + BootIdentity.TOLERANCE_MS + 1L,
            ),
        )
    }

    /** Nothing was ever stamped: there is no run to resume, so it must not claim there is. */
    @Test
    fun `an unstamped record is never resumable`() {
        assertFalse(BootIdentity.sameBoot(0L, 0L, 90_000L, WALL))
        assertFalse(BootIdentity.sameBoot(30_000L, 0L, 90_000L, WALL))
        assertFalse(BootIdentity.sameBoot(0L, WALL, 90_000L, WALL))
    }

    private companion object {
        /** An arbitrary but realistic wall-clock instant. */
        const val WALL = 1_760_000_000_000L
    }
}

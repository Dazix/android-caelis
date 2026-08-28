package com.dazix.caelis.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accounting of a verified shot between its tap and its count, which is where a photo that exists
 * went uncounted and where a retry could have counted one twice.
 *
 * Each test walks the same calls the engine makes, in the order it makes them:
 * `fireShutter` -> [PendingShot.onTapDispatched], `onShutterFired` -> [PendingShot.onTapFailed],
 * `commitPendingShot` -> [PendingShot.commit].
 */
class PendingShotTest {

    private val pending = PendingShot()

    @Test
    fun `nothing is pending before a tap`() {
        assertFalse(pending.isOpen)
        assertNull(pending.commit())
    }

    @Test
    fun `a verified shot is counted at the instant its tap was dispatched`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 10_000L)

        assertTrue(pending.isOpen)
        // Not "whenever MediaStore answered": a ramp anchors its first shot on this instant.
        assertEquals(10_000L, pending.commit())
    }

    /**
     * The defect this type exists for. A gesture result that never arrives leaves the watchdog to run
     * `afterCapture`, verification then confirms the frame in MediaStore — and with the pending shot
     * opened in the result callback there was nothing to commit, so a photo that exists was never
     * counted. Opening it at the dispatch is what makes the sequence below count it.
     */
    @Test
    fun `a shot whose gesture result was lost is still counted when its frame is confirmed`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 4_000L)
        // No onTapFailed and no successful callback at all: the watchdog moved the session on.

        assertEquals(4_000L, pending.commit())
    }

    @Test
    fun `a shot is never counted twice, however many resolution paths run`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 7_000L)

        // finishShot commits it; a stop() or pause() arriving afterwards commits again.
        assertEquals(7_000L, pending.commit())
        assertNull(pending.commit())
        assertFalse(pending.isOpen)
    }

    @Test
    fun `a retry re-taps the same shot and never opens a second one`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 21_000L)

        // Still the first tap's instant, and still only one shot to count.
        assertEquals(1_000L, pending.commit())
        assertNull(pending.commit())
    }

    @Test
    fun `a retry cannot open a shot of its own even when the first tap was already counted`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        assertEquals(1_000L, pending.commit())

        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 21_000L)

        assertFalse(pending.isOpen)
        assertNull(pending.commit())
    }

    @Test
    fun `a tap that never reached the app is dropped instead of counted`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 2_000L)
        pending.onTapFailed(isRetry = false)

        assertFalse(pending.isOpen)
        assertNull(pending.commit())
    }

    /**
     * The retry is the second tap of a shot whose first tap did land. Its own failure says nothing
     * about that frame, so giving up on the verification must still count the shot — dropping it here
     * would lose a photo that is on the card.
     */
    @Test
    fun `a failed retry leaves the first tap's shot countable`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 3_000L)
        pending.onTapFailed(isRetry = true)

        assertTrue(pending.isOpen)
        assertEquals(3_000L, pending.commit())
    }

    /**
     * With nothing being verified the engine counts the tap in `onShutterFired` itself. Opening a
     * pending shot as well would let [PendingShot.commit] count the same tap a second time from
     * `finishShot`.
     */
    @Test
    fun `an unverified shot leaves nothing pending, so it cannot be counted twice`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = false, firedElapsed = 5_000L)

        assertFalse(pending.isOpen)
        assertNull(pending.commit())
    }

    @Test
    fun `a session being started or rebuilt forgets a tap without counting it`() {
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 6_000L)
        pending.clear()

        assertFalse(pending.isOpen)
        assertNull(pending.commit())
    }

    /**
     * Two shots in a row: each opens its own pending tap and each is counted exactly once, with the
     * second never inheriting the first one's instant.
     */
    @Test
    fun `consecutive verified shots are counted once each`() {
        val counted = ArrayList<Long>()

        for (firedAt in listOf(1_000L, 11_000L)) {
            pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = firedAt)
            pending.commit()?.let { counted += it }
            // finishShot commits, then scheduleShot runs: a second commit must find nothing.
            pending.commit()?.let { counted += it }
        }

        assertEquals(listOf(1_000L, 11_000L), counted)
    }

    @Test
    fun `a retry re-opens the shot the first tap dropped by failing`() {
        // The engine still runs the verification window after a refused first tap and still fires its
        // one retry. If that retry's frame lands, the shot exists and has to be counted — before this,
        // there was nothing left to commit and a maxShots session shot one frame too many.
        val pending = PendingShot()
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        pending.onTapFailed(isRetry = false)
        assertFalse(pending.isOpen)

        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 13_000L)
        assertTrue(pending.isOpen)
        assertEquals(13_000L, pending.commit())
        assertNull("the shot may only be committed once", pending.commit())
    }

    @Test
    fun `a retry after a committed shot opens nothing`() {
        val pending = PendingShot()
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        assertEquals(1_000L, pending.commit())

        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 13_000L)
        assertFalse("a counted shot must not be re-opened by a retry", pending.isOpen)
        assertNull(pending.commit())
    }

    @Test
    fun `a shot whose every tap was refused is dropped, not counted`() {
        // Both gestures refused means nothing ever reached the app, so there is no photo. Counting one
        // here is exactly the phantom shot verification is turned on to catch: on a camera whose
        // shutter cannot be found, every cycle would otherwise add one to "shots taken" with an empty
        // photo library behind it.
        val pending = PendingShot()
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        pending.onTapFailed(isRetry = false)
        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 13_000L)
        pending.onTapFailed(isRetry = true)
        assertFalse("no gesture of this shot was ever delivered", pending.isOpen)
        assertNull(pending.commit())
    }

    @Test
    fun `a failed retry over a landed first tap leaves that shot alone`() {
        // The first tap did reach the app, so its frame may well be in MediaStore. Giving up on
        // verifying it still counts the shot — at the instant the *first* tap was dispatched.
        val pending = PendingShot()
        pending.onTapDispatched(isRetry = false, verifyArmed = true, firedElapsed = 1_000L)
        pending.onTapDispatched(isRetry = true, verifyArmed = true, firedElapsed = 13_000L)
        pending.onTapFailed(isRetry = true)
        assertTrue("a landed first tap keeps its shot", pending.isOpen)
        assertEquals(1_000L, pending.commit())
    }
}

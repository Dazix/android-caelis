package com.dazix.caelis.domain.stability

import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TriggerMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldPolicyTest {

    private val timelapse = TriggerMode.Timelapse("com.example.cam")
    private val periodic = TriggerMode.PeriodicTap("com.example.cam")

    @Test
    fun `the session watches while counting down and while shooting a time-lapse`() {
        assertTrue(HandheldPolicy.watchDuringSession(SessionState.WaitingToStart(1L), timelapse, true))
        assertTrue(HandheldPolicy.watchDuringSession(SessionState.Running, timelapse, true))
    }

    @Test
    fun `a paused, stopped or idle session does not watch`() {
        assertFalse(HandheldPolicy.watchDuringSession(SessionState.Paused, timelapse, true))
        assertFalse(HandheldPolicy.watchDuringSession(SessionState.Idle, timelapse, true))
        assertFalse(
            HandheldPolicy.watchDuringSession(SessionState.Stopped(StopReason.USER), timelapse, true),
        )
    }

    @Test
    fun `a periodic tap, a missing mode or the option off never watch`() {
        assertFalse(HandheldPolicy.watchDuringSession(SessionState.Running, periodic, true))
        assertFalse(HandheldPolicy.watchDuringSession(SessionState.Running, null, true))
        assertFalse(HandheldPolicy.watchDuringSession(SessionState.Running, timelapse, false))
    }

    @Test
    fun `the home screen watches only before a time-lapse starts`() {
        assertTrue(HandheldPolicy.watchBeforeStart(sessionActive = false, timelapse = true, enabled = true))
        assertFalse(HandheldPolicy.watchBeforeStart(sessionActive = true, timelapse = true, enabled = true))
        assertFalse(HandheldPolicy.watchBeforeStart(sessionActive = false, timelapse = false, enabled = true))
        assertFalse(HandheldPolicy.watchBeforeStart(sessionActive = false, timelapse = true, enabled = false))
    }

    @Test
    fun `start asks only on a positive answer`() {
        assertTrue(HandheldPolicy.confirmStart(HandheldState.HANDHELD, timelapse = true, enabled = true))
        assertFalse(HandheldPolicy.confirmStart(HandheldState.STEADY, timelapse = true, enabled = true))
        // No gyroscope or a window not yet full: start, never a question about a guess.
        assertFalse(HandheldPolicy.confirmStart(HandheldState.UNKNOWN, timelapse = true, enabled = true))
        assertFalse(HandheldPolicy.confirmStart(HandheldState.HANDHELD, timelapse = false, enabled = true))
        assertFalse(HandheldPolicy.confirmStart(HandheldState.HANDHELD, timelapse = true, enabled = false))
    }
}

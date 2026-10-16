package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.horizon.AutoAlignPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAlignPolicyTest {

    @Test
    fun `it attempts as soon as everything is ready and the phone is still`() {
        val policy = AutoAlignPolicy()
        assertEquals(Decision.WAIT, policy.decide(0L, ready = false, still = true))
        assertEquals(Decision.WAIT, policy.decide(0L, ready = true, still = false))
        assertEquals(Decision.ATTEMPT, policy.decide(0L, ready = true, still = true))
    }

    @Test
    fun `one attempt at a time`() {
        val policy = AutoAlignPolicy()
        policy.onAttemptStarted()
        assertEquals(Decision.WAIT, policy.decide(10_000L, ready = true, still = true))
    }

    @Test
    fun `a refusal is retried after a growing delay`() {
        val policy = AutoAlignPolicy(firstRetryMs = 3_000L, maxRetryMs = 10_000L)
        policy.onAttemptStarted()
        policy.onAttemptFinished(nowMs = 1_000L, accepted = false)
        assertEquals(Decision.WAIT, policy.decide(3_999L, ready = true, still = true))
        assertEquals(Decision.ATTEMPT, policy.decide(4_000L, ready = true, still = true))

        policy.onAttemptStarted()
        policy.onAttemptFinished(nowMs = 4_000L, accepted = false)
        assertEquals(Decision.WAIT, policy.decide(9_999L, ready = true, still = true))
        assertEquals(Decision.ATTEMPT, policy.decide(10_000L, ready = true, still = true))

        // Capped: a phone left pointing at a wall is asked every ten seconds, not ever more rarely.
        repeat(5) {
            policy.onAttemptStarted()
            policy.onAttemptFinished(nowMs = 100_000L, accepted = false)
        }
        assertEquals(Decision.WAIT, policy.decide(109_999L, ready = true, still = true))
        assertEquals(Decision.ATTEMPT, policy.decide(110_000L, ready = true, still = true))
    }

    @Test
    fun `an accepted fit settles the session`() {
        val policy = AutoAlignPolicy()
        policy.onAttemptStarted()
        policy.onAttemptFinished(nowMs = 0L, accepted = true)
        assertTrue(policy.isSettled)
        assertEquals(Decision.WAIT, policy.decide(1_000_000L, ready = true, still = true))
    }

    /** The user looked and decided; an automatic fit must not undo a hand alignment. */
    @Test
    fun `a manual alignment stops it, also with an attempt in flight`() {
        val policy = AutoAlignPolicy()
        policy.onManualAlignment()
        assertEquals(Decision.WAIT, policy.decide(0L, ready = true, still = true))

        val busy = AutoAlignPolicy()
        busy.onAttemptStarted()
        busy.onManualAlignment()
        busy.onAttemptFinished(nowMs = 0L, accepted = false)
        assertTrue("a refusal after a hand alignment does not reopen it", busy.isSettled)
        assertEquals(Decision.WAIT, busy.decide(100_000L, ready = true, still = true))
    }

    @Test
    fun `a reset starts it again with no backoff`() {
        val policy = AutoAlignPolicy()
        repeat(3) {
            policy.onAttemptStarted()
            policy.onAttemptFinished(nowMs = 50_000L, accepted = false)
        }
        policy.onManualAlignment()
        policy.reset()
        assertFalse(policy.isSettled)
        assertEquals(Decision.ATTEMPT, policy.decide(50_001L, ready = true, still = true))
    }

    @Test
    fun `still only after the axis has held for the whole window`() {
        val detector = StillnessDetector(windowMs = 700L, maxDriftDegrees = 1.0)
        assertFalse(detector.add(0L, 0.0, 1.0, 0.0))
        assertFalse(detector.add(500L, 0.0, 1.0, 0.005))
        assertTrue(detector.add(700L, 0.0, 1.0, 0.01))
    }

    @Test
    fun `a turn restarts the clock`() {
        val detector = StillnessDetector(windowMs = 700L, maxDriftDegrees = 1.0)
        detector.add(0L, 0.0, 1.0, 0.0)
        // About 3° away: the phone moved.
        assertFalse(detector.add(800L, 0.05, 1.0, 0.0))
        assertFalse(detector.add(1_400L, 0.05, 1.0, 0.0))
        assertTrue(detector.add(1_500L, 0.05, 1.0, 0.0))
    }

    @Test
    fun `a zero or NaN axis is never still`() {
        val detector = StillnessDetector()
        assertFalse(detector.add(0L, 0.0, 0.0, 0.0))
        assertFalse(detector.add(10_000L, Double.NaN, 1.0, 0.0))
    }
}

package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.horizon.HeadingRefinePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingRefinePolicyTest {

    private fun HeadingRefinePolicy.ask(nowMs: Long, swings: Int = 0, ready: Boolean = true, still: Boolean = true, held: Boolean = true) =
        decide(nowMs, ready = ready, still = still, swingCount = swings, headingHeld = held)

    /** An accepted fit, then a swing (count 1) at 10 s. */
    private fun afterFitAndSwing(): HeadingRefinePolicy = HeadingRefinePolicy().apply {
        onFitAccepted(nowMs = 0L, swingCount = 0)
        assertEquals(Decision.WAIT, ask(10_000L, swings = 1))
    }

    @Test
    fun `nothing is refined before an alignment`() {
        val policy = HeadingRefinePolicy()
        assertEquals(Decision.WAIT, policy.ask(0L))
        assertEquals(Decision.WAIT, policy.ask(60_000L, swings = 3))
    }

    @Test
    fun `a swing after an accepted fit is refined once the phone has settled`() {
        val policy = afterFitAndSwing()
        assertEquals(Decision.WAIT, policy.ask(10_000L + HeadingRefinePolicy.SETTLE_MS - 1, swings = 1))
        assertEquals(Decision.REFINE, policy.ask(10_000L + HeadingRefinePolicy.SETTLE_MS, swings = 1))
    }

    @Test
    fun `only a still, ready phone with a held heading is refined`() {
        val policy = afterFitAndSwing()
        val t = 20_000L
        assertEquals(Decision.WAIT, policy.ask(t, swings = 1, still = false))
        assertEquals(Decision.WAIT, policy.ask(t, swings = 1, ready = false))
        assertEquals(Decision.WAIT, policy.ask(t, swings = 1, held = false))
        assertEquals(Decision.REFINE, policy.ask(t, swings = 1))
    }

    @Test
    fun `the step is what moved since the fit`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        // The calibration is 4°, the fit now wants 5.2°: the game vector jumped 1.2°.
        val step = policy.onRefine(12_500L, fitAzimuthDegrees = 5.2, calibrationDegrees = 4.0)
        assertEquals(1.2, step!!, 1e-9)
        // Checked: no further attempt until the next swing or the period.
        assertEquals(Decision.WAIT, policy.ask(13_000L, swings = 1))
        assertEquals(Decision.REFINE, policy.ask(12_500L + HeadingRefinePolicy.PERIOD_MS, swings = 1))
    }

    @Test
    fun `a step within the fit's noise moves nothing`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        assertEquals(0.0, policy.onRefine(12_500L, fitAzimuthDegrees = 4.1, calibrationDegrees = 4.0)!!, 0.0)
    }

    @Test
    fun `a step beyond a swing's jump is refused and retried`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        assertNull(policy.onRefine(12_500L, fitAzimuthDegrees = 8.0, calibrationDegrees = 4.0))
        assertEquals(Decision.WAIT, policy.ask(12_500L + HeadingRefinePolicy.RETRY_MS - 1, swings = 1))
        assertEquals(Decision.REFINE, policy.ask(12_500L + HeadingRefinePolicy.RETRY_MS, swings = 1))
    }

    @Test
    fun `a refused periodic check waits for the next period`() {
        val policy = HeadingRefinePolicy()
        policy.onFitAccepted(nowMs = 0L, swingCount = 0)
        val t = HeadingRefinePolicy.PERIOD_MS
        assertEquals(Decision.REFINE, policy.ask(t))
        policy.onAttemptStarted()
        assertNull(policy.onRefine(t, fitAzimuthDegrees = null, calibrationDegrees = 0.0))
        assertEquals(Decision.WAIT, policy.ask(t + HeadingRefinePolicy.RETRY_MS))
        assertEquals(Decision.REFINE, policy.ask(2 * t))
    }

    @Test
    fun `a frame from before a later swing is dropped`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        policy.ask(12_100L, swings = 2)
        assertNull(policy.onRefine(12_300L, fitAzimuthDegrees = 5.0, calibrationDegrees = 4.0))
        assertEquals(Decision.REFINE, policy.ask(12_100L + HeadingRefinePolicy.SETTLE_MS, swings = 2))
    }

    @Test
    fun `a hand alignment is kept, the step is relative to its measured baseline`() {
        val policy = HeadingRefinePolicy()
        policy.onManualAlignment(swingCount = 0)
        assertEquals(Decision.MEASURE_BASELINE, policy.ask(1_000L))
        policy.onAttemptStarted()
        // The hand put the calibration at 3°, the fit would have said 3.4°.
        policy.onBaseline(1_200L, fitAzimuthDegrees = 3.4, calibrationDegrees = 3.0)
        assertTrue(policy.hasBaseline)
        assertEquals(0.4, policy.baselineDegrees, 1e-9)

        assertEquals(Decision.WAIT, policy.ask(5_000L, swings = 1))
        assertEquals(Decision.REFINE, policy.ask(7_000L, swings = 1))
        policy.onAttemptStarted()
        // After the swing the fit says 2.4°: the heading moved -1° and the hand's 3° becomes 2°.
        assertEquals(-1.0, policy.onRefine(7_200L, fitAzimuthDegrees = 2.4, calibrationDegrees = 3.0)!!, 1e-9)
    }

    @Test
    fun `a swing before the baseline leaves nothing to refine until the next alignment`() {
        val policy = HeadingRefinePolicy()
        policy.onManualAlignment(swingCount = 0)
        assertEquals(Decision.WAIT, policy.ask(500L, swings = 1, still = false))
        assertEquals(Decision.WAIT, policy.ask(10_000L, swings = 1))
        assertEquals(Decision.WAIT, policy.ask(60_000L, swings = 1))
        assertFalse(policy.hasBaseline)

        policy.onManualAlignment(swingCount = 1)
        assertEquals(Decision.MEASURE_BASELINE, policy.ask(61_000L, swings = 1))
    }

    @Test
    fun `a baseline measured across a swing is not taken`() {
        val policy = HeadingRefinePolicy()
        policy.onManualAlignment(swingCount = 0)
        assertEquals(Decision.MEASURE_BASELINE, policy.ask(1_000L))
        policy.onAttemptStarted()
        policy.ask(1_100L, swings = 1)
        policy.onBaseline(1_300L, fitAzimuthDegrees = 3.4, calibrationDegrees = 3.0)
        assertFalse(policy.hasBaseline)
    }

    @Test
    fun `a refused baseline is retried`() {
        val policy = HeadingRefinePolicy()
        policy.onManualAlignment(swingCount = 0)
        policy.ask(1_000L)
        policy.onAttemptStarted()
        policy.onBaseline(1_000L, fitAzimuthDegrees = null, calibrationDegrees = 3.0)
        assertEquals(Decision.WAIT, policy.ask(1_000L + HeadingRefinePolicy.RETRY_MS - 1))
        assertEquals(Decision.MEASURE_BASELINE, policy.ask(1_000L + HeadingRefinePolicy.RETRY_MS))
    }

    @Test
    fun `nothing is asked while an attempt is in flight, and an abandoned one is retried`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        assertEquals(Decision.WAIT, policy.ask(13_000L, swings = 1))
        policy.onAttemptAbandoned(13_000L)
        assertEquals(Decision.REFINE, policy.ask(13_000L + HeadingRefinePolicy.RETRY_MS, swings = 1))
    }

    @Test
    fun `a hand alignment drops the answer of an attempt in flight`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        policy.onManualAlignment(swingCount = 1)
        assertNull(policy.onRefine(12_300L, fitAzimuthDegrees = 5.2, calibrationDegrees = 4.0))
    }

    @Test
    fun `a reset ends refining`() {
        val policy = afterFitAndSwing()
        policy.reset()
        assertEquals(Decision.WAIT, policy.ask(60_000L, swings = 1))
    }

    @Test
    fun `the step wraps round north`() {
        val policy = afterFitAndSwing()
        policy.ask(12_000L, swings = 1)
        policy.onAttemptStarted()
        assertEquals(1.0, policy.onRefine(12_500L, fitAzimuthDegrees = -179.5, calibrationDegrees = 179.5)!!, 1e-9)
    }
}

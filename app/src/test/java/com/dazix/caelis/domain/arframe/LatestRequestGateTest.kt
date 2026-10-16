package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestGateTest {

    @Test
    fun `the newest request is current until it is settled`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        assertTrue(gate.isCurrent(a))
        assertTrue(gate.finish(a))
        assertFalse("settled", gate.isCurrent(a))
    }

    @Test
    fun `a request can be settled only once`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        assertTrue(gate.finish(a))
        assertFalse("the result and the timeout must not both apply", gate.finish(a))
    }

    @Test
    fun `a stale timer cannot fail a newer request`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        assertTrue(gate.finish(a)) // A's frame arrived and the alignment finished
        val b = gate.begin() // the user taps again
        assertFalse("A's timer wakes up inside B's window", gate.finish(a))
        assertTrue("B is untouched", gate.isCurrent(b))
        assertTrue(gate.finish(b))
    }

    @Test
    fun `a request that is re-issued while the first is outstanding supersedes it`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        val b = gate.begin()
        assertFalse(gate.isCurrent(a))
        assertFalse("A's frame, arriving late, is ignored", gate.finish(a))
        assertTrue(gate.finish(b))
    }

    @Test
    fun `a frame that arrives after the timeout is not current`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        assertTrue("the timeout settles the request", gate.finish(a))
        assertFalse("so the late frame must not run the aligner", gate.isCurrent(a))
    }

    @Test
    fun `invalidating abandons the outstanding request and starts none`() {
        val gate = LatestRequestGate()
        val a = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(a))
        assertFalse(gate.finish(a))
    }

    @Test
    fun `no token is current before the first request`() {
        val gate = LatestRequestGate()
        assertFalse(gate.isCurrent(0))
        assertFalse(gate.finish(0))
    }
}

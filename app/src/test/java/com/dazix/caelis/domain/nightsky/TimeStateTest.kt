package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimeStateTest {

    @Test
    fun defaultTimeState_isLive() {
        val state = TimeState()
        assertFalse(state.isVirtual)
        assertNull(state.virtualTime)
    }

    @Test
    fun defaultTimeState_resolvesLiveInstant() {
        val before = Instant.now()
        val state = TimeState()
        val active = state.activeInstant()
        val after = Instant.now()

        assertTrue(active.toEpochMilli() in before.toEpochMilli()..after.toEpochMilli())
    }

    @Test
    fun virtualTimeState_resolvesVirtualInstant() {
        val target = Instant.parse("2026-09-22T23:30:00Z")
        val state = TimeState(isVirtual = true, virtualTime = target)

        assertTrue(state.isVirtual)
        assertEquals(target, state.virtualTime)
        assertEquals(target, state.activeInstant())
    }

    @Test
    fun virtualTimeStateWithNullTime_fallsBackToLiveInstant() {
        val before = Instant.now()
        val state = TimeState(isVirtual = true, virtualTime = null)
        val active = state.activeInstant()
        val after = Instant.now()

        assertTrue(active.toEpochMilli() in before.toEpochMilli()..after.toEpochMilli())
    }
}

package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealignTriggerTest {

    @Test
    fun `the first tracking frame of a session aligns`() {
        val t = RealignTrigger()
        assertTrue(t.onFrame(tracking = true, epoch = 1))
    }

    @Test
    fun `steady tracking in one session never re-aligns`() {
        val t = RealignTrigger()
        t.onFrame(true, 1)
        repeat(100) { assertFalse(t.onFrame(true, 1)) }
    }

    @Test
    fun `tracking regained after a loss aligns again`() {
        val t = RealignTrigger()
        t.onFrame(true, 1)
        assertFalse(t.onFrame(false, 1))
        assertFalse(t.onFrame(false, 1))
        assertTrue(t.onFrame(true, 1))
        assertFalse(t.onFrame(true, 1))
    }

    @Test
    fun `a new session or a resume aligns even if tracking never dropped`() {
        val t = RealignTrigger()
        t.onFrame(true, 1)
        assertTrue(t.onFrame(true, 2))
        assertFalse(t.onFrame(true, 2))
    }

    @Test
    fun `reset forgets everything`() {
        val t = RealignTrigger()
        t.onFrame(true, 1)
        t.reset()
        assertTrue(t.onFrame(true, 1))
    }
}

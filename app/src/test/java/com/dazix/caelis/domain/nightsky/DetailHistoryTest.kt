package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailHistoryTest {

    private val summerTriangle = SelectionRef.Figure(90)
    private val cygnus = SelectionRef.Figure(17)
    private val deneb = SelectionRef.Star(3)

    @Test
    fun `back retraces the followed names in reverse`() {
        val history = DetailHistory()
        history.follow(summerTriangle, cygnus)
        history.follow(cygnus, deneb)
        assertEquals(cygnus, history.back())
        assertEquals(summerTriangle, history.back())
        assertNull(history.back())
        assertTrue(history.isEmpty)
    }

    @Test
    fun `following from nothing or to the body already shown adds nothing`() {
        val history = DetailHistory()
        history.follow(null, cygnus)
        history.follow(cygnus, cygnus)
        assertTrue(history.isEmpty)
    }

    @Test
    fun `clear forgets the way back`() {
        val history = DetailHistory()
        history.follow(summerTriangle, cygnus)
        history.clear()
        assertNull(history.back())
    }

    @Test
    fun `beyond the capacity the oldest entry goes first`() {
        val history = DetailHistory(capacity = 2)
        history.follow(SelectionRef.Star(1), SelectionRef.Star(2))
        history.follow(SelectionRef.Star(2), SelectionRef.Star(3))
        history.follow(SelectionRef.Star(3), SelectionRef.Star(4))
        assertEquals(SelectionRef.Star(3), history.back())
        assertEquals(SelectionRef.Star(2), history.back())
        assertNull(history.back())
    }
}

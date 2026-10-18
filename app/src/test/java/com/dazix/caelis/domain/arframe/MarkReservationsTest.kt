package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkReservationsTest {

    @Test
    fun `an empty book has nothing taken`() {
        assertFalse(MarkReservations(4).isTaken(10f, 10f, 5f, 5f))
    }

    @Test
    fun `a reserved rectangle blocks an overlapping one and not a distant one`() {
        val book = MarkReservations(4)
        book.reserve(100f, 100f, 50f, 15f)

        assertTrue(book.isTaken(140f, 110f, 25f, 10f))
        assertFalse(book.isTaken(176f, 100f, 25f, 10f))
    }

    @Test
    fun `tryReserve takes a free spot and then refuses the same spot`() {
        val book = MarkReservations(4)

        assertTrue(book.tryReserve(100f, 100f, 50f, 15f))
        assertFalse(book.tryReserve(110f, 100f, 50f, 15f))
        assertEquals(1, book.count)
    }

    @Test
    fun `a refused tryReserve leaves nothing behind for later marks`() {
        val book = MarkReservations(4)
        book.reserve(100f, 100f, 50f, 15f)
        book.tryReserve(110f, 100f, 50f, 15f)

        // The refused mark reserved nothing, so a spot only it would have covered is still free.
        assertFalse(book.isTaken(190f, 100f, 20f, 10f))
    }

    @Test
    fun `clear starts a new frame`() {
        val book = MarkReservations(4)
        book.reserve(100f, 100f, 50f, 15f)
        book.clear()

        assertEquals(0, book.count)
        assertFalse(book.isTaken(100f, 100f, 50f, 15f))
    }

    @Test
    fun `a full book stops recording instead of growing`() {
        val book = MarkReservations(2)
        book.reserve(0f, 0f, 1f, 1f)
        book.reserve(100f, 0f, 1f, 1f)
        book.reserve(200f, 0f, 1f, 1f)

        assertEquals(2, book.count)
        assertFalse(book.isTaken(200f, 0f, 1f, 1f))
    }
}

package com.dazix.caelis.domain.playstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptPolicyTest {

    private val install = 20_000L

    /** Used on enough days, installed long enough, never asked. */
    private val ready = ReviewHistory(engagedDays = 3, lastOpenEpochDay = install + 10)

    private fun ask(
        history: ReviewHistory = ready,
        today: Long = install + 10,
        installDay: Long? = install,
        home: Boolean = true,
        session: Boolean = false,
        updateOffered: Boolean = false,
    ) = ReviewPromptPolicy.shouldRequest(history, today, installDay, home, session, updateOffered)

    @Test
    fun `several starts on one day count as one day of use`() {
        var history = ReviewHistory()
        repeat(5) { history = ReviewPromptPolicy.recordOpen(history, install) }
        assertEquals(1, history.engagedDays)
        history = ReviewPromptPolicy.recordOpen(history, install + 1)
        assertEquals(2, history.engagedDays)
        assertEquals(install + 1, history.lastOpenEpochDay)
    }

    @Test
    fun `asks once used on enough days and installed long enough`() {
        assertTrue(ask())
    }

    @Test
    fun `too few days of use asks nothing`() {
        assertFalse(ask(history = ready.copy(engagedDays = ReviewPromptPolicy.MIN_ENGAGED_DAYS - 1)))
    }

    @Test
    fun `too recent an install asks nothing`() {
        assertFalse(ask(today = install + ReviewPromptPolicy.MIN_DAYS_INSTALLED - 1))
        assertTrue(ask(today = install + ReviewPromptPolicy.MIN_DAYS_INSTALLED))
    }

    @Test
    fun `an unknown install date asks nothing`() {
        assertFalse(ask(installDay = null))
    }

    @Test
    fun `asks only on the home screen, outside a session, and not next to the update sheet`() {
        assertFalse(ask(home = false))
        assertFalse(ask(session = true))
        assertFalse(ask(updateOffered = true))
    }

    @Test
    fun `asked once, not again before the interval has passed`() {
        val asked = ReviewPromptPolicy.recordRequest(ready, install + 10)
        assertFalse(ask(history = asked, today = install + 10))
        assertFalse(ask(history = asked, today = install + 10 + ReviewPromptPolicy.MIN_DAYS_BETWEEN_REQUESTS - 1))
        assertTrue(ask(history = asked, today = install + 10 + ReviewPromptPolicy.MIN_DAYS_BETWEEN_REQUESTS))
    }

    @Test
    fun `a clock set back does not reopen the question`() {
        val asked = ReviewPromptPolicy.recordRequest(ready, install + 400)
        assertFalse(ask(history = asked, today = install + 10))
    }
}

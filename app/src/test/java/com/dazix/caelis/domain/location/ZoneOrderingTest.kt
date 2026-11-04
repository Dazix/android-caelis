package com.dazix.caelis.domain.location

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ZoneOrderingTest {

    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")

    @Test
    fun `orders west to east, then by id within an offset`() {
        val ids = listOf("Europe/Prague", "Asia/Kolkata", "UTC", "America/New_York", "Europe/Berlin", "Europe/London")
        val sorted = ZoneOrdering.sorted(ids, winter).map { it.id }
        assertEquals(
            listOf("America/New_York", "Europe/London", "UTC", "Europe/Berlin", "Europe/Prague", "Asia/Kolkata"),
            sorted,
        )
    }

    @Test
    fun `the offset is the one at the given instant, daylight saving included`() {
        val ids = listOf("Europe/London", "UTC")
        assertEquals(listOf("Europe/London", "UTC"), ZoneOrdering.sorted(ids, winter).map { it.id })
        // In summer London is an hour ahead of UTC.
        assertEquals(listOf("UTC", "Europe/London"), ZoneOrdering.sorted(ids, summer).map { it.id })
        assertEquals("UTC+01:00", ZoneOrdering.sorted(listOf("Europe/London"), summer).single().offsetLabel)
    }

    @Test
    fun `labels use a real minus and plain UTC for zero`() {
        val entries = ZoneOrdering.sorted(listOf("America/St_Johns", "UTC", "Asia/Kathmandu"), winter)
        assertEquals(listOf("UTC−03:30", "UTC", "UTC+05:45"), entries.map { it.offsetLabel })
    }

    @Test
    fun `an unknown id is skipped rather than failing the list`() {
        assertEquals(listOf("UTC"), ZoneOrdering.sorted(listOf("Mars/Olympus", "UTC"), winter).map { it.id })
    }

    @Test
    fun `filter matches ids with spaces and offsets with a typed hyphen`() {
        val entries = ZoneOrdering.sorted(listOf("America/New_York", "America/St_Johns", "Europe/Prague"), winter)
        assertEquals(listOf("America/New_York"), ZoneOrdering.filter(entries, "new york").map { it.id })
        assertEquals(listOf("America/St_Johns"), ZoneOrdering.filter(entries, "-03:30").map { it.id })
        assertEquals(listOf("Europe/Prague"), ZoneOrdering.filter(entries, "+01").map { it.id })
        assertEquals(entries, ZoneOrdering.filter(entries, "  "))
    }

    @Test
    fun `every zone the platform knows is listed once, in order`() {
        val all = ZoneId.getAvailableZoneIds()
        val sorted = ZoneOrdering.sorted(all, winter)
        assertEquals(all.size, sorted.size)
        sorted.zipWithNext().forEach { (a, b) ->
            val ordered = a.offset.totalSeconds < b.offset.totalSeconds ||
                (a.offset == b.offset && a.id < b.id)
            assertEquals("${a.id} before ${b.id}", true, ordered)
        }
        assertEquals(sorted.indexOfFirst { it.id == "Europe/Prague" }, ZoneOrdering.indexOf(sorted, "Europe/Prague"))
        assertEquals(-1, ZoneOrdering.indexOf(sorted, "Mars/Olympus"))
    }
}

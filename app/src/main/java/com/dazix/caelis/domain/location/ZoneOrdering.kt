package com.dazix.caelis.domain.location

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A zone as the picker lists it: its id and its UTC offset at the instant the list was made. */
data class ZoneEntry(val id: String, val offset: ZoneOffset) {
    /** "UTC+01:00", "UTC−03:30", "UTC" — the same width for every zone but UTC itself. */
    val offsetLabel: String
        get() = if (offset.totalSeconds == 0) "UTC" else "UTC" + offset.id.replace('-', '−')
}

/**
 * Orders the time zones for the place editor's picker: west to east by their offset *now*, so the
 * phone's own zone sits among its neighbours rather than wherever its name falls in the alphabet,
 * then by id within an offset. The offset is taken at one instant for the whole list, so two zones
 * that differ only in daylight saving rules are ordered by the offset they have today.
 */
object ZoneOrdering {

    fun sorted(zoneIds: Collection<String>, at: Instant): List<ZoneEntry> =
        zoneIds
            .mapNotNull { id ->
                val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return@mapNotNull null
                ZoneEntry(id, zone.rules.getOffset(at))
            }
            .sortedWith(compareBy<ZoneEntry> { it.offset.totalSeconds }.thenBy { it.id })

    /**
     * Filters by id or offset label, ignoring case. A space in [query] matches the underscore in
     * "New_York", and a typed hyphen matches the label's minus sign.
     */
    fun filter(entries: List<ZoneEntry>, query: String): List<ZoneEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        val idQuery = q.replace(' ', '_')
        val offsetQuery = q.replace('−', '-')
        return entries.filter {
            it.id.contains(idQuery, ignoreCase = true) ||
                it.offsetLabel.replace('−', '-').contains(offsetQuery, ignoreCase = true)
        }
    }

    /** Index of [selected] in [entries], or -1. */
    fun indexOf(entries: List<ZoneEntry>, selected: String): Int = entries.indexOfFirst { it.id == selected }
}

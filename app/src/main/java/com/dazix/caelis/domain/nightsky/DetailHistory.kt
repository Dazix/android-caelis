package com.dazix.caelis.domain.nightsky

/** A selected body by catalog index (or, for a planet or the Moon, by id). */
sealed interface SelectionRef {
    data class Star(val starIndex: Int) : SelectionRef
    data class Figure(val constellationIndex: Int) : SelectionRef
    data class Dso(val dsoIndex: Int) : SelectionRef
    data class Meteor(val showerIndex: Int) : SelectionRef
    data class Planet(val planetId: String) : SelectionRef
}

/**
 * The bodies the detail sheet was followed from, so that back returns to them in turn: Summer
 * Triangle -> Cygnus -> Deneb, then back to Cygnus and back to the Summer Triangle.
 *
 * Only following a name in the sheet adds an entry. Any other selection (a tap on the sky, a search
 * result, the disambiguation list) and closing the sheet start over with [clear], so back never
 * returns to something from before the user chose a new body themselves.
 */
class DetailHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<SelectionRef>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Records [from], the body shown when a name in its sheet was followed. A follow from nothing,
     * or to the body already shown, adds nothing. Beyond [capacity] the oldest entry is dropped.
     */
    fun follow(from: SelectionRef?, to: SelectionRef) {
        if (from == null || from == to) return
        entries.addLast(from)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** The body to go back to, removed from the history, or null when there is none. */
    fun back(): SelectionRef? = entries.removeLastOrNull()

    fun clear() = entries.clear()

    companion object {
        /** A chain longer than this is a walk around the sky, not a way back. */
        const val DEFAULT_CAPACITY = 32
    }
}

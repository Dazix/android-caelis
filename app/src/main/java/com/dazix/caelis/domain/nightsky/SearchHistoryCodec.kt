package com.dazix.caelis.domain.nightsky

/**
 * Pure Kotlin serialization codec and collection manager for [SearchResultItem] search history records.
 * Ensures zero external library dependencies and fast, deterministic JVM unit testing.
 */
object SearchHistoryCodec {
    const val MAX_HISTORY_ITEMS = 20

    /**
     * Serializes a list of [SearchResultItem]s into a newline/tab-delimited string format for DataStore storage.
     */
    fun encode(items: List<SearchResultItem>): String {
        return items.take(MAX_HISTORY_ITEMS).joinToString("\n") { item ->
            listOf(
                item.type.name,
                item.indexId.toString(),
                escape(item.primaryName),
                escape(item.secondaryName ?: ""),
                item.ra.toString(),
                item.dec.toString(),
                item.magnitude?.toString() ?: "",
                item.planetId ?: ""
            ).joinToString("\t")
        }
    }

    /**
     * Deserializes a string back into a list of [SearchResultItem]s.
     * Safely ignores malformed or corrupted lines without throwing exceptions.
     */
    fun decode(raw: String): List<SearchResultItem> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val tokens = line.split("\t")
                if (tokens.size < 8) return@mapNotNull null
                val type = runCatching { CelestialType.valueOf(tokens[0]) }.getOrNull() ?: return@mapNotNull null
                val indexId = tokens[1].toIntOrNull() ?: return@mapNotNull null
                val primaryName = unescape(tokens[2])
                val secondaryName = unescape(tokens[3]).takeIf { it.isNotEmpty() }
                val ra = tokens[4].toFloatOrNull() ?: return@mapNotNull null
                val dec = tokens[5].toFloatOrNull() ?: return@mapNotNull null
                val magnitude = tokens[6].toFloatOrNull()
                val planetId = tokens[7].takeIf { it.isNotEmpty() }
                SearchResultItem(
                    indexId = indexId,
                    primaryName = primaryName,
                    secondaryName = secondaryName,
                    type = type,
                    ra = ra,
                    dec = dec,
                    magnitude = magnitude,
                    planetId = planetId,
                )
            }
            .take(MAX_HISTORY_ITEMS)
            .toList()
    }

    /**
     * Inserts [item] at the beginning of [current] history, deduplicating any existing occurrence,
     * and capping the total count at [maxItems].
     */
    fun addToHistory(
        current: List<SearchResultItem>,
        item: SearchResultItem,
        maxItems: Int = MAX_HISTORY_ITEMS,
    ): List<SearchResultItem> {
        val filtered = current.filterNot { isSameItem(it, item) }
        return (listOf(item) + filtered).take(maxItems)
    }

    /**
     * Removes [item] from [current] history based on item identity.
     */
    fun removeFromHistory(
        current: List<SearchResultItem>,
        item: SearchResultItem,
    ): List<SearchResultItem> {
        return current.filterNot { isSameItem(it, item) }
    }

    private fun isSameItem(a: SearchResultItem, b: SearchResultItem): Boolean {
        return a.type == b.type &&
            a.indexId == b.indexId &&
            a.primaryName == b.primaryName &&
            a.planetId == b.planetId
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
}

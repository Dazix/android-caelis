package com.dazix.caelis.domain.location

/**
 * Tab-separated storage of [SavedPlace]s and the [PlaceSelection], one record per line.
 *
 * A malformed line is dropped, never thrown on: one damaged record must not take the whole list
 * with it. An unknown zone id is kept as written ([SavedPlace.zone] reads it as UTC), so a place
 * saved under a newer tz database survives a downgrade.
 */
object SavedPlaceCodec {

    private const val FIELD_COUNT = 6

    fun encodePlaces(places: List<SavedPlace>): String =
        places.joinToString("\n") { place ->
            listOf(
                escape(place.id),
                escape(place.name),
                place.latitude.toString(),
                place.longitude.toString(),
                escape(place.zoneId),
                place.createdAtMs.toString(),
            ).joinToString("\t")
        }

    fun decodePlaces(raw: String): List<SavedPlace> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val tokens = line.split("\t")
            if (tokens.size < FIELD_COUNT) return@mapNotNull null
            val id = unescape(tokens[0]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = unescape(tokens[1])
            val lat = tokens[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = tokens[3].toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val zone = unescape(tokens[4]).takeIf { it.isNotEmpty() } ?: "UTC"
            val createdAt = tokens[5].toLongOrNull() ?: 0L
            SavedPlace(id, name, lat, lon, zone, createdAt)
        }.distinctBy { it.id }.toList()
    }

    /** `sync`, `shared`, `sunAr`, `nightSky`; an empty slot is GPS. */
    fun encodeSelection(selection: PlaceSelection): String =
        listOf(
            if (selection.sync) "1" else "0",
            escape(selection.shared.orEmpty()),
            escape(selection.sunAr.orEmpty()),
            escape(selection.nightSky.orEmpty()),
        ).joinToString("\t")

    fun decodeSelection(raw: String): PlaceSelection {
        val tokens = raw.trim('\n').split("\t")
        if (tokens.size < 4) return PlaceSelection()
        fun slot(i: Int) = unescape(tokens[i]).takeIf { it.isNotEmpty() }
        return PlaceSelection(
            sync = tokens[0] == "1",
            shared = slot(1),
            sunAr = slot(2),
            nightSky = slot(3),
        )
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

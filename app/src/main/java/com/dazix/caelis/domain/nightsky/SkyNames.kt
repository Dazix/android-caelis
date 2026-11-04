package com.dazix.caelis.domain.nightsky

/**
 * The names of night-sky objects in one language, read from `res/raw/sky_names.tsv` (English) or
 * its translation in `res/raw-<language>/`.
 *
 * The catalog carries ids and English only; every name a user reads comes from here, one file per
 * language, so a new language is a file and not a catalog rebuild, and a language split delivers
 * only the user's own names. Android picks the file (`raw-de` for de-AT, `raw` for any language
 * without one), so this class never asks which language it is.
 *
 * Keys: `con.<IAU code>`, `ast.<Stellarium asterism code>`, `dso.M<n>`, `met.<id>`,
 * `planet.<PlanetState.id>`, `type.<body type in snake_case>`. A key a file lacks falls back to the
 * English name the caller passes, so a missing translation shows English rather than nothing.
 */
class SkyNames(private val names: Map<String, String>) {

    val size: Int get() = names.size

    fun keys(): Set<String> = names.keys

    operator fun get(key: String): String? = names[key]

    fun constellation(id: String?, isAsterism: Boolean, fallback: String?): String? {
        if (id.isNullOrEmpty()) return fallback
        return names[(if (isAsterism) "ast." else "con.") + id] ?: fallback
    }

    /** The name of the constellation with this IAU code ("CMa"), or the code itself. */
    fun constellationOfCode(code: String?): String? {
        if (code.isNullOrEmpty()) return code
        return names["con.$code"] ?: code
    }

    fun messier(messierId: Int, fallback: String?): String? = names["dso.M$messierId"] ?: fallback

    fun meteorShower(id: Int, fallback: String?): String? = names["met.$id"] ?: fallback

    fun planet(id: String, fallback: String): String = names["planet.$id"] ?: fallback

    /** "Red Giant" -> "Červený obr"; an unknown class is shown as the catalog has it. */
    fun starType(type: String?): String? {
        if (type.isNullOrEmpty()) return type
        return names[typeKey(type)] ?: type
    }

    companion object {
        val NONE = SkyNames(emptyMap())

        private val NON_LETTERS = Regex("[^a-z]+")

        /** The key of a catalog body type: "Orange/Red Star" -> "type.orange_red_star". */
        fun typeKey(type: String): String = "type." + type.lowercase().replace(NON_LETTERS, "_").trim('_')

        /**
         * `key<TAB>name` per line; `#` starts a comment line, blank lines are skipped. A line without
         * a tab or with an empty name is ignored rather than failing the whole sky over one typo; the
         * parity test is where a broken file is caught.
         */
        fun parse(text: String): SkyNames {
            val map = HashMap<String, String>(512)
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty() || line.startsWith('#')) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val name = line.substring(tab + 1).trim()
                if (name.isNotEmpty()) map[line.substring(0, tab)] = name
            }
            return SkyNames(map)
        }
    }
}

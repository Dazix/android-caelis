package com.dazix.caelis.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every language the app ships is complete, in every place a language lives.
 *
 * A language is a `values-<language>` directory; adding one is adding files, and this test is the
 * checklist: the same strings and plurals as `values/`, the plural forms the language needs, the
 * same format arguments in every string (a dropped `%1$d` is a crash or a wrong sentence on a
 * device nobody tests in that language), the debug strings, the night-sky names in
 * `raw-<language>/sky_names.tsv` and a store listing in `metadata/android/<language>-*`. A regional
 * directory such as `values-de-rAT` is an override and may hold only keys the default has.
 *
 * Lint's MissingTranslation sees a missing string, not a missing plural form or a changed argument,
 * and it does not look at raw files or the store listing at all.
 */
class TranslationParityTest {

    /** CLDR cardinal categories the app's strings use, per language. A new language goes here first. */
    private val pluralForms = mapOf(
        "en" to setOf("one", "other"),
        "de" to setOf("one", "other"),
        "cs" to setOf("one", "few", "many", "other"),
        "sk" to setOf("one", "few", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"),
    )

    @Test
    fun `the expected languages are all there`() {
        assertEquals(setOf("cs", "sk", "pl", "de"), languages())
    }

    @Test
    fun `every language has every string and plural of the default, and nothing else`() {
        val english = resources("main", "values")
        assertTrue("no strings found at all — wrong path?", english.strings.size > 100)
        languages().forEach { language ->
            val translated = resources("main", "values-$language")
            assertEquals("$language: missing strings", emptySet<String>(), english.strings.keys - translated.strings.keys)
            assertEquals("$language: strings the default lacks", emptySet<String>(), translated.strings.keys - english.strings.keys)
            assertEquals("$language: missing plurals", emptySet<String>(), english.plurals.keys - translated.plurals.keys)
            assertEquals("$language: plurals the default lacks", emptySet<String>(), translated.plurals.keys - english.plurals.keys)
        }
    }

    @Test
    fun `plurals declare exactly the forms each language needs`() {
        (languages() + "en").forEach { language ->
            val forms = pluralForms[language] ?: return@forEach fail("no plural forms known for $language")
            val dir = if (language == "en") "values" else "values-$language"
            val plurals = resources("main", dir).plurals
            assertTrue("$dir has no plurals", plurals.isNotEmpty())
            plurals.forEach { (name, quantities) -> assertEquals("quantities of $dir/$name", forms, quantities.keys) }
        }
    }

    @Test
    fun `every translation takes the same format arguments as the default`() {
        val english = resources("main", "values")
        (languages().map { "values-$it" } + regionalOverrides()).forEach { dir ->
            val translated = resources("main", dir)
            translated.strings.forEach { (name, text) ->
                val original = english.strings[name] ?: return@forEach
                assertEquals("format arguments of $dir/$name", formatArguments(original), formatArguments(text))
            }
            translated.plurals.forEach { (name, forms) ->
                val expected = formatArguments(english.plurals.getValue(name).getValue("other"))
                forms.forEach { (quantity, text) ->
                    assertEquals("format arguments of $dir/$name[$quantity]", expected, formatArguments(text))
                }
            }
        }
    }

    @Test
    fun `a regional override holds only keys the default has`() {
        val english = resources("main", "values")
        regionalOverrides().forEach { dir ->
            val override = resources("main", dir)
            assertEquals("$dir: unknown strings", emptySet<String>(), override.strings.keys - english.strings.keys)
            assertEquals("$dir: unknown plurals", emptySet<String>(), override.plurals.keys - english.plurals.keys)
        }
    }

    @Test
    fun `the debug harness strings are translated too`() {
        val english = resources("debug", "values")
        assertTrue("debug_fake_camera_label" in english.strings)
        languages().forEach { language ->
            val translated = resources("debug", "values-$language")
            assertEquals("debug strings for $language", english.strings.keys, translated.strings.keys)
        }
    }

    @Test
    fun `every language names the whole night sky`() {
        val english = skyNames(res("main").resolve("raw/sky_names.tsv"))
        assertTrue("sky_names.tsv looks empty", english.size > 300)
        val rawLanguages = res("main").listFiles { file -> file.isDirectory && file.name.startsWith("raw-") }
            .orEmpty().map { it.name.removePrefix("raw-") }.toSet()
        assertEquals("raw-<language> directories without a values-<language>, or the reverse", languages(), rawLanguages)
        languages().forEach { language ->
            val translated = skyNames(res("main").resolve("raw-$language/sky_names.tsv"))
            assertEquals("$language: sky names missing", emptySet<String>(), english.keys - translated.keys)
            assertEquals("$language: sky names the default lacks", emptySet<String>(), translated.keys - english.keys)
        }
    }

    @Test
    fun `every language has a store listing`() {
        val metadata = listOf(File("../metadata/android"), File("metadata/android")).first { it.isDirectory }
        val listed = metadata.listFiles { file -> file.isDirectory }.orEmpty().map { it.name.substringBefore('-') }.toSet()
        assertEquals("store listing languages (metadata/android/<language>-<REGION>)", languages() + "en", listed)
    }

    /** `values-cs`, `values-de`, ... with strings in them: a language the app ships. */
    private fun languages(): Set<String> = res("main").listFiles { file -> file.isDirectory }.orEmpty()
        .mapNotNull { LANGUAGE_DIR.matchEntire(it.name)?.groupValues?.get(1) }
        .filter { stringFiles("main", "values-$it").isNotEmpty() }
        .toSet()

    /** `values-de-rAT`: a regional override of one of [languages]. */
    private fun regionalOverrides(): List<String> = res("main").listFiles { file -> file.isDirectory }.orEmpty()
        .map { it.name }
        .filter { REGION_DIR.matches(it) }

    private class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    /** Every `strings*.xml` of one directory at once: a key may move between the files. */
    private fun resources(sourceSet: String, valuesDir: String): Resources {
        val files = stringFiles(sourceSet, valuesDir)
        assertTrue("no strings in $sourceSet/$valuesDir", files.isNotEmpty())
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        files.forEach { file ->
            val root = parse(file)
            val stringNodes = root.getElementsByTagName("string")
            for (index in 0 until stringNodes.length) {
                val element = stringNodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                strings[element.getAttribute("name")] = element.textContent
            }
            val pluralNodes = root.getElementsByTagName("plurals")
            for (index in 0 until pluralNodes.length) {
                val element = pluralNodes.item(index) as Element
                val items = element.getElementsByTagName("item")
                plurals[element.getAttribute("name")] = (0 until items.length).associate { itemIndex ->
                    val item = items.item(itemIndex) as Element
                    item.getAttribute("quantity") to item.textContent
                }
            }
        }
        return Resources(strings, plurals)
    }

    private fun stringFiles(sourceSet: String, valuesDir: String): List<File> =
        res(sourceSet).resolve(valuesDir)
            .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }

    /** Runs from the module directory under Gradle and from the repository root under an IDE. */
    private fun res(sourceSet: String): File {
        val candidates = listOf(File("src/$sourceSet/res"), File("app/src/$sourceSet/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("no res directory for $sourceSet; looked in ${candidates.map { it.absolutePath }}")
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    /** key -> name of a `sky_names.tsv`; the format is `SkyNames.parse`'s, re-read here strictly. */
    private fun skyNames(file: File): Map<String, String> {
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val names = LinkedHashMap<String, String>()
        file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split('\t')
            assertEquals("${file.path}:${index + 1} is not key<TAB>name", 2, parts.size)
            assertTrue("${file.path}:${index + 1} has an empty name", parts[1].isNotBlank())
            assertTrue("${file.path}:${index + 1} repeats ${parts[0]}", names.put(parts[0], parts[1]) == null)
        }
        return names
    }

    /**
     * The arguments a string formats, positional or not, as a sorted list: `%1$d of %2$s` -> `[1$d, 2$s]`,
     * `%d%%` -> `[d]`. A literal `%%` is not an argument.
     */
    private fun formatArguments(text: String): List<String> =
        FORMAT.findAll(text.replace("%%", "")).map { it.groupValues[1] + it.groupValues[2] }.sorted().toList()

    private companion object {
        val LANGUAGE_DIR = Regex("""values-([a-z]{2,3})""")
        val REGION_DIR = Regex("""values-[a-z]{2,3}-r[A-Z]{2}""")
        val FORMAT = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""")
    }
}

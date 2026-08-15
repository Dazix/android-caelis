package com.dazix.intervalometer.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The shot counts are quantities, so they are `<plurals>` rather than `%d` glued to a fixed noun.
 *
 * Lint's PluralsCandidate only notices the missing plural; it cannot notice a plural that is missing
 * the forms Czech needs, or one whose translation quietly dropped a format argument — and either of
 * those is a crash or a wrong sentence on a device nobody tests in Czech. So the resources are read
 * back here, from the same XML the build compiles.
 */
class ShotCountPluralsTest {

    /** Czech is one of the languages with four cardinal forms: 1, 2-4, 5+, and a decimal. */
    private val czechQuantities = setOf("one", "few", "many", "other")

    @Test
    fun `every plural in the default locale is also translated to czech`() {
        val english = plurals("values")
        val czech = plurals("values-cs")

        assertTrue("no plurals found at all — wrong path?", english.isNotEmpty())
        assertEquals(
            "plurals with no Czech translation",
            emptySet<String>(),
            english.keys - czech.keys,
        )
        assertEquals(
            "Czech plurals with no default-locale original",
            emptySet<String>(),
            czech.keys - english.keys,
        )
    }

    @Test
    fun `english plurals declare a singular and a general form`() {
        plurals("values").forEach { (name, forms) ->
            assertEquals("quantities of $name", setOf("one", "other"), forms.keys)
        }
    }

    @Test
    fun `czech plurals declare all four forms the language needs`() {
        plurals("values-cs").forEach { (name, forms) ->
            assertEquals("quantities of $name", czechQuantities, forms.keys)
        }
    }

    @Test
    fun `every form of a plural takes the same format arguments`() {
        val english = plurals("values")
        listOf("values", "values-cs").forEach { locale ->
            plurals(locale).forEach { (name, forms) ->
                val expected = formatArguments(english.getValue(name).getValue("other"))
                forms.forEach { (quantity, text) ->
                    assertEquals(
                        "format arguments of $locale/$name[$quantity]",
                        expected,
                        formatArguments(text),
                    )
                }
            }
        }
    }

    @Test
    fun `the debug harness label is translated too`() {
        val english = allStrings(res("debug").resolve("values/strings_debug.xml"))
        val czech = allStrings(res("debug").resolve("values-cs/strings_debug.xml"))

        assertTrue("debug_fake_camera_label" in english)
        assertEquals(
            "untranslated debug strings",
            emptySet<String>(),
            english.keys - czech.keys,
        )
    }

    /**
     * Every `strings*.xml` of one locale at once: the shot counts are split across `strings.xml` and
     * `strings_overlay.xml`, and a plural added to either has the same obligations.
     */
    private fun stringFiles(valuesDir: String): List<File> {
        val dir = res("main").resolve(valuesDir)
        assertTrue("missing ${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
    }

    /** Runs from the module directory under Gradle and from the repository root under an IDE. */
    private fun res(sourceSet: String): File {
        val candidates = listOf(
            File("src/$sourceSet/res"),
            File("app/src/$sourceSet/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("no res directory for $sourceSet; looked in ${candidates.map { it.absolutePath }}")
    }

    /** name -> (quantity -> text) for every `<plurals>` of one locale. */
    private fun plurals(valuesDir: String): Map<String, Map<String, String>> =
        stringFiles(valuesDir).fold(emptyMap()) { all, file -> all + pluralsIn(file) }

    private fun pluralsIn(file: File): Map<String, Map<String, String>> {
        val root = parse(file)
        val nodes = root.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            val items = element.getElementsByTagName("item")
            val forms = (0 until items.length).associate { itemIndex ->
                val item = items.item(itemIndex) as Element
                item.getAttribute("quantity") to item.textContent
            }
            element.getAttribute("name") to forms
        }
    }

    private fun allStrings(file: File): Map<String, String> {
        val nodes = parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun parse(file: File): Element {
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.documentElement
    }

    /** The positional arguments a form uses, e.g. `%1$d shots, about %2$s` -> `[1d, 2s]`. */
    private fun formatArguments(text: String): Set<String> =
        POSITIONAL.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toSet()

    private companion object {
        val POSITIONAL = Regex("""%(\d+)\$([a-zA-Z])""")
    }
}

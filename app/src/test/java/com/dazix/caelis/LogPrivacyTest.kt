package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logcat is in every bug report a user sends, so a release log must not say where the user is. The
 * tiles the app fetches are the 1° squares around them, a profile file is named after the spot to
 * 110 m, and an exception's message carries either one. `data/horizon/TileLog.kt` is how a log line
 * names a tile, a region or an exception; this pins that nothing goes around it.
 */
class LogPrivacyTest {

    /** Text that names a position, a tile or a profile file when it is interpolated into a log line. */
    private val positional = listOf(
        Regex("""\b\w*[kK]ey\.name\b"""),
        Regex("""\$\{?observerKey\b(?!\.logName)"""),
        Regex("""\b(latitude|longitude)\b"""),
        Regex("""\.(lat|lon)\b"""),
    )

    @Test
    fun `no log line names a position or a tile directly`() {
        val offenders = logCalls().filter { (_, arguments) -> positional.any { it.containsMatchIn(arguments) } }
            .map { (where, arguments) -> "$where: Log(${arguments.trim()})" }
        assertEquals("use logName() / regionLogName() from data/horizon/TileLog.kt", emptyList<String>(), offenders)
    }

    /** A terrain exception's message is a file path or a tile name, so its throwable goes through inDebug(). */
    @Test
    fun `terrain code hands a throwable to Log only in a debug build`() {
        val offenders = logCalls()
            .filter { (where, _) -> where.contains("/data/horizon/") }
            .filter { (_, arguments) -> Regex(""",\s*\w+\s*$""").containsMatchIn(arguments.trim()) }
            .filterNot { (_, arguments) -> arguments.trim().endsWith(".inDebug()") }
            .map { (where, arguments) -> "$where: Log(${arguments.trim()})" }
        assertEquals("pass e.inDebug() and log e.logClass", emptyList<String>(), offenders)
    }

    /** Every `Log.x(...)` call in the shipped sources, as (file:line, the text between its parentheses). */
    private fun logCalls(): List<Pair<String, String>> {
        val start = Regex("""\bLog\.[dviwe]\(""")
        val calls = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            start.findAll(text).map { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.path.replace(File.separatorChar, '/')}:$line" to arguments(text, match.range.last + 1)
            }
        }.toList()
        assertTrue("no Log calls found, the scan is looking in the wrong place", calls.size > 20)
        return calls
    }

    /** The text from [from] up to the parenthesis that closes the call, nested parentheses included. */
    private fun arguments(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(from, i - 1)
    }

    private fun sourceRoot(): File = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        ?: error("no src/main/java from ${File(".").absolutePath}")
}

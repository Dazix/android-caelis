package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deploy jobs hold the Play service-account key and the upload keystore, and an update signed
 * with them reaches every phone that has the accessibility service enabled. These are the two ways
 * the workflow itself could hand that key out.
 */
class WorkflowHardeningTest {

    /**
     * A `${{ }}` expression is pasted into the script text before bash parses it. A release body or
     * tag name containing `"; …` then runs as a command next to the key. Values go through `env:`.
     */
    @Test
    fun `no expression is interpolated into a run script`() {
        val offenders = workflowFiles().flatMap { file ->
            runScripts(file.readLines())
                .filter { (_, line) -> "\${{" in line }
                .map { (number, line) -> "${file.invariantPath()}:$number: ${line.trim()}" }
        }
        assertEquals("pass these through env: instead", emptyList<String>(), offenders)
    }

    /**
     * A tag such as `@v1` is moved by whoever controls that repository, and the upload action is handed
     * the Play key. A commit SHA cannot be moved. Actions owned by GitHub itself and the repository's
     * own composite actions are exempt.
     */
    @Test
    fun `every third-party action is pinned to a commit`() {
        val uses = Regex("""^\s*-?\s*uses:\s*(\S+)""")
        val pinned = Regex("""^[^@]+@[0-9a-f]{40}$""")
        val all = workflowFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                uses.find(line)?.groupValues?.get(1)?.let { "${file.invariantPath()}:${index + 1}" to it }
            }
        }
        assertTrue("no uses: found, the scan is looking in the wrong place", all.isNotEmpty())

        val offenders = all
            .filterNot { (_, ref) -> ref.startsWith("./") || ref.startsWith("actions/") }
            .filterNot { (_, ref) -> pinned.matches(ref) }
            .map { (where, ref) -> "$where: $ref" }
        assertEquals("pin these to a 40-character commit SHA", emptyList<String>(), offenders)
    }

    /** Same reasoning for Python packages: an unpinned `pip install` runs whatever was published last. */
    @Test
    fun `every pip install pins an exact version`() {
        val install = Regex("""\bpip3?\s+install\s+(.*)$""")
        val all = workflowFiles().flatMap { file ->
            runScripts(file.readLines()).mapNotNull { (number, line) ->
                install.find(line)?.groupValues?.get(1)?.let { "${file.invariantPath()}:$number" to it }
            }
        }
        val offenders = all.flatMap { (where, arguments) ->
            arguments.trim().split(Regex("""\s+"""))
                .filterNot { it.startsWith("-") }
                .filterNot { Regex("""^[A-Za-z0-9._\-\[\]]+==[0-9][^=\s]*$""").matches(it) }
                .map { "$where: $it" }
        }
        assertEquals("pin these with ==", emptyList<String>(), offenders)
    }

    /**
     * The upload keystore, its base64 form and the Play service-account key are what these jobs protect.
     * `scripts/build_release_bundle.sh` looks for the keystore in `keys/`; a checkout without these
     * lines commits it with the first `git add .`.
     */
    @Test
    fun `signing material stays untracked`() {
        val gitignore = listOf(File(".gitignore"), File("../.gitignore")).firstOrNull { it.isFile }
            ?: error("no .gitignore from ${File(".").absolutePath}")
        val lines = gitignore.readLines().map { it.trim() }
        listOf("/keys/", "*.jks", "*.keystore", "keystore_base64.txt").forEach {
            assertTrue(".gitignore lacks $it", it in lines)
        }
    }

    /** Lines belonging to a `run:` value, with their 1-based numbers: the inline one and a block's body. */
    private fun runScripts(lines: List<String>): List<Pair<Int, String>> {
        val run = Regex("""^(\s*)-?\s*run:\s*(.*)$""")
        val result = ArrayList<Pair<Int, String>>()
        var blockIndent = -1
        lines.forEachIndexed { index, line ->
            if (blockIndent >= 0) {
                val indent = line.length - line.trimStart().length
                if (line.isBlank() || indent > blockIndent) {
                    result += index + 1 to line
                    return@forEachIndexed
                }
                blockIndent = -1
            }
            val match = run.find(line) ?: return@forEachIndexed
            val value = match.groupValues[2]
            result += index + 1 to value
            if (value.startsWith("|") || value.startsWith(">")) {
                blockIndent = line.indexOf("run:")
            }
        }
        return result
    }

    private fun workflowFiles(): List<File> {
        val github = listOf(File(".github"), File("../.github")).firstOrNull { it.isDirectory }
            ?: error("no .github directory from ${File(".").absolutePath}")
        val files = github.walkTopDown()
            .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
            .toList()
        assertTrue("no workflow files found under ${github.path}", files.isNotEmpty())
        return files
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')
}

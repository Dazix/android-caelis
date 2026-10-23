package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release is shrunk by R8, and what R8 cannot see being used is kept on purpose. A JVM test cannot
 * run R8; this pins the configuration, and the release build itself is what proves it links.
 */
class ReleaseBuildTest {

    private val app = listOf(File("."), File("app")).first { File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory }

    @Test
    fun `the release build is minified and resource-shrunk, with the mapping going to Play`() {
        val release = Regex("""(?s)\brelease \{(.*?)\n        \}""").find(File(app, "build.gradle.kts").readText())
            ?.groupValues?.get(1)
            ?: error("no release build type in build.gradle.kts")
        assertTrue("isMinifyEnabled = true" in release)
        assertTrue("isShrinkResources = true" in release)
        assertTrue("\"proguard-rules.pro\"" in release)

        val deploy = listOf(File(".github"), File("../.github")).first { it.isDirectory }
            .resolve("workflows/deploy.yml").readText()
        val uploads = Regex("""uses: r0adkll/upload-google-play@""").findAll(deploy).count()
        val mappings = Regex("""mappingFile: app/build/outputs/mapping/release/mapping\.txt""").findAll(deploy).count()
        assertTrue("every Play upload carries the R8 mapping ($mappings of $uploads)", uploads > 0 && mappings == uploads)
    }

    /**
     * Artwork found by name through Resources.getIdentifier is invisible to the resource shrinker, so
     * every drawable of those families must match a tools:keep pattern or the release draws no art.
     */
    @Test
    fun `every drawable looked up by name is kept against resource shrinking`() {
        val keep = File(app, "src/main/res/raw/keep.xml").readText()
        val patterns = Regex("""tools:keep="([^"]+)"""").find(keep)?.groupValues?.get(1)?.split(',')
            ?.map { it.trim().removePrefix("@drawable/") }
            ?: error("res/raw/keep.xml keeps nothing")
        val byName = listOf("art_", "dso_", "planet_")
        val drawables = File(app, "src/main/res/drawable-nodpi").list()!!.map { it.substringBefore('.') }
            .filter { name -> byName.any { name.startsWith(it) } }
        assertTrue("no artwork found, the scan is looking in the wrong place", drawables.size > 50)

        val unkept = drawables.filterNot { name ->
            patterns.any { p -> if (p.endsWith("*")) name.startsWith(p.dropLast(1)) else name == p }
        }
        assertTrue("not kept: $unkept", unkept.isEmpty())
    }
}

package com.dazix.caelis.ar.scene

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source checks of the Filament sky materials — the successors of the AGSL shader string tests.
 * The constants and mappings are the ones the AGSL shaders used, and night vision must reach
 * every material that colours from a texture or a computed glow.
 */
class SkyMaterialSourceTest {

    private fun material(name: String): String {
        val candidates = listOf(File("src/main/materials/$name.mat"), File("app/src/main/materials/$name.mat"))
        val file = candidates.firstOrNull { it.exists() } ?: error("$name.mat not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun assertContains(src: String, what: String, why: String) =
        assertTrue("$why: expected `$what`", src.contains(what))

    @Test
    fun `milky way maps the celestial direction to the equirectangular texture`() {
        val src = material("milkyway")
        assertContains(src, "name : milkyWay", "texture parameter")
        assertContains(src, "name : enuToCelestial", "the sky turns with sidereal time")
        assertContains(src, "atan(cel.z, cel.x)", "right ascension")
        assertContains(src, "asin(clamp(cel.y, -1.0, 1.0))", "declination")
        assertContains(src, "luminance * 0.3 * nv.rgb", "night vision dims the Milky Way as the Canvas did")
    }

    @Test
    fun `atmosphere glows up to fifteen degrees with a cubic falloff and nothing below the horizon`() {
        val src = material("atmosphere")
        assertContains(src, "0.258", "sin(15 deg)")
        assertContains(src, "pow(factor, 3.0)", "cubic falloff")
        assertContains(src, "luminance * 0.15 * nv.rgb", "night vision")
    }

    @Test
    fun `landscape panorama keeps north in the middle of the image`() {
        val src = material("landscape")
        assertContains(src, "atan(worldDir.x, worldDir.y)", "azimuth clockwise from north")
        assertContains(src, "asin(clamp(worldDir.z, -1.0, 1.0))", "elevation")
        assertContains(src, "theta / 6.283185307179586 + 0.5", "the panorama has north at u = 0.5")
        assertContains(src, "luminance * 0.15 * nv.rgb", "night vision")
    }

    @Test
    fun `stars near the horizon dim through the atmosphere only below ten degrees`() {
        val src = material("star")
        assertContains(src, "name : extinction", "the atmosphere layer gates extinction")
        assertContains(src, "elevationDeg < 10.0", "extinction ends at ten degrees")
    }

    @Test
    fun `every textured or glowing material reads the night vision global`() {
        // line and disc are themed on the CPU (LineBatch / DiscBatch colours), so they do not read it.
        for (name in listOf("milkyway", "atmosphere", "landscape", "terrain", "textured", "star")) {
            assertContains(material(name), "getMaterialGlobal", "$name must follow night vision")
        }
    }

    /**
     * The marks (names, glyphs, reticle) are themed on the CPU like line and disc: their colours are
     * baked into the atlas or written per vertex from the night-vision palette, so they must not also
     * be tinted by the global, which would tint them twice.
     */
    @Test
    fun `marks are themed on the CPU and never tinted by the night vision global`() {
        for (name in listOf("label", "glyph", "line", "disc")) {
            assertTrue("$name is CPU-themed and must not read the night-vision global", !material(name).contains("getMaterialGlobal"))
        }
    }

    /**
     * Marks are drawn over everything and never hidden by the ground's depth (a mark below the ground
     * is left out on the CPU instead), and one behind the camera collapses rather than being mirrored
     * onto the screen.
     */
    @Test
    fun `marks ignore depth and collapse behind the camera`() {
        for (name in listOf("label", "glyph")) {
            val src = material(name)
            assertContains(src, "depthWrite : false", "$name writes no depth")
            assertContains(src, "depthCulling : false", "$name is not hidden by the ground's depth")
            assertContains(src, "dist > 1e-4", "$name collapses behind the camera")
            assertContains(src, "getResolution().y", "$name sizes itself in screen pixels")
        }
    }
}

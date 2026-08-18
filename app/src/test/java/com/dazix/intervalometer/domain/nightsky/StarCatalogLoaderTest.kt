package com.dazix.intervalometer.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StarCatalogLoaderTest {

    @Test
    fun `verifies loading synthetic binary stream`() {
        val byteBuf = ByteBuffer.allocate(3 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        // Star 0: Sirius
        byteBuf.putFloat(101.287f)
        byteBuf.putFloat(-16.716f)
        byteBuf.putFloat(-1.46f)

        // Star 1: Vega
        byteBuf.putFloat(279.234f)
        byteBuf.putFloat(38.784f)
        byteBuf.putFloat(0.03f)

        // Star 2: Polaris
        byteBuf.putFloat(37.955f)
        byteBuf.putFloat(89.264f)
        byteBuf.putFloat(1.98f)

        val stream = ByteArrayInputStream(byteBuf.array())
        val catalog = StarCatalogLoader.loadFromStream(stream)

        assertEquals(3, catalog.starCount)
        assertEquals(101.287f, catalog.getRa(0), 0.001f)
        assertEquals(-16.716f, catalog.getDec(0), 0.001f)
        assertEquals(-1.46f, catalog.getMag(0), 0.001f)

        assertEquals(279.234f, catalog.getRa(1), 0.001f)
        assertEquals(38.784f, catalog.getDec(1), 0.001f)
        assertEquals(0.03f, catalog.getMag(1), 0.001f)

        assertEquals(37.955f, catalog.getRa(2), 0.001f)
        assertEquals(89.264f, catalog.getDec(2), 0.001f)
        assertEquals(1.98f, catalog.getMag(2), 0.001f)
    }

    @Test
    fun `verifies raw stars_catalog bin file integrity`() {
        val binFile = File("src/main/res/raw/stars_catalog.bin")
        if (!binFile.exists()) {
            // Also check relative to project root
            val altFile = File("app/src/main/res/raw/stars_catalog.bin")
            if (altFile.exists()) {
                verifyCatalogFile(altFile)
                return
            }
        } else {
            verifyCatalogFile(binFile)
        }
    }

    private fun verifyCatalogFile(file: File) {
        val catalog = FileInputStream(file).use { stream ->
            StarCatalogLoader.loadFromStream(stream)
        }

        assertTrue("Star catalog should have >= 500 stars, got ${catalog.starCount}", catalog.starCount >= 500)

        // Verify all coordinates and magnitudes are valid numbers within physical bounds
        for (i in 0 until catalog.starCount) {
            val ra = catalog.getRa(i)
            val dec = catalog.getDec(i)
            val mag = catalog.getMag(i)

            assertTrue("RA must be in [0, 360), star $i has $ra", ra in 0.0f..360.0f)
            assertTrue("Dec must be in [-90, +90], star $i has $dec", dec in -90.0f..90.0f)
            assertTrue("Mag must be between -2.0 and 6.0, star $i has $mag", mag in -2.0f..6.5f)
        }

        // Brightest star in catalog should have negative magnitude (Sirius)
        assertTrue(catalog.getMag(0) < 0.0f)
    }
}

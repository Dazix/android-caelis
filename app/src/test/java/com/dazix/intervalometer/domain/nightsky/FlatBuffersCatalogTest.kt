package com.dazix.intervalometer.domain.nightsky

import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.data.nightsky.StarMetadata
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class FlatBuffersCatalogTest {

    @Test
    fun `verifies programmatic FlatBuffers serialization and zero-allocation reading`() {
        val fbb = FlatBufferBuilder(1024)

        // 1. Constellation
        val constNameOff = fbb.createString("Ursa Major")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1, 1, 2, 2, 3))
        val constOff = Constellation.createConstellation(fbb, constNameOff, linesVec)
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))

        // 2. StarMetadata
        val metaNameOff = fbb.createString("Sirius")
        val metaOff = StarMetadata.createStarMetadata(fbb, 0, metaNameOff)
        val metaVec = NightSkyCatalog.createMetadataVector(fbb, intArrayOf(metaOff))

        // 3. Stars (Structs vector)
        NightSkyCatalog.startStarsVector(fbb, 2)
        Star.createStar(fbb, 279.23f, 38.78f, 0.03f) // Vega
        Star.createStar(fbb, 101.28f, -16.71f, -1.44f) // Sirius
        val starsVec = fbb.endVector()

        // 4. Root NightSkyCatalog
        val rootOff = NightSkyCatalog.createNightSkyCatalog(fbb, starsVec, metaVec, constsVec)
        NightSkyCatalog.finishNightSkyCatalogBuffer(fbb, rootOff)

        val bb = fbb.dataBuffer()
        val catalog = NightSkyCatalogLoader.loadFromByteBuffer(bb)

        assertEquals(2, catalog.starsLength())
        assertEquals(1, catalog.metadataLength())
        assertEquals(1, catalog.constellationsLength())

        // Verify zero-allocation reused struct access
        val starReusable = Star()
        catalog.stars(starReusable, 0)
        assertEquals(101.28f, starReusable.ra(), 0.01f)
        assertEquals(-16.71f, starReusable.dec(), 0.01f)
        assertEquals(-1.44f, starReusable.mag(), 0.01f)

        catalog.stars(starReusable, 1)
        assertEquals(279.23f, starReusable.ra(), 0.01f)
        assertEquals(38.78f, starReusable.dec(), 0.01f)
        assertEquals(0.03f, starReusable.mag(), 0.01f)

        // Verify metadata
        val meta = catalog.metadata(0)
        assertNotNull(meta)
        assertEquals(0, meta.starIndex())
        assertEquals("Sirius", meta.name())

        // Verify constellation
        val constObj = catalog.constellations(0)
        assertNotNull(constObj)
        assertEquals("Ursa Major", constObj.name())
        assertEquals(6, constObj.linesLength())
        assertEquals(0, constObj.lines(0))
        assertEquals(1, constObj.lines(1))
    }

    @Test
    fun `verifies raw catalog bin integrity and astronomical contents`() {
        val binFile = File("src/main/res/raw/catalog.bin")
        val targetFile = if (binFile.exists()) binFile else File("app/src/main/res/raw/catalog.bin")
        assertTrue("catalog.bin must exist on disk", targetFile.exists())

        val catalog = FileInputStream(targetFile).use { stream ->
            NightSkyCatalogLoader.loadFromStream(stream)
        }

        assertEquals(1289, catalog.starsLength())
        assertTrue("Metadata count should be >= 20, got ${catalog.metadataLength()}", catalog.metadataLength() >= 20)
        assertTrue("Constellations count should be >= 5, got ${catalog.constellationsLength()}", catalog.constellationsLength() >= 5)

        // Check Sirius
        val star0 = catalog.stars(0)
        assertEquals(101.28f, star0.ra(), 0.5f)
        assertEquals(-16.71f, star0.dec(), 0.5f)
        assertTrue("Sirius magnitude should be negative, got ${star0.mag()}", star0.mag() < 0.0f)

        // Check constellations
        val constNames = (0 until catalog.constellationsLength()).map { catalog.constellations(it).name() }
        assertTrue("Must include Ursa Major", constNames.contains("Ursa Major"))
        assertTrue("Must include Orion", constNames.contains("Orion"))
        assertTrue("Must include Cassiopeia", constNames.contains("Cassiopeia"))
    }
}

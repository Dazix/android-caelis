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

        // 1. Constellation with 7 fields
        val constNameOff = fbb.createString("Ursa Major")
        val locNameOff = fbb.createString("Velká medvědice")
        val brightOff = fbb.createString("Alioth")
        val famOff = fbb.createString("Ursa Major Family")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1, 1, 2, 2, 3))
        val constOff = Constellation.createConstellation(fbb, constNameOff, 184.8f, 55.7f, linesVec, locNameOff, brightOff, famOff)
        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff))

        // 2. StarMetadata with rich fields
        val metaNameOff = fbb.createString("Sirius")
        val metaTypeOff = fbb.createString("Binary Star")
        val metaConstOff = fbb.createString("Canis Major")
        val metaOff = StarMetadata.createStarMetadata(fbb, 0, metaNameOff, metaTypeOff, metaConstOff, 8.6f)
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
        assertEquals("Binary Star", meta.bodyType())
        assertEquals("Canis Major", meta.constellation())
        assertEquals(8.6f, meta.distanceLy(), 0.01f)

        // Verify constellation
        val constObj = catalog.constellations(0)
        assertNotNull(constObj)
        assertEquals("Ursa Major", constObj.name())
        assertEquals(184.8f, constObj.centroidRa(), 0.01f)
        assertEquals(55.7f, constObj.centroidDec(), 0.01f)
        assertEquals("Velká medvědice", constObj.localizedName())
        assertEquals("Alioth", constObj.brightestStar())
        assertEquals("Ursa Major Family", constObj.family())
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

        assertTrue("Star count should be >= 8000, got ${catalog.starsLength()}", catalog.starsLength() >= 8000)
        assertTrue("Metadata count should be >= 400, got ${catalog.metadataLength()}", catalog.metadataLength() >= 400)
        assertEquals("Constellations count must be 88 IAU constellations", 88, catalog.constellationsLength())

        // Check Sirius (brightest star at index 0)
        val star0 = catalog.stars(0)
        assertEquals(101.28f, star0.ra(), 0.5f)
        assertEquals(-16.71f, star0.dec(), 0.5f)
        assertTrue("Sirius magnitude should be negative, got ${star0.mag()}", star0.mag() < 0.0f)

        // Check Sirius metadata
        val meta0 = (0 until catalog.metadataLength()).map { catalog.metadata(it) }.first { it.name() == "Sirius" }
        assertEquals(0, meta0.starIndex())
        assertEquals(8.6f, meta0.distanceLy(), 0.1f)

        // Check constellations
        val consts = (0 until catalog.constellationsLength()).map { catalog.constellations(it) }
        val constNames = consts.map { it.name() }
        assertTrue("Must include Ursa Major", constNames.contains("Ursa Major"))
        assertTrue("Must include Orion", constNames.contains("Orion"))
        assertTrue("Must include Cassiopeia", constNames.contains("Cassiopeia"))
        assertTrue("Must include Cygnus", constNames.contains("Cygnus"))
        assertTrue("Must include Crux", constNames.contains("Crux"))
        assertTrue("Must include Leo", constNames.contains("Leo"))
        assertTrue("Must include Scorpius", constNames.contains("Scorpius"))

        // Check Ursa Major metadata
        val um = consts.first { it.name() == "Ursa Major" }
        assertEquals("Velká medvědice", um.localizedName())
        assertEquals("Alioth", um.brightestStar())
        assertEquals("Ursa Major Family", um.family())
        assertTrue("Ursa Major centroid RA should be ~164, got ${um.centroidRa()}", um.centroidRa() in 155f..175f)
        assertTrue("Ursa Major centroid Dec should be ~55, got ${um.centroidDec()}", um.centroidDec() in 50f..60f)

        // Check Orion metadata
        val ori = consts.first { it.name() == "Orion" }
        assertEquals("Orion", ori.localizedName())
        assertEquals("Rigel", ori.brightestStar())
        assertEquals("Orion Family", ori.family())
        assertTrue("Orion centroid RA should be ~84, got ${ori.centroidRa()}", ori.centroidRa() in 80f..90f)
        assertTrue("Orion centroid Dec should be ~6.8, got ${ori.centroidDec()}", ori.centroidDec() in 0f..15f)
    }
}

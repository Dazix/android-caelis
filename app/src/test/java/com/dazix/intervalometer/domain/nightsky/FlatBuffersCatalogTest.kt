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

        // 1. Constellations: 1 IAU Constellation (isAsterism = false) and 1 Asterism (isAsterism = true)
        val constNameOff = fbb.createString("Ursa Major")
        val locNameOff = fbb.createString("Velká medvědice")
        val brightOff = fbb.createString("Alioth")
        val famOff = fbb.createString("Ursa Major Family")
        val artResOff = fbb.createString("art_uma")
        val linesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1, 1, 2, 2, 3))
        val constOff = Constellation.createConstellation(fbb, constNameOff, 184.8f, 55.7f, linesVec, locNameOff, brightOff, famOff, false, artResOff, 165.2f, 58.4f, 42.5f, 15.0f)

        val astNameOff = fbb.createString("Big Dipper (Plough)")
        val astLocNameOff = fbb.createString("Velký vůz")
        val astBrightOff = fbb.createString("Alioth")
        val astFamOff = fbb.createString("Ursa Major")
        val astLinesVec = Constellation.createLinesVector(fbb, intArrayOf(0, 1, 1, 2))
        val astOff = Constellation.createConstellation(fbb, astNameOff, 175.5f, 56.2f, astLinesVec, astLocNameOff, astBrightOff, astFamOff, true, 0, 0f, 0f, 0f, 0f)

        val constsVec = NightSkyCatalog.createConstellationsVector(fbb, intArrayOf(constOff, astOff))

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
        assertEquals(2, catalog.constellationsLength())

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

        // Verify IAU constellation (isAsterism = false)
        val constObj = catalog.constellations(0)
        assertNotNull(constObj)
        assertEquals("Ursa Major", constObj.name())
        assertEquals(184.8f, constObj.centroidRa(), 0.01f)
        assertEquals(55.7f, constObj.centroidDec(), 0.01f)
        assertEquals("Velká medvědice", constObj.localizedName())
        assertEquals("Alioth", constObj.brightestStar())
        assertEquals("Ursa Major Family", constObj.family())
        assertEquals(false, constObj.isAsterism())
        assertEquals("art_uma", constObj.artResourceName())
        assertEquals(165.2f, constObj.artCenterRa(), 0.01f)
        assertEquals(58.4f, constObj.artCenterDec(), 0.01f)
        assertEquals(42.5f, constObj.artWidthDeg(), 0.01f)
        assertEquals(15.0f, constObj.artRotationDeg(), 0.01f)
        assertEquals(6, constObj.linesLength())
        assertEquals(0, constObj.lines(0))
        assertEquals(1, constObj.lines(1))

        // Verify Asterism (isAsterism = true)
        val astObj = catalog.constellations(1)
        assertNotNull(astObj)
        assertEquals("Big Dipper (Plough)", astObj.name())
        assertEquals("Velký vůz", astObj.localizedName())
        assertEquals("Alioth", astObj.brightestStar())
        assertEquals("Ursa Major", astObj.family())
        assertEquals(true, astObj.isAsterism())
        assertEquals(null, astObj.artResourceName())
        assertEquals(4, astObj.linesLength())
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
        assertEquals("Constellations count must be 151 (88 IAU + 63 asterisms)", 151, catalog.constellationsLength())

        // Check Sirius (brightest star at index 0)
        val star0 = catalog.stars(0)
        assertEquals(101.28f, star0.ra(), 0.5f)
        assertEquals(-16.71f, star0.dec(), 0.5f)
        assertTrue("Sirius magnitude should be negative, got ${star0.mag()}", star0.mag() < 0.0f)

        // Check Sirius metadata
        val meta0 = (0 until catalog.metadataLength()).map { catalog.metadata(it) }.first { it.name() == "Sirius" }
        assertEquals(0, meta0.starIndex())
        assertEquals(8.6f, meta0.distanceLy(), 0.1f)

        // Check constellations & asterisms partition
        val allConsts = (0 until catalog.constellationsLength()).map { catalog.constellations(it) }
        val iauConsts = allConsts.filter { !it.isAsterism() }
        val asterisms = allConsts.filter { it.isAsterism() }

        assertEquals("Must have exactly 88 IAU constellations", 88, iauConsts.size)
        assertEquals("Must have 63 asterisms", 63, asterisms.size)

        val constNames = iauConsts.map { it.name() }
        assertTrue("Must include Ursa Major", constNames.contains("Ursa Major"))
        assertTrue("Must include Orion", constNames.contains("Orion"))
        assertTrue("Must include Cassiopeia", constNames.contains("Cassiopeia"))
        assertTrue("Must include Cygnus", constNames.contains("Cygnus"))
        assertTrue("Must include Crux", constNames.contains("Crux"))
        assertTrue("Must include Leo", constNames.contains("Leo"))
        assertTrue("Must include Scorpius", constNames.contains("Scorpius"))

        // Check Ursa Major metadata (IAU constellation)
        val um = iauConsts.first { it.name() == "Ursa Major" }
        assertEquals("Velká medvědice", um.localizedName())
        assertEquals("Alioth", um.brightestStar())
        assertEquals("Ursa Major Family", um.family())
        assertEquals(false, um.isAsterism())
        assertEquals("art_uma", um.artResourceName())
        assertTrue("Ursa Major art width should be > 20 deg, got ${um.artWidthDeg()}", um.artWidthDeg() > 20f)
        assertTrue("Ursa Major centroid RA should be ~164, got ${um.centroidRa()}", um.centroidRa() in 155f..175f)
        assertTrue("Ursa Major centroid Dec should be ~55, got ${um.centroidDec()}", um.centroidDec() in 50f..60f)

        // Check Orion metadata (IAU constellation)
        val ori = iauConsts.first { it.name() == "Orion" }
        assertEquals("Orion", ori.localizedName())
        assertEquals("Rigel", ori.brightestStar())
        assertEquals("Orion Family", ori.family())
        assertEquals(false, ori.isAsterism())
        assertEquals("art_ori", ori.artResourceName())
        assertTrue("Orion art width should be > 20 deg, got ${ori.artWidthDeg()}", ori.artWidthDeg() > 20f)
        assertTrue("Orion centroid RA should be ~84, got ${ori.centroidRa()}", ori.centroidRa() in 80f..90f)
        assertTrue("Orion centroid Dec should be ~6.8, got ${ori.centroidDec()}", ori.centroidDec() in 0f..15f)

        // Check Big Dipper metadata (Asterism)
        val bigDipper = asterisms.first { it.name() == "Big Dipper (Plough)" }
        assertEquals("Velký vůz", bigDipper.localizedName())
        assertEquals("Alioth", bigDipper.brightestStar())
        assertEquals("Ursa Major", bigDipper.family())
        assertEquals(true, bigDipper.isAsterism())
        assertEquals(null, bigDipper.artResourceName())
        assertTrue("Big Dipper centroid RA should be ~180, got ${bigDipper.centroidRa()}", bigDipper.centroidRa() in 170f..190f)
        assertTrue("Big Dipper centroid Dec should be ~56, got ${bigDipper.centroidDec()}", bigDipper.centroidDec() in 50f..65f)

        // Check Summer Triangle (Asterism)
        val summerTriangle = asterisms.first { it.name() == "Summer Triangle" }
        assertEquals("Letní trojúhelník", summerTriangle.localizedName())
        assertEquals("Vega", summerTriangle.brightestStar())
        assertEquals("Lyra / Cygnus / Aquila", summerTriangle.family())
        assertEquals(true, summerTriangle.isAsterism())
    }
}

package com.dazix.caelis.domain.horizon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkTileCodecTest {

    /** Produced by `data_pipeline/generate_peaks.py`, not by [LandmarkTileCodec.write]. */
    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("landmarks/N50E015.peaks")) {
            "fixture missing: regenerate with data_pipeline/test_generate_peaks.py --write-fixture"
        }.use { it.readBytes() }

    private fun decode(bytes: ByteArray) = LandmarkTileCodec.read(ByteArrayInputStream(bytes))

    private fun fixturePeaks(): List<LandmarkPeak> = checkNotNull(decode(fixtureBytes())) { "fixture did not decode" }

    @Test
    fun `reads the tile the python pipeline wrote`() {
        val peaks = fixturePeaks()

        assertEquals(4, peaks.size)
        val sneka = peaks.single { it.name == "Sněžka" }
        assertEquals(50.7361, sneka.latitude, 1e-9)
        assertEquals(15.7399, sneka.longitude, 1e-9)
        assertEquals(1603, sneka.elevationMeters)
        assertEquals("Sněžka", sneka.nameCs)
        assertEquals("Sniezka", sneka.nameEn)
    }

    @Test
    fun `a missing translation is an empty string`() {
        val peaks = fixturePeaks()

        val polish = peaks.single { it.name == "Śnieżka bis" }
        assertEquals("", polish.nameCs)
        assertEquals("", polish.nameEn)
        assertEquals(1602, polish.elevationMeters)
    }

    @Test
    fun `a peak with only an english name carries it as the local name too`() {
        val peaks = fixturePeaks()

        val english = peaks.single { it.nameEn == "Only English" }
        assertEquals("Only English", english.name)
        assertEquals(1100, english.elevationMeters)
    }

    @Test
    fun `a character outside the BMP survives the python modified utf-8`() {
        val peaks = fixturePeaks()

        assertTrue(peaks.any { it.name == "Smoji 🏔" })
    }

    @Test
    fun `roundtrips through the kotlin writer`() {
        val peaks = listOf(
            LandmarkPeak(-33.9, -70.5, 6_961, "Aconcagua", "", "Aconcagua"),
            LandmarkPeak(0.000001, 179.999999, 1, "A", "B", "C"),
        )
        val out = ByteArrayOutputStream()
        LandmarkTileCodec.write(out, peaks)

        assertEquals(peaks, decode(out.toByteArray()))
    }

    @Test
    fun `an empty tile is a valid answer, not a failure`() {
        val out = ByteArrayOutputStream()
        LandmarkTileCodec.write(out, emptyList())

        assertEquals(emptyList<LandmarkPeak>(), decode(out.toByteArray()))
    }

    @Test
    fun `wrong magic is refused`() {
        val bytes = fixtureBytes().also { it[0] = 0 }

        assertNull(decode(bytes))
    }

    @Test
    fun `an unknown version is refused`() {
        val bytes = fixtureBytes().also { it[7] = 9 }

        assertNull(decode(bytes))
    }

    @Test
    fun `every truncation is refused rather than returning the records read so far`() {
        val bytes = fixtureBytes()

        for (length in 0 until bytes.size) {
            assertNull("length $length", decode(bytes.copyOf(length)))
        }
    }

    @Test
    fun `trailing bytes are refused`() {
        assertNull(decode(fixtureBytes() + byteArrayOf(0)))
    }

    @Test
    fun `an absurd count does not allocate or hang`() {
        val bytes = fixtureBytes().also {
            it[8] = 0x7F
            it[9] = 0xFF.toByte()
        }

        assertNull(decode(bytes))
    }

    @Test
    fun `an empty name is refused`() {
        val out = ByteArrayOutputStream()
        LandmarkTileCodec.write(out, listOf(LandmarkPeak(50.0, 15.0, 100, "", "", "")))

        assertNull(decode(out.toByteArray()))
    }
}

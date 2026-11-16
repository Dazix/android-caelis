package com.dazix.caelis.domain.horizon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
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

        assertEquals(7, peaks.size)
        val sneka = peaks.single { it.name == "Sněžka" }
        assertEquals(50.7361, sneka.latitude, 1e-9)
        assertEquals(15.7399, sneka.longitude, 1e-9)
        assertEquals(1603, sneka.elevationMeters)
        // name:cs equals the local name and is not repeated.
        assertEquals(mapOf("de" to "Schneekoppe", "en" to "Sniezka", "pl" to "Śnieżka"), sneka.names)
    }

    @Test
    fun `a name in another script carries its latin spelling`() {
        val peaks = fixturePeaks().associateBy { it.name }

        assertEquals(mapOf("Latn" to "Fuji", "en" to "Mount Fuji"), peaks.getValue("富士山").names)
        assertEquals(mapOf("Latn" to "Olympos"), peaks.getValue("Όλυμπος").names)
        assertEquals(mapOf("Latn" to "Elbrus", "sk" to "Elbrus"), peaks.getValue("Эльбрус").names)
    }

    @Test
    fun `a peak without translations has no other names`() {
        val peaks = fixturePeaks()

        val polish = peaks.single { it.name == "Śnieżka bis" }
        assertEquals(emptyMap<String, String>(), polish.names)
        assertEquals(1602, polish.elevationMeters)
    }

    @Test
    fun `a peak with only an english name carries it as the local name`() {
        val peaks = fixturePeaks()

        val english = peaks.single { it.name == "Only English" }
        assertEquals(emptyMap<String, String>(), english.names)
        assertEquals(1100, english.elevationMeters)
    }

    @Test
    fun `the kotlin writer produces the python bytes`() {
        val out = ByteArrayOutputStream()
        // The pipeline sorts peaks by position; the writer keeps the order it is given.
        LandmarkTileCodec.write(out, fixturePeaks())

        assertArrayEquals(fixtureBytes(), out.toByteArray())
    }

    @Test
    fun `a key the app does not know yet is read, not refused`() {
        val out = ByteArrayOutputStream()
        val peak = LandmarkPeak(50.0, 15.0, 100, "Mont", mapOf("fr" to "Mont", "Latn" to "x"))
        LandmarkTileCodec.write(out, listOf(peak))

        assertEquals(listOf(peak), decode(out.toByteArray()))
    }

    @Test
    fun `an empty or overlong key, an empty name and a repeated key are refused`() {
        for (names in listOf(mapOf("" to "A"), mapOf("toolongkey" to "A"), mapOf("cs" to ""))) {
            val out = ByteArrayOutputStream()
            LandmarkTileCodec.write(out, listOf(LandmarkPeak(50.0, 15.0, 100, "P", names)))
            assertNull("$names", decode(out.toByteArray()))
        }

        val out = ByteArrayOutputStream()
        LandmarkTileCodec.write(out, listOf(LandmarkPeak(50.0, 15.0, 100, "P", mapOf("cs" to "A", "de" to "B"))))
        val bytes = out.toByteArray()
        val d = bytes.size - 5 // ... 0 2 'd' 'e' 0 1 'B'
        assertEquals('d'.code.toByte(), bytes[d])
        bytes[d] = 'c'.code.toByte()
        bytes[d + 1] = 's'.code.toByte()
        assertNull(decode(bytes))
    }

    @Test
    fun `a version 1 tile is refused, so a cached one is fetched again`() {
        val v1 = ByteArrayOutputStream()
        java.io.DataOutputStream(v1).apply {
            writeInt(0x434C4D4B)
            writeInt(1)
            writeInt(1)
            writeInt(50_000_000)
            writeInt(15_000_000)
            writeShort(1603)
            writeUTF("Sněžka")
            writeUTF("Sněžka")
            writeUTF("Sniezka")
            flush()
        }

        assertNull(decode(v1.toByteArray()))
    }

    @Test
    fun `a character outside the BMP survives the python modified utf-8`() {
        val peaks = fixturePeaks()

        assertTrue(peaks.any { it.name == "Smoji 🏔" })
    }

    @Test
    fun `roundtrips through the kotlin writer`() {
        val peaks = listOf(
            LandmarkPeak(-33.9, -70.5, 6_961, "Aconcagua", mapOf("cs" to "Akonkagua")),
            LandmarkPeak(0.000001, 179.999999, 1, "A", mapOf("cs" to "B", "en" to "C", "Latn" to "D")),
            LandmarkPeak(35.36, 138.73, 3_776, "富士山 🏔"),
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
        LandmarkTileCodec.write(out, listOf(LandmarkPeak(50.0, 15.0, 100, "")))

        assertNull(decode(out.toByteArray()))
    }
}

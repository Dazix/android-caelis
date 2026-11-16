package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile is the one thing the terrain pipeline keeps. If it stops round-tripping, the symptom
 * on a device is a horizon that silently recomputes on every launch — or worse, one that loads
 * plausible nonsense.
 */
class HorizonProfileCodecTest {

    private val profile = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { it * 0.001f - 0.5f },
        observer = GeoLocation(50.0755, 14.4378, altitudeMeters = 214.0),
        observerGroundElevationMeters = 208.5,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 1_757_000_000_000L,
    )

    private fun roundTrip(source: HorizonProfile): HorizonProfile? {
        val bytes = ByteArrayOutputStream().also { HorizonProfileCodec.write(it, source) }.toByteArray()
        return HorizonProfileCodec.read(ByteArrayInputStream(bytes))
    }

    @Test
    fun `a profile survives a round trip byte for byte`() {
        val restored = roundTrip(profile)
        assertNotNull("a freshly written profile must read back", restored)
        restored!!

        assertArrayEquals(profile.elevationsDegrees, restored.elevationsDegrees, 0f)
        assertEquals(profile.observer, restored.observer)
        assertEquals(profile.observer.altitudeMeters, restored.observer.altitudeMeters, 1e-9)
        assertEquals(profile.observerGroundElevationMeters, restored.observerGroundElevationMeters, 1e-9)
        assertEquals(profile.eyeHeightMeters, restored.eyeHeightMeters, 1e-9)
        assertEquals(profile.maxRangeMeters, restored.maxRangeMeters, 1e-9)
        assertEquals(profile.generatedAtEpochMillis, restored.generatedAtEpochMillis)
    }

    @Test
    fun `the encoding stays small enough to keep hundreds of them`() {
        val bytes = ByteArrayOutputStream().also { HorizonProfileCodec.write(it, profile) }.toByteArray()

        assertTrue("expected under 8 kB, was ${bytes.size}", bytes.size < 8 * 1024)
        // magic + version + six doubles + timestamp + coverage + sample count + the samples
        // + the resolved flag + the peak count (this profile names none)
        assertEquals(4 + 4 + 8 * 6 + 8 + 4 + 4 + HorizonProfile.SAMPLE_COUNT * 4 + 1 + 4, bytes.size)
    }

    private val sneka = HorizonLandmark(
        name = "Śnieżka",
        names = mapOf("cs" to "Sněžka", "de" to "Schneekoppe"),
        elevationMeters = 1603,
        distanceMeters = 41_250.5,
        azimuthDegrees = 12.75,
        apparentElevationDegrees = 1.8125,
    )

    @Test
    fun `named peaks and the resolved flag survive a round trip`() {
        val named = profile.withLandmarks(listOf(sneka, sneka.copy(name = "Lysá hora", names = emptyMap(), elevationMeters = 1323)), resolved = true)

        val restored = roundTrip(named)!!

        assertEquals(named.landmarks, restored.landmarks)
        assertTrue(restored.landmarksResolved)
        assertArrayEquals(named.elevationsDegrees, restored.elevationsDegrees, 0f)
    }

    @Test
    fun `an unresolved profile stays unresolved so it is filled in later`() {
        val restored = roundTrip(profile)!!

        assertTrue(restored.landmarks.isEmpty())
        assertEquals(false, restored.landmarksResolved)
    }

    /** What the app wrote before peak names: the layout of version 2, byte for byte. */
    private fun versionTwoBytes(source: HorizonProfile): ByteArray {
        val bytes = ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).apply {
            writeInt(0x4341485A)
            writeInt(2)
            writeDouble(source.observer.latitude)
            writeDouble(source.observer.longitude)
            writeDouble(source.observer.altitudeMeters)
            writeDouble(source.observerGroundElevationMeters)
            writeDouble(source.eyeHeightMeters)
            writeDouble(source.maxRangeMeters)
            writeLong(source.generatedAtEpochMillis)
            writeInt(source.coveredSamples)
            writeInt(source.elevationsDegrees.size)
            source.elevationsDegrees.forEach { writeFloat(it) }
            flush()
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a version 2 profile still reads, with no names and not resolved`() {
        val restored = HorizonProfileCodec.read(ByteArrayInputStream(versionTwoBytes(profile)))

        assertNotNull("a stored skyline must not be thrown away for lacking names", restored)
        restored!!
        assertArrayEquals(profile.elevationsDegrees, restored.elevationsDegrees, 0f)
        assertEquals(profile.observer, restored.observer)
        assertEquals(profile.coveredSamples, restored.coveredSamples)
        assertTrue(restored.landmarks.isEmpty())
        assertEquals("the peaks have never been asked for", false, restored.landmarksResolved)
    }

    /** What the app wrote with peak names in three fixed slots (local, Czech, English): version 3. */
    private fun versionThreeBytes(source: HorizonProfile, resolved: Boolean, peaks: List<Triple<String, String, String>>): ByteArray {
        val head = versionTwoBytes(source).also { it[7] = 3 }
        val tail = ByteArrayOutputStream()
        java.io.DataOutputStream(tail).apply {
            writeBoolean(resolved)
            writeInt(peaks.size)
            for ((name, cs, en) in peaks) {
                writeUTF(name)
                writeUTF(cs)
                writeUTF(en)
                writeInt(1603)
                writeDouble(41_250.5)
                writeDouble(12.75)
                writeDouble(1.8125)
            }
            flush()
        }
        return head + tail.toByteArray()
    }

    @Test
    fun `a version 3 profile keeps its names but is named again`() {
        val bytes = versionThreeBytes(
            profile,
            resolved = true,
            peaks = listOf(Triple("Śnieżka", "Sněžka", ""), Triple("Lysá hora", "", "Lysa Hora")),
        )

        val restored = HorizonProfileCodec.read(ByteArrayInputStream(bytes))!!

        assertEquals(listOf("Śnieżka", "Lysá hora"), restored.landmarks.map { it.name })
        assertEquals(mapOf("cs" to "Sněžka"), restored.landmarks[0].names)
        assertEquals(mapOf("en" to "Lysa Hora"), restored.landmarks[1].names)
        assertEquals(1603, restored.landmarks[0].elevationMeters)
        assertEquals(12.75, restored.landmarks[0].azimuthDegrees, 0.0)
        assertEquals(
            "named from Czech and English tiles only, so the v2 tiles must name it again",
            false,
            restored.landmarksResolved,
        )
    }

    @Test
    fun `a name list that repeats a key reads as absent`() {
        val bytes = ByteArrayOutputStream().also {
            HorizonProfileCodec.write(it, profile.withLandmarks(listOf(sneka.copy(names = mapOf("cs" to "A", "de" to "B"))), resolved = true))
        }.toByteArray()
        // Keys are written sorted: turn "de" into a second "cs".
        val de = bytes.indexOfSequence(byteArrayOf(0, 2, 'd'.code.toByte(), 'e'.code.toByte()))
        bytes[de + 2] = 'c'.code.toByte()
        bytes[de + 3] = 's'.code.toByte()

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes)))
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Test
    fun `a version 4 file cut short inside the peaks reads as absent`() {
        val bytes = ByteArrayOutputStream().also {
            HorizonProfileCodec.write(it, profile.withLandmarks(listOf(sneka), resolved = true))
        }.toByteArray()

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 5))))
    }

    @Test
    fun `an unknown future version reads as absent`() {
        val bytes = versionTwoBytes(profile).also { it[7] = 9 }

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `an absurd peak count reads as absent instead of allocating`() {
        val bytes = ByteArrayOutputStream().also { HorizonProfileCodec.write(it, profile) }.toByteArray()
        // The peak count is the last four bytes of a profile with no peaks.
        bytes[bytes.size - 4] = 0x7f

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `a truncated file reads as absent rather than as a broken profile`() {
        val bytes = ByteArrayOutputStream().also { HorizonProfileCodec.write(it, profile) }.toByteArray()

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))))
        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes.copyOf(8))))
        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `something that is not a profile at all reads as absent`() {
        val notAProfile = "this is a photo, not a horizon".toByteArray()

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(notAProfile)))
    }

    @Test
    fun `the file name pins the position, the range and the eye height`() {
        val name = HorizonProfileCodec.fileName(50.0755, 14.4378, maxRangeKm = 60, eyeHeightCm = 160)

        assertEquals("50.076_14.438_r60_e160.bin", name)
    }

    /**
     * Changing the search range or the eye height changes the skyline, so the two must not share a
     * file. Reusing one under the other's name is a wrong answer that looks like a cached one.
     */
    @Test
    fun `different settings never collide on one file name`() {
        val base = HorizonProfileCodec.fileName(50.0755, 14.4378, 60, 160)

        assertTrue(base != HorizonProfileCodec.fileName(50.0755, 14.4378, 100, 160))
        assertTrue(base != HorizonProfileCodec.fileName(50.0755, 14.4378, 60, 175))
        assertTrue(base != HorizonProfileCodec.fileName(50.0855, 14.4378, 60, 160))
    }

    @Test
    fun `positions inside the rounding step share a file and neighbouring valleys do not`() {
        // About 110 m of latitude: walking around a viewpoint reuses the profile.
        assertEquals(
            HorizonProfileCodec.fileName(50.07551, 14.43781, 60, 160),
            HorizonProfileCodec.fileName(50.07649, 14.43779, 60, 160),
        )
        assertTrue(
            HorizonProfileCodec.fileName(50.0755, 14.4378, 60, 160) !=
                HorizonProfileCodec.fileName(50.0855, 14.4378, 60, 160),
        )
    }

    @Test
    fun `a southern and western observer keeps its sign through the round trip`() {
        val southern = HorizonProfile(
            elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { -1f },
            observer = GeoLocation(-33.45, -70.66, altitudeMeters = 570.0),
            observerGroundElevationMeters = 566.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 100_000.0,
            generatedAtEpochMillis = 1L,
        )

        val restored = roundTrip(southern)!!

        assertEquals(-33.45, restored.observer.latitude, 1e-9)
        assertEquals(-70.66, restored.observer.longitude, 1e-9)
    }
}

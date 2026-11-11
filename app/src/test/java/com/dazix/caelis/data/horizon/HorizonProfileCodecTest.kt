package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.RidgeSamples
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
        // + the resolved flag + the peak count (this profile names none) + the ridge flag (none)
        assertEquals(4 + 4 + 8 * 6 + 8 + 4 + 4 + HorizonProfile.SAMPLE_COUNT * 4 + 1 + 4 + 1, bytes.size)
    }

    private val sneka = HorizonLandmark(
        name = "Śnieżka",
        nameCs = "Sněžka",
        nameEn = "",
        elevationMeters = 1603,
        distanceMeters = 41_250.5,
        azimuthDegrees = 12.75,
        apparentElevationDegrees = 1.8125,
    )

    @Test
    fun `named peaks and the resolved flag survive a round trip`() {
        val named = profile.withLandmarks(listOf(sneka, sneka.copy(name = "Lysá hora", nameCs = "", elevationMeters = 1323)), resolved = true)

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

    @Test
    fun `a version 3 file cut short inside the peaks reads as absent`() {
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
        // The peak count is the four bytes before the ridge flag of a profile with no peaks or ridges.
        bytes[bytes.size - 5] = 0x7f

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

    // ---- Ridges (version 4) -------------------------------------------------------------------

    /** Two points on even azimuths, one on odd ones: uneven counts catch an offset that drifts. */
    private val ridges: RidgeSamples = run {
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1)
        for (i in 0 until HorizonProfile.SAMPLE_COUNT) offsets[i + 1] = offsets[i] + if (i % 2 == 0) 2 else 1
        val total = offsets.last()
        RidgeSamples(offsets, FloatArray(total) { 400f + it * 3.5f }, FloatArray(total) { it * 0.01f - 2f })
    }

    private val ridged = HorizonProfile(
        elevationsDegrees = profile.elevationsDegrees,
        observer = profile.observer,
        observerGroundElevationMeters = profile.observerGroundElevationMeters,
        eyeHeightMeters = profile.eyeHeightMeters,
        maxRangeMeters = profile.maxRangeMeters,
        generatedAtEpochMillis = profile.generatedAtEpochMillis,
        ridges = ridges,
    )

    private fun bytesOf(source: HorizonProfile): ByteArray =
        ByteArrayOutputStream().also { HorizonProfileCodec.write(it, source) }.toByteArray()

    @Test
    fun `ridge samples survive a round trip exactly`() {
        val restored = roundTrip(ridged.withLandmarks(listOf(sneka), resolved = true))!!.ridges!!

        assertArrayEquals(ridges.offsets, restored.offsets)
        assertArrayEquals(ridges.distancesMeters, restored.distancesMeters, 0f)
        assertArrayEquals(ridges.altitudesDegrees, restored.altitudesDegrees, 0f)
    }

    @Test
    fun `a profile without ridges reads back without ridges`() {
        assertNull(roundTrip(profile)!!.ridges)
    }

    @Test
    fun `a version 3 profile still reads, with its names and no ridges`() {
        // Version 3 is version 4 without the trailing ridge flag.
        val v4 = bytesOf(profile.withLandmarks(listOf(sneka), resolved = true))
        val v3 = v4.copyOf(v4.size - 1).also { it[7] = 3 }

        val restored = HorizonProfileCodec.read(ByteArrayInputStream(v3))

        assertNotNull("a stored skyline must not be thrown away for lacking ridges", restored)
        assertEquals(listOf(sneka), restored!!.landmarks)
        assertTrue(restored.landmarksResolved)
        assertNull(restored.ridges)
    }

    @Test
    fun `a ridge count over the cap reads as absent`() {
        val bytes = bytesOf(ridged)
        // The first azimuth's count follows the peak count and the ridge flag.
        val firstCount = 4 + 4 + 8 * 6 + 8 + 4 + 4 + HorizonProfile.SAMPLE_COUNT * 4 + 1 + 4 + 1
        assertEquals(2, bytes[firstCount].toInt())
        bytes[firstCount] = (RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 2).toByte()

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `a negative ridge count reads as absent`() {
        val bytes = bytesOf(ridged)
        val firstCount = 4 + 4 + 8 * 6 + 8 + 4 + 4 + HorizonProfile.SAMPLE_COUNT * 4 + 1 + 4 + 1
        bytes[firstCount] = -1

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `a file cut short inside the ridges reads as absent`() {
        val bytes = bytesOf(ridged)

        assertNull(HorizonProfileCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))))
    }

    @Test
    fun `a profile at the ridge cap on every azimuth stays under eighty kilobytes`() {
        val cap = RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 1
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1) { it * cap }
        val total = offsets.last()
        val full = HorizonProfile(
            elevationsDegrees = profile.elevationsDegrees,
            observer = profile.observer,
            observerGroundElevationMeters = 0.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 0L,
            ridges = RidgeSamples(offsets, FloatArray(total) { 1_000f }, FloatArray(total)),
        )

        val size = bytesOf(full).size
        assertTrue("was $size", size < 80 * 1024)
    }
}

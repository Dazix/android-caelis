package com.dazix.caelis.data.horizon

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
        assertEquals(4 + 4 + 8 * 6 + 8 + 4 + 4 + HorizonProfile.SAMPLE_COUNT * 4, bytes.size)
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

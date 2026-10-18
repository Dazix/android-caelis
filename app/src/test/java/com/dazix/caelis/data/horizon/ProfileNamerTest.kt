package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.LandmarkPeak
import com.dazix.caelis.domain.horizon.LandmarkTileCodec
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.cos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The decisions that keep peak names from ever costing a skyline: what gets saved when the names
 * could not be had, and when a named profile may replace the one on screen.
 */
class ProfileNamerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = DemTileKey.containing(50.0, 15.0)

    private val profile = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { i ->
            val delta = kotlin.math.abs(HorizonProfile.azimuthForIndex(i) - 90.0)
            if (delta <= 1.0) (4.5265 - 5.5265 * delta * 0.5).toFloat() else -1f
        },
        observer = GeoLocation(50.0, 15.0),
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
    )

    private val summit = LandmarkPeak(
        latitude = 50.0,
        longitude = 15.0 + 10_000.0 / (111_194.93 * cos(Math.toRadians(50.0))),
        elevationMeters = 1000,
        name = "Summit",
        nameCs = "Vrchol",
        nameEn = "",
    )

    private fun tileBytes(peaks: List<LandmarkPeak>) =
        ByteArrayOutputStream().also { LandmarkTileCodec.write(it, peaks) }.toByteArray()

    private class Downloader(private val answer: suspend (OutputStream) -> Boolean) : DemTileDownloader({ true }) {
        override suspend fun downloadLandmarks(key: DemTileKey, out: OutputStream) = answer(out)
    }

    /** A namer wired to in-memory stand-ins for the repository's disk and state. */
    private inner class Harness(answer: suspend (OutputStream) -> Boolean) {
        val stored = ArrayList<HorizonProfile>()
        var shown: HorizonProfile? = profile
        val published = ArrayList<HorizonProfile>()

        val namer = ProfileNamer(
            source = LandmarkSource(LandmarkTileCache(File(folder.root, "landmarks")), Downloader(answer), { true }),
            store = { stored += it },
            current = { shown },
            publish = { published += it; shown = it },
        )
    }

    @Test
    fun `a network failure saves nothing and publishes nothing - the profile stays as it was`() = runBlocking {
        val harness = Harness { throw IOException("no signal") }

        harness.namer.name(profile, listOf(key))

        assertTrue("no write for a profile that gained nothing", harness.stored.isEmpty())
        assertTrue(harness.published.isEmpty())
        assertSame(profile, harness.shown)
        assertFalse("still unresolved, so the next request tries again", profile.landmarksResolved)
    }

    @Test
    fun `a 404 everywhere saves nothing and leaves the profile to be retried`() = runBlocking {
        val harness = Harness { false }

        harness.namer.name(profile, listOf(key))

        assertTrue(harness.stored.isEmpty())
        assertTrue(harness.published.isEmpty())
    }

    @Test
    fun `names that arrive are stored with the profile and published`() = runBlocking {
        val harness = Harness { out -> out.write(tileBytes(listOf(summit))); true }

        harness.namer.name(profile, listOf(key))

        val named = harness.stored.single()
        assertEquals(listOf("Summit"), named.landmarks.map { it.name })
        assertTrue(named.landmarksResolved)
        assertSame(named, harness.shown)
        // The skyline itself is what was already there.
        assertEquals(profile.observer, named.observer)
        assertEquals(profile.coveredSamples, named.coveredSamples)
    }

    @Test
    fun `a covered tile with no visible peak is stored as final`() = runBlocking {
        val harness = Harness { out -> out.write(tileBytes(emptyList())); true }

        harness.namer.name(profile, listOf(key))

        val named = harness.stored.single()
        assertTrue(named.landmarks.isEmpty())
        assertTrue("empty and final: not asked for again", named.landmarksResolved)
    }

    @Test
    fun `a profile replaced meanwhile is stored but not put back on screen`() = runBlocking {
        val newer = profile.withLandmarks(emptyList(), resolved = false)
        val harness = Harness { out -> out.write(tileBytes(listOf(summit))); true }
        harness.shown = newer

        harness.namer.name(profile, listOf(key))

        assertEquals(1, harness.stored.size)
        assertTrue("the newer request's profile stays", harness.published.isEmpty())
        assertSame(newer, harness.shown)
    }

    private val knownLandmark = com.dazix.caelis.domain.horizon.HorizonLandmark(
        name = "Known", nameCs = "", nameEn = "", elevationMeters = 900,
        distanceMeters = 8_000.0, azimuthDegrees = 90.0, apparentElevationDegrees = 3.0,
    )

    @Test
    fun `a partial answer never takes names away from a profile that already has them`() = runBlocking {
        val named = profile.withLandmarks(listOf(knownLandmark), resolved = false)
        // One tile is covered but has no visible peak, the other cannot be fetched: partial and empty.
        val harness = Harness { throw IOException("no signal") }
        harness.shown = named

        harness.namer.name(named, listOf(key))

        assertTrue("no rewrite that would drop the stored names", harness.stored.isEmpty())
        assertTrue(harness.published.isEmpty())
        assertSame(named, harness.shown)
    }

    @Test
    fun `a partial answer with fewer names than the profile has is not written`() = runBlocking {
        val two = profile.withLandmarks(listOf(knownLandmark, knownLandmark.copy(name = "Other")), resolved = false)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val harness = Harness { out ->
            if (calls.incrementAndGet() > 1) throw DemTileDownloader.ServerException(503)
            out.write(tileBytes(listOf(summit)))
            true
        }
        harness.shown = two

        // One tile answers, the other cannot: unresolved, at most one name against the stored two.
        harness.namer.name(two, listOf(key, DemTileKey(50, 16)))

        assertTrue(harness.stored.isEmpty())
        assertSame(two, harness.shown)
    }

    @Test
    fun `an identical final answer is not written again`() = runBlocking {
        val harness = Harness { out -> out.write(tileBytes(emptyList())); true }
        val final = profile.withLandmarks(emptyList(), resolved = true)
        harness.shown = final

        harness.namer.name(final, listOf(key))

        assertTrue(harness.stored.isEmpty())
    }

    @Test
    fun `a profile shown unnamed while offline is named by a later attempt at the same spot`() = runBlocking {
        var online = false
        val harness = Harness { out ->
            if (!online) throw IOException("no signal")
            out.write(tileBytes(listOf(summit)))
            true
        }

        harness.namer.name(profile, listOf(key))
        assertTrue("offline: still shown without names", harness.shown!!.landmarks.isEmpty())
        assertTrue(
            "unresolved, so a repeated request is to try again",
            ProfileNamer.needsNames(harness.shown, running = false, sinceLastAttemptMs = ProfileNamer.RETRY_INTERVAL_MS),
        )

        online = true
        harness.namer.name(harness.shown!!, listOf(key))

        assertEquals(listOf("Summit"), harness.shown!!.landmarks.map { it.name })
        assertTrue(harness.shown!!.landmarksResolved)
        assertFalse(
            "named and final: never asked again",
            ProfileNamer.needsNames(harness.shown, running = false, sinceLastAttemptMs = null),
        )
    }

    @Test
    fun `a retry waits for a running naming job and for the interval`() {
        assertTrue(ProfileNamer.needsNames(profile, running = false, sinceLastAttemptMs = null))
        assertFalse("one already running", ProfileNamer.needsNames(profile, running = true, sinceLastAttemptMs = null))
        assertFalse(
            "too soon after the last attempt",
            ProfileNamer.needsNames(profile, running = false, sinceLastAttemptMs = ProfileNamer.RETRY_INTERVAL_MS - 1),
        )
        assertFalse("nothing shown", ProfileNamer.needsNames(null, running = false, sinceLastAttemptMs = null))
        assertFalse(
            "final already",
            ProfileNamer.needsNames(profile.withLandmarks(emptyList(), resolved = true), running = false, sinceLastAttemptMs = null),
        )
    }

    @Test
    fun `nothing being shown means nothing is published`() = runBlocking {
        val harness = Harness { out -> out.write(tileBytes(listOf(summit))); true }
        harness.shown = null

        harness.namer.name(profile, listOf(key))

        assertNull(harness.shown)
        assertTrue(harness.published.isEmpty())
    }
}

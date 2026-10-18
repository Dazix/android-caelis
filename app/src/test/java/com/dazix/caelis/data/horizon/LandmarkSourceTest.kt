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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The failure paths of the peak names. Every one of them has to end in a profile that is drawn
 * without names, never in a profile that is not drawn — which is what [LandmarkSource] returning
 * instead of throwing is for.
 */
class LandmarkSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val observer = GeoLocation(50.0, 15.0)
    private val key = DemTileKey.containing(50.0, 15.0)

    /** A ridge at 90 degrees, tall enough that a 1000 m summit 10 km east stands on it (see LandmarkVisibilityTest). */
    private val profile = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { i ->
            val delta = kotlin.math.abs(HorizonProfile.azimuthForIndex(i) - 90.0)
            if (delta <= 1.0) (4.5265 - 5.5265 * delta * 0.5).toFloat() else -1f
        },
        observer = observer,
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

    /** Stands in for the network; refuses like the real one does when consent is off. */
    private class FakeDownloader(
        private val consentGiven: Boolean = true,
        private val answer: suspend (DemTileKey, OutputStream) -> Boolean,
    ) : DemTileDownloader({ consentGiven }) {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        val calls: Int get() = counter.get()

        override suspend fun downloadLandmarks(key: DemTileKey, out: OutputStream): Boolean {
            counter.incrementAndGet()
            if (!consentGiven) throw NotPermittedException()
            return answer(key, out)
        }
    }

    private fun cache() = LandmarkTileCache(File(folder.root, "landmarks"))

    private fun source(
        downloader: DemTileDownloader,
        mayDownload: Boolean = true,
        cache: LandmarkTileCache = cache(),
    ) = LandmarkSource(cache, downloader, mayDownload = { mayDownload })

    @Test
    fun `a downloaded tile names the summit on the ridge and is cached`() = runBlocking {
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(listOf(summit))); true }
        val cache = cache()

        val result = source(downloader, cache = cache).resolve(profile, listOf(key))

        assertTrue(result.resolved)
        assertEquals(listOf("Summit"), result.landmarks.map { it.name })
        assertEquals(1, downloader.calls)
        assertTrue(File(folder.root, "landmarks/${key.name}.peaks").isFile)

        // The second look is answered from disk: this is what makes it work in a valley with no signal.
        val offline = FakeDownloader { _, _ -> throw IOException("no signal") }
        val again = source(offline, mayDownload = false, cache = cache).resolve(profile, listOf(key))
        assertTrue(again.resolved)
        assertEquals(listOf("Summit"), again.landmarks.map { it.name })
        assertEquals(0, offline.calls)
    }

    @Test
    fun `every tile a 404 stays unresolved - and is asked once per process`() = runBlocking {
        val downloader = FakeDownloader { _, _ -> false }
        val source = source(downloader)

        val first = source.resolve(profile, listOf(key))
        val second = source.resolve(profile, listOf(key))

        // Not final: the data repository may not be published yet, and a profile stored as "resolved"
        // would then never be named once it is.
        assertFalse("nothing real answered, so it is not final", first.resolved)
        assertTrue(first.landmarks.isEmpty())
        assertFalse(second.resolved)
        assertEquals("the retry is answered from memory, not the network", 1, downloader.calls)
        assertFalse(File(folder.root, "landmarks/${key.name}.peaks").exists())
    }

    @Test
    fun `a covered tile next to an uncovered one is used but not final`() = runBlocking {
        val uncovered = DemTileKey(50, 16)
        val downloader = FakeDownloader { requested, out ->
            if (requested == uncovered) return@FakeDownloader false
            out.write(tileBytes(listOf(summit)))
            true
        }
        val source = source(downloader)

        val result = source.resolve(profile, listOf(uncovered, key))
        val again = source.resolve(profile, listOf(uncovered, key))

        // At the edge of the published coverage: final would never pick up tiles published later.
        assertFalse("an uncovered tile keeps it open", result.resolved)
        assertEquals(listOf("Summit"), result.landmarks.map { it.name })
        assertFalse(again.resolved)
        assertEquals("the retry costs no request: cache and notCovered answer", 2, downloader.calls)
    }

    @Test
    fun `one corrupt tile does not stop the remaining tiles`() = runBlocking {
        val corrupt = DemTileKey(50, 14)
        val keys = listOf(corrupt) + (0 until 6).map { DemTileKey(50, 16 + it) }
        val downloader = FakeDownloader { requested, out ->
            out.write(if (requested == corrupt) tileBytes(listOf(summit)).copyOf(10) else tileBytes(emptyList()))
            true
        }

        val result = source(downloader).resolve(profile, keys)

        assertEquals("every tile was still tried", keys.size, downloader.calls)
        assertFalse("the corrupt one is missing, so not final", result.resolved)
    }

    @Test
    fun `one server error does not stop the remaining tiles`() = runBlocking {
        val failing = DemTileKey(50, 14)
        val keys = listOf(failing) + (0 until 6).map { DemTileKey(50, 16 + it) }
        val downloader = FakeDownloader { requested, out ->
            if (requested == failing) throw DemTileDownloader.ServerException(503)
            out.write(tileBytes(emptyList()))
            true
        }

        val result = source(downloader).resolve(profile, keys)

        assertEquals(keys.size, downloader.calls)
        assertFalse(result.resolved)
    }

    @Test
    fun `an unknown host counts as a lost connection`() = runBlocking {
        val downloader = FakeDownloader { _, _ -> throw java.net.UnknownHostException("raw.githubusercontent.com") }
        val keys = (0 until 8).map { DemTileKey(50, 10 + it) }

        source(downloader).resolve(profile, keys)

        assertTrue("tried ${downloader.calls} of ${keys.size}", downloader.calls <= 3)
    }

    @Test
    fun `a covered tile with no peaks is a real answer`() = runBlocking {
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(emptyList())); true }

        val result = source(downloader).resolve(profile, listOf(key))

        assertTrue(result.resolved)
        assertTrue(result.landmarks.isEmpty())
    }

    @Test
    fun `after the first network failure the other tiles are not tried`() = runBlocking {
        val downloader = FakeDownloader { _, _ -> throw IOException("no signal") }
        val keys = (0 until 8).map { DemTileKey(50, 10 + it) }

        val result = source(downloader).resolve(profile, keys)

        assertFalse(result.resolved)
        // At most the tiles in flight together when the first one failed.
        assertTrue("tried ${downloader.calls} of ${keys.size}", downloader.calls <= 3)
    }

    @Test
    fun `tiles are fetched in parallel but never more than three at once`() = runBlocking {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val downloader = FakeDownloader { _, out ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            kotlinx.coroutines.delay(30)
            running.decrementAndGet()
            out.write(tileBytes(emptyList()))
            true
        }
        val keys = (0 until 8).map { DemTileKey(50, 10 + it) }

        assertTrue(source(downloader).prefetch(keys))

        assertEquals(8, downloader.calls)
        assertTrue("never more than 3 at once, was ${peak.get()}", peak.get() <= 3)
        assertTrue("fetched in parallel, was ${peak.get()}", peak.get() >= 2)
    }

    @Test
    fun `a network failure leaves the profile unresolved without throwing`() = runBlocking {
        val result = source(FakeDownloader { _, _ -> throw IOException("timeout") }).resolve(profile, listOf(key))

        assertFalse("it has to be retried when there is a connection", result.resolved)
        assertTrue(result.landmarks.isEmpty())
    }

    @Test
    fun `a server error leaves the profile unresolved without throwing`() = runBlocking {
        val downloader = FakeDownloader { _, _ -> throw DemTileDownloader.ServerException(503) }

        val result = source(downloader).resolve(profile, listOf(key))

        assertFalse(result.resolved)
    }

    @Test
    fun `an unexpected runtime failure is contained too`() = runBlocking {
        val downloader = FakeDownloader { _, _ -> throw IllegalStateException("boom") }

        val result = source(downloader).resolve(profile, listOf(key))

        assertFalse(result.resolved)
        assertTrue(result.landmarks.isEmpty())
    }

    @Test
    fun `without consent nothing is requested and the profile stays unresolved`() = runBlocking {
        val downloader = FakeDownloader(consentGiven = false) { _, out -> out.write(tileBytes(listOf(summit))); true }

        val result = source(downloader).resolve(profile, listOf(key))

        assertFalse(result.resolved)
        assertTrue(result.landmarks.isEmpty())
        assertFalse(File(folder.root, "landmarks/${key.name}.peaks").exists())
    }

    @Test
    fun `the callers policy stops the request before it is made`() = runBlocking {
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(listOf(summit))); true }

        val result = source(downloader, mayDownload = false).resolve(profile, listOf(key))

        assertFalse(result.resolved)
        assertEquals("metered or switched off means no request at all", 0, downloader.calls)
    }

    @Test
    fun `a truncated tile is refused and leaves nothing behind`() = runBlocking {
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(listOf(summit)).copyOf(10)); true }

        val result = source(downloader).resolve(profile, listOf(key))

        assertFalse(result.resolved)
        assertTrue(result.landmarks.isEmpty())
        val leftovers = File(folder.root, "landmarks").listFiles().orEmpty().map { it.name }
        assertEquals("neither a tile nor a .part file", emptyList<String>(), leftovers)
    }

    @Test
    fun `a corrupt cached tile is dropped and fetched again`() = runBlocking {
        val cache = cache()
        File(folder.root, "landmarks").mkdirs()
        File(folder.root, "landmarks/${key.name}.peaks").writeBytes(ByteArray(7))
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(listOf(summit))); true }

        val result = source(downloader, cache = cache).resolve(profile, listOf(key))

        assertTrue(result.resolved)
        assertEquals(1, result.landmarks.size)
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `one failing tile among several leaves the others used but the result unresolved`() = runBlocking {
        val other = DemTileKey(50, 16)
        // The good tile comes from disk, so the failing one cannot race it out of the picture.
        File(folder.root, "landmarks").mkdirs()
        File(folder.root, "landmarks/${key.name}.peaks").writeBytes(tileBytes(listOf(summit)))
        val downloader = FakeDownloader { _, _ -> throw IOException("reset") }

        val result = source(downloader).resolve(profile, listOf(other, key))

        assertFalse(result.resolved)
        assertEquals(listOf("Summit"), result.landmarks.map { it.name })
    }

    @Test
    fun `cancellation is not swallowed`() {
        val downloader = FakeDownloader { _, _ -> throw CancellationException("stop") }

        try {
            runBlocking { source(downloader).resolve(profile, listOf(key)) }
            fail("a cancelled job must stop, not report a quiet failure")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `an unusable profile resolves to no names even with tiles`() = runBlocking {
        val flat = HorizonProfile(
            elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { -1f },
            observer = observer,
            observerGroundElevationMeters = 200.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 0L,
            coveredSamples = 0,
        )
        val downloader = FakeDownloader { _, out -> out.write(tileBytes(listOf(summit))); true }

        val result = source(downloader).resolve(flat, listOf(key))

        assertTrue(result.landmarks.isEmpty())
    }
}

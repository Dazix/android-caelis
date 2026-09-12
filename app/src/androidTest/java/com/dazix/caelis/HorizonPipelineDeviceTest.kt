package com.dazix.caelis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.data.horizon.DemTileCache
import com.dazix.caelis.data.horizon.HorizonProfileCodec
import com.dazix.caelis.data.horizon.NetworkAvailability
import com.dazix.caelis.domain.horizon.CompositeElevationGrid
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.HorizonProfileCalculator
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts of the terrain horizon pipeline that only exist on a device.
 *
 * The JVM suite covers the geometry against synthetic grids. What it cannot cover is the file layer:
 * the atomic rename that stops a half-finished download being mistaken for a tile, and the memory
 * mapping that lets a 26 MB grid be read without ever landing on the heap. Both are the kind of
 * thing that works in a unit test and fails on a phone, so they are held down here.
 *
 * No network: the tile is synthesised. A test that downloaded would be a test that fails when the
 * wifi does.
 */
@RunWith(AndroidJUnit4::class)
class HorizonPipelineDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = DemTileKey(50, 14)

    /** 1201 posts, the 3 arcsecond SRTM size — a real grid shape at a size a test can build. */
    private val gridSize = 1201

    private lateinit var cache: DemTileCache

    @Before
    fun setUp() {
        cache = DemTileCache(context)
        cache.clear()
    }

    @After
    fun tearDown() {
        cache.clear()
        profileDirectory().deleteRecursively()
    }

    private fun profileDirectory() = File(context.filesDir, "horizons-device-test")

    /**
     * A tile that is flat at [baseMeters] except for a wall of [wallMeters] north of the observer,
     * starting [wallDistanceMeters] away.
     */
    private fun syntheticTileBytes(
        observer: GeoLocation,
        baseMeters: Short,
        wallMeters: Short,
        wallDistanceMeters: Double,
    ): ByteArray {
        val wallLatitude = observer.latitude +
            wallDistanceMeters / HorizonProfileCalculator.EARTH_RADIUS_METERS * (180.0 / Math.PI)
        val out = ByteBuffer.allocate(gridSize * gridSize * 2).order(ByteOrder.BIG_ENDIAN)

        for (row in 0 until gridSize) {
            // Row 0 is the northernmost row of the tile.
            val latitude = key.latSouth + 1.0 - row.toDouble() / (gridSize - 1)
            val value = if (latitude >= wallLatitude) wallMeters else baseMeters
            for (col in 0 until gridSize) {
                out.putShort(value)
            }
        }
        return out.array()
    }

    @Test
    fun tileSurvivesAnAtomicStoreAndIsReadBackThroughAMemoryMapping() = runBlocking {
        val bytes = syntheticTileBytes(GeoLocation(50.5, 14.5), baseMeters = 200, wallMeters = 200, wallDistanceMeters = 0.0)

        cache.store(key) { it.write(bytes) }

        assertTrue("the tile must be in the cache after store()", cache.contains(key))
        assertEquals(
            "the stored tile must be byte-for-byte the size of the grid",
            (gridSize * gridSize * 2).toLong(),
            cache.fileFor(key).length(),
        )
        assertFalse(
            "the temporary file must not survive a successful store",
            File(cache.fileFor(key).parentFile, "${key.name}.part").exists(),
        )

        val tile = cache.open(key)
        assertNotNull("a stored tile must map", tile)
        assertEquals(gridSize, tile!!.gridSize)
        assertEquals(200.0, tile.elevationAt(50.5, 14.5)!!, 1e-6)
    }

    @Test
    fun aTruncatedTransferIsRefusedRatherThanStoredAsATile() = runBlocking {
        val truncated = ByteArray(gridSize * gridSize) // half the payload

        val error = runCatching { cache.store(key) { it.write(truncated) } }.exceptionOrNull()

        assertNotNull("a half tile must be refused", error)
        assertFalse("nothing must be left under the real name", cache.contains(key))
        assertFalse(
            "the temporary file must be cleaned up after a failure",
            File(context.cacheDir, "dem/${key.name}.part").exists(),
        )
    }

    @Test
    fun aMappedTileProducesTheSkylineTheGeometryPredicts() = runBlocking {
        val observer = GeoLocation(50.5, 14.5)
        val wallDistance = 5_000.0
        val bytes = syntheticTileBytes(observer, baseMeters = 0, wallMeters = 500, wallDistanceMeters = wallDistance)
        cache.store(key) { it.write(bytes) }

        val tile = cache.open(key)!!
        val profile = HorizonProfileCalculator.compute(
            grid = CompositeElevationGrid(listOf(tile)),
            observer = observer,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 20_000.0,
        )

        val drop = wallDistance * wallDistance / (2.0 * HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS)
        val expected = Math.toDegrees(Math.atan2(500.0 - 1.6 - drop, wallDistance))

        assertEquals("due north, into the wall", expected, profile.elevationAt(0.0), 0.1)
        assertEquals(
            "due south, open ground",
            HorizonProfileCalculator.geometricHorizonDipDegrees(1.6),
            profile.elevationAt(180.0),
            0.01,
        )
    }

    @Test
    fun aProfileRoundTripsThroughTheAppsOwnFileStorage() {
        val directory = profileDirectory().also { it.mkdirs() }
        val profile = HorizonProfile(
            elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT) { it * 0.002f - 1f },
            observer = GeoLocation(50.0755, 14.4378, altitudeMeters = 214.0),
            observerGroundElevationMeters = 208.5,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 60_000.0,
            generatedAtEpochMillis = 1_757_000_000_000L,
        )
        val file = File(directory, HorizonProfileCodec.fileName(50.0755, 14.4378, 60, 160))

        file.outputStream().use { HorizonProfileCodec.write(it, profile) }
        val restored = file.inputStream().use { HorizonProfileCodec.read(it) }

        assertNotNull("a profile written to filesDir must read back", restored)
        assertArrayEqualsWithin(profile.elevationsDegrees, restored!!.elevationsDegrees)
        assertEquals(profile.observer, restored.observer)
        assertTrue("a profile must stay small enough to keep hundreds of", file.length() < 8 * 1024)
    }

    /**
     * The download path exists only when this build declares `INTERNET`.
     *
     * Deleting that line from the manifest is a supported way to build from source without a
     * network, so this asserts what *this* build is rather than that the permission is present:
     * either answer is valid, and what matters is that the app can tell.
     */
    @Test
    fun theBuildCanReportWhetherItHasNetworkPermissionAtAll() {
        val declared = NetworkAvailability.hasInternetPermission(context)

        val manifestDeclares = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(android.Manifest.permission.INTERNET) == true

        assertEquals(
            "the runtime check must agree with the manifest it was built from",
            manifestDeclares,
            declared,
        )
    }

    /**
     * The regression this exists for, reproduced from a device: the cache cap was smaller than one
     * working set, `store()` trimmed after every tile, and the tile the observer was standing in got
     * evicted while the next one was still downloading. The ray march then found nothing within two
     * kilometres on every azimuth and produced a flat ring at the geometric dip — a wrong answer
     * that rendered exactly like a right one.
     *
     * Two things stop it: storing no longer trims, and a trim never evicts the working set.
     */
    @Test
    fun storingATileNeverEvictsTheWorkingSetItBelongsTo() = runBlocking {
        // A cap far below a single tile, so any unprotected trim would empty the folder.
        val tinyCache = DemTileCache(context, maxBytes = 1_024L)
        tinyCache.clear()

        val working = listOf(DemTileKey(50, 14), DemTileKey(50, 15))
        working.forEach { k ->
            tinyCache.store(k) { it.write(syntheticTileBytes(GeoLocation(50.5, 14.5), 100, 100, 0.0)) }
        }

        assertTrue("storing must not trim", working.all { tinyCache.contains(it) })

        tinyCache.trim(protect = working.toSet())

        assertTrue(
            "a trim must never evict a tile the current computation needs",
            working.all { tinyCache.contains(it) },
        )

        // An unprotected tile, by contrast, is exactly what the cap is for.
        val stale = DemTileKey(49, 14)
        tinyCache.store(stale) { it.write(syntheticTileBytes(GeoLocation(49.5, 14.5), 50, 50, 0.0)) }
        tinyCache.trim(protect = working.toSet())

        assertFalse("an unprotected tile must still be reclaimable", tinyCache.contains(stale))
        assertTrue("and the working set must survive that", working.all { tinyCache.contains(it) })

        tinyCache.clear()
    }

    /** The default cap has to be able to hold one 60 km working set, which is up to six tiles. */
    @Test
    fun theDefaultCacheCapFitsAWholeWorkingSet() {
        val oneArcSecondTile = 3601L * 3601L * 2L

        assertTrue(
            "cap ${DemTileCache.DEFAULT_MAX_BYTES} cannot hold six tiles of $oneArcSecondTile",
            DemTileCache.DEFAULT_MAX_BYTES >= 6 * oneArcSecondTile,
        )
    }

    @Test
    fun clearingTheCacheLeavesNoTilesBehind() = runBlocking {
        cache.store(key) { it.write(syntheticTileBytes(GeoLocation(50.5, 14.5), 100, 100, 0.0)) }
        assertTrue(cache.contains(key))
        assertTrue("the cache must report the bytes it holds", cache.totalBytes() > 0)

        cache.clear()

        assertFalse(cache.contains(key))
        assertEquals(0L, cache.totalBytes())
        assertTrue(cache.cachedKeys().isEmpty())
    }

    private fun assertArrayEqualsWithin(expected: FloatArray, actual: FloatArray) {
        assertEquals("sample count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], actual[i], 0f)
        }
    }
}

package com.dazix.caelis.ar

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.domain.solar.GeoLocation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolarLocationProviderTest {

    private lateinit var context: Application
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun `initial state has no gps fix and default location`() {
        val provider = SolarLocationProvider(context)
        assertEquals(GeoLocation.DEFAULT, provider.location.value)
        assertFalse(provider.hasGpsFix.value)
        assertEquals(120_000L, SolarLocationProvider.GPS_FIX_TIMEOUT_MS)
        assertEquals(5_000L, SolarLocationProvider.GPS_FIX_POLL_INTERVAL_MS)
    }

    @Test
    fun `onLocationChanged sets location and marks gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis()
            accuracy = 5f
        }

        provider.onLocationChanged(loc)

        assertEquals(50.087, provider.location.value.latitude, 1e-4)
        assertEquals(14.421, provider.location.value.longitude, 1e-4)
        assertEquals(250.0, provider.location.value.altitudeMeters, 1e-4)
        assertTrue(provider.hasGpsFix.value)
    }

    @Test
    fun `setManualLocation updates location and resets gps fix to false`() {
        val provider = SolarLocationProvider(context)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis()
            accuracy = 5f
        }
        provider.onLocationChanged(loc)
        assertTrue(provider.hasGpsFix.value)

        val manual = GeoLocation(48.8566, 2.3522, 35.0)
        provider.setManualLocation(manual)

        assertEquals(manual, provider.location.value)
        assertFalse(provider.hasGpsFix.value)
    }

    @Test
    fun `refreshLocation ignores stale gps location for fix flag but updates coordinates`() {
        val provider = SolarLocationProvider(context)
        val staleGps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 45.0
            longitude = 10.0
            altitude = 100.0
            time = System.currentTimeMillis() - 120_000L // 2 minutes old (> 60s limit)
            accuracy = 10f
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, staleGps)

        provider.refreshLocation()

        assertEquals(45.0, provider.location.value.latitude, 1e-4)
        assertEquals(10.0, provider.location.value.longitude, 1e-4)
        assertFalse("Stale GPS fix must not be considered a valid GPS fix", provider.hasGpsFix.value)
    }

    @Test
    fun `refreshLocation ignores inaccurate gps location for fix flag`() {
        val provider = SolarLocationProvider(context)
        val inaccurateGps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 45.0
            longitude = 10.0
            altitude = 100.0
            time = System.currentTimeMillis()
            accuracy = 500f // 500m (> 100m limit)
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, inaccurateGps)

        provider.refreshLocation()

        assertEquals(45.0, provider.location.value.latitude, 1e-4)
        assertFalse("Inaccurate GPS fix (> 100m) must not be considered a valid GPS fix", provider.hasGpsFix.value)
    }

    @Test
    fun `refreshLocation with fresh accurate gps location marks gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val freshGps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 45.0
            longitude = 10.0
            altitude = 100.0
            time = System.currentTimeMillis() - 5_000L // 5 seconds old
            accuracy = 15f // 15m
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshGps)

        provider.refreshLocation()

        assertEquals(45.0, provider.location.value.latitude, 1e-4)
        assertTrue("Fresh accurate GPS fix must set hasGpsFix to true", provider.hasGpsFix.value)
    }

    @Test
    fun `refreshLocation with network provider only does not mark gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val netLoc = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 45.0
            longitude = 10.0
            altitude = 100.0
            time = System.currentTimeMillis()
            accuracy = 20f
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, netLoc)

        provider.refreshLocation()

        assertEquals(45.0, provider.location.value.latitude, 1e-4)
        assertFalse("Network provider location must not be considered a GPS fix", provider.hasGpsFix.value)
    }

    @Test
    fun `refreshLocation with fresh accurate fused location marks gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val freshFused = Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis() - 5_000L
            accuracy = 10f
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.FUSED_PROVIDER, freshFused)

        provider.refreshLocation()

        assertEquals(50.087, provider.location.value.latitude, 1e-4)
        assertTrue("Fresh accurate fused location must set hasGpsFix to true", provider.hasGpsFix.value)
    }

    @Test
    fun `onLocationChanged with coarse network update does not mark gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val loc = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis()
            accuracy = 500f // 500m
        }

        provider.onLocationChanged(loc)

        assertEquals(50.087, provider.location.value.latitude, 1e-4)
        assertFalse("Coarse network update must not set hasGpsFix to true", provider.hasGpsFix.value)
    }

    @Test
    fun `onLocationChanged with fused provider marks gps fix as true`() {
        val provider = SolarLocationProvider(context)
        val loc = Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis()
            accuracy = 8f
        }

        provider.onLocationChanged(loc)

        assertEquals(50.087, provider.location.value.latitude, 1e-4)
        assertTrue("Accurate fused update must set hasGpsFix to true", provider.hasGpsFix.value)
    }

    @Test
    fun `awaitGpsFix returns immediately if already fixed`() = runBlocking {
        val provider = SolarLocationProvider(context)
        val freshGps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.087
            longitude = 14.421
            altitude = 250.0
            time = System.currentTimeMillis() - 2_000L
            accuracy = 10f
        }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshGps)

        val result = provider.awaitGpsFix(timeoutMs = 1000L, pollIntervalMs = 200L)
        assertTrue(result)
        assertTrue(provider.hasGpsFix.value)
    }

    @Test
    fun `awaitGpsFix returns false on timeout if no fix acquired`() = runBlocking {
        val provider = SolarLocationProvider(context)
        val result = provider.awaitGpsFix(timeoutMs = 100L, pollIntervalMs = 50L)
        assertFalse(result)
        assertFalse(provider.hasGpsFix.value)
    }

    @Test
    fun `awaitGpsFix resolves true when location update arrives via callback`() = runBlocking {
        val provider = SolarLocationProvider(context)
        val job = async {
            provider.awaitGpsFix(timeoutMs = 2000L, pollIntervalMs = 200L)
        }
        kotlinx.coroutines.delay(50L)
        provider.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.1
            longitude = 14.4
            altitude = 200.0
            time = System.currentTimeMillis()
            accuracy = 5f
        })
        val result = job.await()
        assertTrue(result)
        assertTrue(provider.hasGpsFix.value)
    }

    /** No public fused provider on Android 11: GPS alone has to carry the fix there. */
    @Test
    @Config(sdk = [30])
    fun `android 11 takes a gps fix without the fused provider`() {
        val provider = SolarLocationProvider(context)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 50.087
                longitude = 14.421
                time = System.currentTimeMillis() - 5_000L
                accuracy = 8f
            },
        )

        provider.refreshLocation()
        provider.startUpdates()

        assertEquals(50.087, provider.location.value.latitude, 1e-4)
        assertTrue(provider.hasGpsFix.value)
        provider.stopUpdates()
    }
}

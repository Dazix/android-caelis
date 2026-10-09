package com.dazix.caelis.ar

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides observer geographic coordinates from Android's LocationManager.
 *
 * Degrades gracefully to [GeoLocation.DEFAULT] if location permissions are denied or GPS is unavailable.
 */
class SolarLocationProvider(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val locationState = MutableStateFlow(GeoLocation.DEFAULT)
    val location: StateFlow<GeoLocation> = locationState.asStateFlow()

    private val hasGpsFixState = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = hasGpsFixState.asStateFlow()

    private var isUpdating = false

    fun refreshLocation() {
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = locationManager
        if ((!hasFine && !hasCoarse) || lm == null) return

        try {
            val providers = listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            val known = providers.mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }
            // A qualifying fix wins over a newer coarse one: picking only the newest let a network
            // location from a moment later hide a usable satellite or fused fix, and everything gated
            // on a fix (the terrain horizon) then waited for a live one.
            val fix = known.filter(::qualifiesAsFix).maxByOrNull { it.time }
            val best = fix ?: known.maxByOrNull { it.time }

            if (best != null) {
                locationState.value = GeoLocation(best.latitude, best.longitude, best.altitude)
            }
            hasGpsFixState.value = fix != null
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }

    private fun qualifiesAsFix(loc: Location): Boolean {
        val isFresh = (System.currentTimeMillis() - loc.time) < FRESH_FIX_MAX_AGE_MS
        val isAccurate = loc.hasAccuracy() && loc.accuracy <= FRESH_FIX_MAX_ACCURACY_METERS
        val isSatelliteOrFused = loc.provider == LocationManager.GPS_PROVIDER || loc.provider == LocationManager.FUSED_PROVIDER
        return isFresh && isAccurate && isSatelliteOrFused
    }

    fun startUpdates() {
        refreshLocation()
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = locationManager
        if (!hasFine && !hasCoarse || lm == null || isUpdating) return

        try {
            val providersToRequest = listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )
            for (p in providersToRequest) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(
                        p,
                        1000L, // 1 second
                        0f,    // 0 meters (report every fix even when stationary)
                        this,
                    )
                    isUpdating = true
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked
        }
    }

    fun stopUpdates() {
        if (!isUpdating || locationManager == null) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        isUpdating = false
    }

    fun setManualLocation(geoLocation: GeoLocation) {
        locationState.value = geoLocation
        hasGpsFixState.value = false
    }

    override fun onLocationChanged(loc: Location) {
        locationState.value = GeoLocation(loc.latitude, loc.longitude, loc.altitude)
        val isAccurate = loc.hasAccuracy() && loc.accuracy <= FRESH_FIX_MAX_ACCURACY_METERS
        val isSatelliteOrFused = loc.provider == LocationManager.GPS_PROVIDER || loc.provider == LocationManager.FUSED_PROVIDER
        if (isAccurate && isSatelliteOrFused) {
            hasGpsFixState.value = true
        }
    }

    /**
     * Actively polls location providers every [pollIntervalMs] while listening for live satellite updates
     * until a fresh, accurate GPS fix is acquired or [timeoutMs] elapses.
     *
     * @param timeoutMs Total timeout duration (default: 120s / 2 minutes).
     * @param pollIntervalMs Interval between provider refreshes (default: 5s).
     * @return True if a GPS fix was obtained, false on timeout.
     */
    suspend fun awaitGpsFix(
        timeoutMs: Long = GPS_FIX_TIMEOUT_MS,
        pollIntervalMs: Long = GPS_FIX_POLL_INTERVAL_MS,
    ): Boolean {
        refreshLocation()
        if (hasGpsFixState.value) return true

        startUpdates()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            refreshLocation()
            if (hasGpsFixState.value) return true

            val fixed = withTimeoutOrNull(pollIntervalMs) {
                hasGpsFixState.first { it }
            } ?: false
            if (fixed) return true
        }
        return false
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        const val GPS_FIX_TIMEOUT_MS = 120_000L // 2 minutes
        const val GPS_FIX_POLL_INTERVAL_MS = 5_000L // 5 seconds
        private const val FRESH_FIX_MAX_AGE_MS = 60_000L // 1 minute
        private const val FRESH_FIX_MAX_ACCURACY_METERS = 100f
    }
}

package com.dazix.caelis.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.domain.solar.LocationFixPolicy
import com.dazix.caelis.ui.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides observer geographic coordinates from Android's LocationManager.
 *
 * Degrades gracefully to [GeoLocation.DEFAULT] if location permissions are denied or location is off.
 * A device without a GPS receiver settles for a network position ([isApproximate]).
 */
class SolarLocationProvider(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val locationState = MutableStateFlow(GeoLocation.DEFAULT)
    val location: StateFlow<GeoLocation> = locationState.asStateFlow()

    /** A position good enough for the terrain profile, from satellites where the device can receive them. */
    private val hasGpsFixState = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = hasGpsFixState.asStateFlow()

    /** The current fix came from Wi-Fi or cell positioning because this device has no satellites to wait for. */
    private val approximateState = MutableStateFlow(false)
    val isApproximate: StateFlow<Boolean> = approximateState.asStateFlow()

    private var isUpdating = false

    fun refreshLocation() {
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = locationManager
        if ((!hasFine && !hasCoarse) || lm == null) return

        try {
            val providers = listOfNotNull(
                FUSED,
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
            val satellites = satellitesAvailable()
            val fix = known.filter { qualifiesAsFix(it, satellites) }.maxByOrNull { it.time }
            val best = fix ?: known.maxByOrNull { it.time }

            if (best != null) {
                locationState.value = GeoLocation(best.latitude, best.longitude, best.altitude)
            }
            hasGpsFixState.value = fix != null
            approximateState.value = fix != null && !satellites
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }

    private fun qualifiesAsFix(loc: Location, satellites: Boolean, ageMs: Long = System.currentTimeMillis() - loc.time) =
        LocationFixPolicy.qualifies(
            provider = loc.provider,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
            ageMs = ageMs,
            satellites = satellites,
        )

    /**
     * Whether waiting for satellites can end in a fix at all. A Pixel Tablet has no GPS receiver, and a
     * phone's GPS provider can be switched off; either way the best this device will ever report is a
     * network position, and [LocationFixPolicy] accepts that instead of timing out on it.
     */
    fun satellitesAvailable(): Boolean {
        val lm = locationManager ?: return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) return false
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    /** No provider enabled means location is off in system settings; no amount of waiting changes that. */
    private fun anyProviderEnabled(): Boolean {
        val lm = locationManager ?: return false
        return listOfNotNull(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, FUSED)
            .any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    fun startUpdates() {
        refreshLocation()
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = locationManager
        if (!hasFine && !hasCoarse || lm == null || isUpdating) return

        try {
            val providersToRequest = listOfNotNull(
                FUSED,
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
        approximateState.value = false
    }

    override fun onLocationChanged(loc: Location) {
        locationState.value = GeoLocation(loc.latitude, loc.longitude, loc.altitude)
        // A callback is a live position, whatever its timestamp says.
        val satellites = satellitesAvailable()
        if (qualifiesAsFix(loc, satellites, ageMs = 0L)) {
            approximateState.value = !satellites
            hasGpsFixState.value = true
        }
    }

    /**
     * Actively polls location providers every [pollIntervalMs] while listening for live updates until a
     * position [LocationFixPolicy] accepts arrives or [timeoutMs] elapses.
     *
     * @param timeoutMs Total timeout; by default two minutes where satellites can be received and
     *   15 seconds where they cannot ([LocationFixPolicy.timeoutMs]).
     * @param pollIntervalMs Interval between provider refreshes (default: 5s).
     * @return True if a fix was obtained, false on timeout or with location switched off.
     */
    suspend fun awaitGpsFix(
        timeoutMs: Long? = null,
        pollIntervalMs: Long = GPS_FIX_POLL_INTERVAL_MS,
    ): Boolean {
        refreshLocation()
        if (hasGpsFixState.value) return true
        if (!anyProviderEnabled()) return false
        val timeoutMs = timeoutMs ?: LocationFixPolicy.timeoutMs(satellitesAvailable())

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
        const val GPS_FIX_POLL_INTERVAL_MS = 5_000L // 5 seconds

        /**
         * The platform's fused provider is public from Android 12. On Android 11 it is not part of the
         * API, so the app asks GPS and network there, which is what fused would have combined anyway.
         */
        private val FUSED: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null
    }
}

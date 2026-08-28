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
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            val best = providers.mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }.maxByOrNull { it.time }

            if (best != null) {
                locationState.value = GeoLocation(best.latitude, best.longitude, best.altitude)
                hasGpsFixState.value = true
            }
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }

    fun startUpdates() {
        refreshLocation()
        val hasFine = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse || locationManager == null || isUpdating) return

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // 5 seconds
                    10f,   // 10 meters
                    this,
                )
                isUpdating = true
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    this,
                )
                isUpdating = true
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
        hasGpsFixState.value = true
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

package com.dazix.caelis.domain.location

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.time.ZoneId
import kotlin.math.abs

/**
 * Where an AR screen computes the sky from, and what that allows.
 *
 * @property location the observer the Sun, Moon, stars and terrain are computed for.
 * @property place the saved place, or null for the phone's own position.
 * @property zone the zone every time is shown in: the place's own, else the phone's.
 * @property canRequestProfile whether a terrain profile may be asked for now. GPS needs a fix (the
 *   default Prague fallback must never be turned into a skyline); a saved place is exact by definition.
 * @property autoAlignAllowed matching the camera against the terrain is only meaningful where the
 *   phone actually is.
 * @property cameraWanted the camera picture shows where the phone is; behind a virtual place it
 *   would put this street under that skyline, so a neutral backdrop replaces it.
 */
data class Observer(
    val location: GeoLocation,
    val place: SavedPlace?,
    val zone: ZoneId,
    val canRequestProfile: Boolean,
    val autoAlignAllowed: Boolean,
    val cameraWanted: Boolean,
) {
    val isVirtual: Boolean get() = place != null
}

/**
 * Pure decision of the [Observer]. Magnetic declination is deliberately not in it: it corrects the
 * physical compass where the phone is, so it stays on the GPS position under a virtual place.
 */
object ObserverPolicy {

    fun observe(gps: GeoLocation, hasGpsFix: Boolean, place: SavedPlace?, deviceZone: ZoneId): Observer =
        if (place != null) {
            Observer(
                location = place.toGeoLocation(),
                place = place,
                zone = place.zone(),
                canRequestProfile = true,
                autoAlignAllowed = false,
                cameraWanted = false,
            )
        } else {
            Observer(
                location = gps,
                place = null,
                zone = deviceZone,
                canRequestProfile = hasGpsFix,
                autoAlignAllowed = true,
                cameraWanted = true,
            )
        }

    /**
     * Whether a stored [profile] was computed for [location], to the 0.001° the profile store rounds
     * to. `HorizonRepository` holds one profile for the whole app, so the screen not in front may
     * still see the other screen's place for a moment; drawing it would put a foreign skyline here.
     */
    fun profileBelongsTo(profile: HorizonProfile, location: GeoLocation): Boolean =
        abs(round3(profile.observer.latitude) - round3(location.latitude)) < SAME_SPOT_DEG &&
            abs(round3(profile.observer.longitude) - round3(location.longitude)) < SAME_SPOT_DEG

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    private const val SAME_SPOT_DEG = 0.0015
}

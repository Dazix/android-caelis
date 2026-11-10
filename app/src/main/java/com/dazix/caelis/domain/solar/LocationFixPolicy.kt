package com.dazix.caelis.domain.solar

/**
 * Which reported position is good enough to compute a terrain profile from, and how long to wait for one.
 *
 * A device that can receive satellites waits for them: a network position is routinely a few hundred
 * metres out, which moves a near ridge visibly. A device that cannot — a tablet with no GPS receiver, or
 * a phone whose GPS provider is off — never gets anything better than Wi-Fi or cell positioning, and
 * waiting two minutes for satellites there only ended in "could not determine location" with a usable
 * position already in hand. Provider names are the `LocationManager` constants, spelled out so this
 * stays free of Android imports.
 */
object LocationFixPolicy {

    const val SATELLITE_MAX_AGE_MS = 60_000L
    const val SATELLITE_MAX_ACCURACY_METERS = 100f
    const val SATELLITE_TIMEOUT_MS = 120_000L

    /** A tablet does not wander off; a network position from a few minutes ago is still where it is. */
    const val NETWORK_MAX_AGE_MS = 10 * 60_000L

    /** Wi-Fi positioning is tens of metres; a bare cell position of several kilometres is not a skyline. */
    const val NETWORK_MAX_ACCURACY_METERS = 500f

    /** Network positions arrive in seconds or not at all; there is nothing to wait for after that. */
    const val NETWORK_TIMEOUT_MS = 15_000L

    private const val GPS = "gps"
    private const val FUSED = "fused"
    private const val NETWORK = "network"

    /**
     * @param satellites whether the device has a GPS receiver and its provider is enabled.
     * @param accuracyMeters null when the position reports no accuracy, which never qualifies.
     */
    fun qualifies(provider: String?, accuracyMeters: Float?, ageMs: Long, satellites: Boolean): Boolean {
        // A negative age is a position stamped a moment ahead of this clock, which is still fresh.
        if (accuracyMeters == null) return false
        return if (satellites) {
            (provider == GPS || provider == FUSED) &&
                accuracyMeters <= SATELLITE_MAX_ACCURACY_METERS &&
                ageMs < SATELLITE_MAX_AGE_MS
        } else {
            (provider == GPS || provider == FUSED || provider == NETWORK) &&
                accuracyMeters <= NETWORK_MAX_ACCURACY_METERS &&
                ageMs < NETWORK_MAX_AGE_MS
        }
    }

    /**
     * Whether a live update should replace the position currently held.
     *
     * Updates come from GPS, fused and network at once, every second. Taking each one put a network
     * position a few hundred metres off in place of a GPS fix every so often: the terrain profile no
     * longer belonged to the observer for that second and the skyline blinked out (on a viewpoint,
     * standing still). A coarse update is only taken while there is no fix it would displace — none
     * yet, or one too old to [qualifies] any more.
     *
     * @param heldAgeMs how long ago the held fix was received.
     */
    fun replacesHeld(newQualifies: Boolean, heldQualifies: Boolean, heldAgeMs: Long, satellites: Boolean): Boolean {
        if (newQualifies || !heldQualifies) return true
        val maxAge = if (satellites) SATELLITE_MAX_AGE_MS else NETWORK_MAX_AGE_MS
        return heldAgeMs >= maxAge
    }

    fun timeoutMs(satellites: Boolean): Long = if (satellites) SATELLITE_TIMEOUT_MS else NETWORK_TIMEOUT_MS
}

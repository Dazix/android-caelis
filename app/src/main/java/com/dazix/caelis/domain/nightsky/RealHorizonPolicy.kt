package com.dazix.caelis.domain.nightsky

/**
 * Whether Night Sky draws the terrain skyline instead of the stylised landscape.
 *
 * The skyline is wanted as soon as the feature is on, not only once a profile exists: until the
 * profile lands it is a flat ring at 0° that then rises into the real ridge (see `HorizonMorph`),
 * so opening the screen does not flash the stylised tree line first and swap it a second later.
 * Only when the profile is known not to be coming — the computation failed, or tiles have to be
 * imported by hand — does the stylised landscape take over, rather than a flat ring that would sit
 * there indefinitely looking like a measured horizon.
 */
object RealHorizonPolicy {

    fun isWanted(
        featureEnabled: Boolean,
        realHorizonInNightSky: Boolean,
        hasProfile: Boolean,
        profileUnavailable: Boolean,
    ): Boolean = featureEnabled && realHorizonInNightSky && (hasProfile || !profileUnavailable)
}

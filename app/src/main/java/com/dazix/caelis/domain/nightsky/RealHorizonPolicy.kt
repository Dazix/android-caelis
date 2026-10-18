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

    /**
     * Whether the stylised landscape is drawn: when its own switch is on, and as the stand-in for a
     * real skyline that is not coming. The two switches are exclusive in the layer sheet, so the
     * landscape's own switch is off whenever the real one is on — read alone, it left a user who
     * declined the download, or whose tiles are missing, with no ground at all.
     */
    fun showsStylisedLandscape(
        landscapeSwitch: Boolean,
        featureEnabled: Boolean,
        realHorizonInNightSky: Boolean,
        hasProfile: Boolean,
        profileUnavailable: Boolean,
    ): Boolean = landscapeSwitch || (
        featureEnabled && realHorizonInNightSky &&
            !isWanted(featureEnabled, realHorizonInNightSky, hasProfile, profileUnavailable)
        )
}

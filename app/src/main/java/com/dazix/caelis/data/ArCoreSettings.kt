package com.dazix.caelis.data

/**
 * What the user has agreed to about Google's AR components. Both default to **off**, and a build or an
 * install that never touches them never reaches Google: [com.dazix.caelis.ar.pose.ArCoreSessionController]
 * will not even ask the platform whether ARCore is present until [arCoreConsent] is true.
 *
 * ARCore is not part of this app. It is Google Play Services for AR, a separate package that the
 * `com.google.ar:core` library talks to, and it reports to Google on its own terms (device
 * identifiers, usage and diagnostics while a session runs). The app cannot inspect or restrict that,
 * which is exactly why the switch exists and why the answer is read again on every use rather than
 * remembered by whoever asked first.
 */
data class ArCoreSettings(
    /** Use ARCore for the AR pose. Off means the phone's own sensors alone, and nothing is sent. */
    val arCoreConsent: Boolean = false,
    /**
     * Use the Geospatial API as well. This additionally sends camera-derived data and the location to
     * Google's Visual Positioning Service, so it is a separate decision rather than a sub-option of the
     * first, and it can never be on while [arCoreConsent] is off.
     */
    val geospatialConsent: Boolean = false,
) {

    /**
     * The only way a stored pair is ever interpreted. A geospatial "yes" next to an ARCore "no" is a
     * state the UI cannot produce, but a hand-edited backup or an older build could leave it behind,
     * and the safe reading of an inconsistent answer is the narrower one.
     */
    fun normalized(): ArCoreSettings =
        if (arCoreConsent) this else copy(geospatialConsent = false)

    /** Turning ARCore off takes geospatial down with it: nothing is left running without its base. */
    fun withArCoreConsent(granted: Boolean): ArCoreSettings =
        copy(arCoreConsent = granted).normalized()

    /** Geospatial can only be granted on top of ARCore; asking for it without is a no-op, not a grant. */
    fun withGeospatialConsent(granted: Boolean): ArCoreSettings =
        copy(geospatialConsent = granted).normalized()
}

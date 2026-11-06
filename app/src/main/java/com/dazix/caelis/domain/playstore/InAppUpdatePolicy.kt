package com.dazix.caelis.domain.playstore

/** What the Play Store says about a newer version, reduced to what the decision needs. */
enum class UpdateAvailability { AVAILABLE, NOT_AVAILABLE, IN_PROGRESS, UNKNOWN }

/** Where a flexible update that was already accepted stands. */
enum class UpdateInstall { NONE, PENDING, DOWNLOADING, DOWNLOADED, INSTALLING, INSTALLED, FAILED, CANCELED }

data class UpdateCheck(
    val availability: UpdateAvailability,
    val install: UpdateInstall,
    /** The version code Play would install; meaningful only while [availability] is AVAILABLE. */
    val availableVersionCode: Int,
    val flexibleAllowed: Boolean,
)

sealed interface UpdateAction {
    data object None : UpdateAction

    /** Put up Play's own update sheet for this version. */
    data class Offer(val versionCode: Int) : UpdateAction

    /** The update is downloaded and waits for the app to restart into it. */
    data object OfferRestart : UpdateAction
}

/**
 * When the Play Store's update sheet is shown.
 *
 * Every newer version is offered, once: a version the user has already been offered is not offered
 * again, at the next start or ever, because Play itself would not hold back and a sheet at every
 * start is how an app gets uninstalled. The next version is offered as usual.
 *
 * Never while a session is armed or shooting. Installing a flexible update restarts the process,
 * and the process is where the accessibility service runs the session; a "Restart" pressed in the
 * middle of a night's time-lapse would end it. The sheet itself waits too: whoever opens the app
 * during a session came to watch or stop it, not to read about an update.
 */
object InAppUpdatePolicy {
    fun decide(
        check: UpdateCheck?,
        lastOfferedVersionCode: Int?,
        sessionActive: Boolean,
    ): UpdateAction {
        // No answer (not installed from Play, no Play Store, an error) is no update.
        if (check == null || sessionActive) return UpdateAction.None
        if (check.install == UpdateInstall.DOWNLOADED) return UpdateAction.OfferRestart
        val offerable = check.availability == UpdateAvailability.AVAILABLE &&
            check.flexibleAllowed &&
            check.availableVersionCode > (lastOfferedVersionCode ?: 0)
        return if (offerable) UpdateAction.Offer(check.availableVersionCode) else UpdateAction.None
    }
}

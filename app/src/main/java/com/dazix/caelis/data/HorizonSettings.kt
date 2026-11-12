package com.dazix.caelis.data

import com.dazix.caelis.domain.horizon.DownloadMode

/**
 * Everything the terrain horizon layer is configured by. All opt-in: a default-constructed instance
 * behaves exactly like a build that never had the feature, draws nothing extra and opens no socket.
 */
data class HorizonSettings(
    /**
     * Draw the real terrain skyline in the AR views, and compute rise/set times against it.
     *
     * Off by default because the layer is useless until a profile exists for where you are standing,
     * and computing one costs either a download or a file the user has to go and fetch.
     */
    val enabled: Boolean = false,
    /**
     * Whether the user has agreed to the app fetching elevation tiles over the network.
     *
     * This is the entire network gate. [com.dazix.caelis.data.horizon.DemTileDownloader] refuses to
     * open a connection while it is false, which is what makes the promise auditable now that the
     * `INTERNET` permission itself can no longer be withheld by the system.
     *
     * `null` means the question has not been asked yet, and is what makes the consent prompt appear
     * exactly once: a user who said no is not asked again on the next launch.
     */
    val networkConsent: Boolean? = null,
    /**
     * With consent given: fetch the tiles a position needs without asking, up to [confirmAboveMb]
     * per run (true), or only when the user taps Download (false, "on demand").
     *
     * True by default so that everyone who consented before the mode existed keeps what they agreed
     * to, now with the limit in front of large runs.
     */
    val autoDownload: Boolean = true,
    /**
     * Above this many megabytes a run waits for a tap even in automatic mode. One of
     * [CONFIRM_LIMIT_CHOICES_MB]. Tiles weigh 1–19 MB each and a run can need nine of them, so an
     * unattended 100+ MB download on a mobile plan was possible before this existed.
     */
    val confirmAboveMb: Int = DEFAULT_CONFIRM_ABOVE_MB,
    /**
     * Let the app work out how far to search from the terrain around the observer.
     *
     * On by default, because the honest answer varies by two orders of magnitude — a valley floor
     * needs a couple of kilometres, a plain facing a distant range needs eighty — and the cost of
     * guessing high is up to nine more tiles at 26 MB each, fetched to discover they changed nothing.
     * The tile the observer is standing in is downloaded regardless and covers a whole degree, so the
     * estimate itself is free. See [com.dazix.caelis.domain.horizon.HorizonRangeEstimator].
     */
    val autoRange: Boolean = true,
    /**
     * How far out terrain is searched, in kilometres, when [autoRange] is off.
     *
     * 60 km is a deliberate compromise. Further would catch the Alps from Bohemia on the rare day
     * the air allows it, but each extra degree of latitude or longitude is another tile to fetch,
     * and beyond about 80 km the atmosphere hides the terrain far more reliably than geometry does.
     */
    val maxRangeKm: Int = DEFAULT_MAX_RANGE_KM,
    /**
     * Height of the eye above the ground, in centimetres. Never stored: it describes where the user is
     * standing right now (a lookout tower, a balcony), and a height left over from yesterday's tower
     * would skew today's horizon without a word. [com.dazix.caelis.data.horizon.HorizonRepository]
     * owns the value for the process and writes it into every request; whatever a caller passes here
     * is replaced there.
     */
    val eyeHeightCm: Int = DEFAULT_EYE_HEIGHT_CM,
    /** Draw the part of a trajectory that is behind the terrain dimmed rather than solid. */
    val shadeOccludedTrajectory: Boolean = true,
    /** Only fetch tiles on an unmetered connection. A tile can be 19 MB in mountainous terrain. */
    val wifiOnly: Boolean = false,
    /** Use the real profile instead of the stylised landscape texture in Night Sky AR. */
    val realHorizonInNightSky: Boolean = false,
    /**
     * Print peak names on the skyline in Sun AR. On by default: the layer only draws once the terrain
     * is on and a profile carries names, so it adds nothing for anyone who has not opted in to terrain.
     */
    val peakNamesInSunAr: Boolean = true,
    /** The same for Night Sky AR, where the skyline is the real one only if [realHorizonInNightSky]. */
    val peakNamesInNightSky: Boolean = true,
) {

    val maxRangeMeters: Double get() = maxRangeKm * 1000.0

    val eyeHeightMeters: Double get() = eyeHeightCm / 100.0

    /** True once the user has answered the network question, either way. */
    val networkConsentAnswered: Boolean get() = networkConsent != null

    /** True only when the user actively agreed. A missing answer is not consent. */
    val networkDownloadAllowed: Boolean get() = networkConsent == true

    /** The mode the Settings screen shows; an unanswered consent fetches nothing, like a refused one. */
    val downloadMode: DownloadMode
        get() = when {
            !networkDownloadAllowed -> DownloadMode.NEVER
            autoDownload -> DownloadMode.AUTOMATIC
            else -> DownloadMode.ON_DEMAND
        }

    /**
     * [confirmAboveMb] in bytes. Decimal megabytes, as Android's `Formatter.formatShortFileSize`
     * prints them, so "50 MB" in Settings and "50 MB" on the offer are the same amount.
     */
    val confirmAboveBytes: Long get() = confirmAboveMb * 1_000_000L

    companion object {
        const val DEFAULT_MAX_RANGE_KM: Int = 60
        const val DEFAULT_EYE_HEIGHT_CM: Int = 160

        /** Range values offered in the UI, in kilometres. An automatic estimate rounds up to one. */
        val RANGE_CHOICES_KM: List<Int> = listOf(20, 40, 60, 100)

        /** How far the automatic estimate is allowed to look before it has downloaded anything. */
        const val AUTO_PROBE_RANGE_KM: Int = 40

        /**
         * Heights above the terrain offered on the AR screens. Below a few metres the choice changes
         * nothing visible (30 cm moves a ridge 1 km away by 0.017°, under a pixel); it matters on a
         * roof or a tower, where the steps are coarse on purpose.
         */
        val HEIGHT_CHOICES_CM: List<Int> = listOf(160, 300, 500, 1000, 1500, 2000, 3000, 5000)

        const val DEFAULT_CONFIRM_ABOVE_MB: Int = 50

        /** Confirmation limits offered in Settings, in megabytes. */
        val CONFIRM_LIMIT_CHOICES_MB: List<Int> = listOf(10, 25, 50, 100, 250)
    }
}

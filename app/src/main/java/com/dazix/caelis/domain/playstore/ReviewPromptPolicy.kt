package com.dazix.caelis.domain.playstore

/**
 * How much the app has been used, counted in days rather than starts: five starts in one evening of
 * setting up a time-lapse are one day of use, not five.
 */
data class ReviewHistory(
    val engagedDays: Int = 0,
    val lastOpenEpochDay: Long? = null,
    val lastRequestedEpochDay: Long? = null,
)

/** Everything the two Play sheets remember between starts. */
data class PlayPromptState(
    val lastOfferedUpdateVersion: Int?,
    val review: ReviewHistory,
)

/**
 * When the app asks Play for its review sheet.
 *
 * Play decides on its own whether the sheet actually appears (a quota the app cannot read) and never
 * says whether it did, so asking can only be rationed from this side, never confirmed. Asking means
 * the user has used the app on [MIN_ENGAGED_DAYS] separate days and had it for [MIN_DAYS_INSTALLED]
 * days, and it is asked again at most every [MIN_DAYS_BETWEEN_REQUESTS] days.
 *
 * Only on the home screen, never during a session and never on a resume that already put up the
 * update sheet: the sheet covers whatever is open, and over an AR screen or a running time-lapse it
 * is an interruption, not a question. A button cannot open this sheet reliably for the same quota
 * reason, so Settings links to the store listing instead.
 */
object ReviewPromptPolicy {
    const val MIN_ENGAGED_DAYS = 3
    const val MIN_DAYS_INSTALLED = 5L
    const val MIN_DAYS_BETWEEN_REQUESTS = 120L

    /** Counts [today] as a day of use if it is not the day already counted. */
    fun recordOpen(history: ReviewHistory, today: Long): ReviewHistory =
        if (history.lastOpenEpochDay == today) {
            history
        } else {
            history.copy(engagedDays = history.engagedDays + 1, lastOpenEpochDay = today)
        }

    fun shouldRequest(
        history: ReviewHistory,
        today: Long,
        installEpochDay: Long?,
        onHomeScreen: Boolean,
        sessionActive: Boolean,
        updateOffered: Boolean,
    ): Boolean {
        if (!onHomeScreen || sessionActive || updateOffered) return false
        // Without an install date the age rule cannot be met, and a guess would be a prompt on day one.
        if (installEpochDay == null || today - installEpochDay < MIN_DAYS_INSTALLED) return false
        if (history.engagedDays < MIN_ENGAGED_DAYS) return false
        val last = history.lastRequestedEpochDay ?: return true
        // A clock set back must not reopen the question: only time actually passed counts.
        return today - last >= MIN_DAYS_BETWEEN_REQUESTS
    }

    fun recordRequest(history: ReviewHistory, today: Long): ReviewHistory =
        history.copy(lastRequestedEpochDay = today)
}

package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonProfile

/**
 * Puts names on a profile that is already stored and shown.
 *
 * Split out of [HorizonRepository] so the decisions that keep names from ever costing a skyline can
 * be tested without a `Context`: what is saved when nothing could be resolved, and when a named
 * profile may replace the one on screen.
 *
 * @param store persists the profile (called off the main thread by the caller's own dispatcher choice).
 * @param current what is being shown right now.
 * @param publish makes a profile the one being shown.
 */
internal class ProfileNamer(
    private val source: LandmarkSource,
    private val store: suspend (HorizonProfile) -> Unit,
    private val current: () -> HorizonProfile?,
    private val publish: (HorizonProfile) -> Unit,
) {

    /**
     * Names the peaks on [profile] out of [keys]'s tiles, stores the result with it and publishes it.
     *
     * Best effort by construction: [LandmarkSource] swallows every failure, and what it could not
     * resolve leaves the stored profile as it was, unresolved, so the next request for this spot tries
     * again. Nothing here changes the repository's state, which would flash a download notification
     * for a few kilobytes.
     */
    suspend fun name(profile: HorizonProfile, keys: List<DemTileKey>) {
        val resolution = source.resolve(profile, keys)
        // Nothing found and nothing final: there is nothing worth a write.
        if (resolution.landmarks.isEmpty() && !resolution.resolved) return
        // Rewriting a stored profile is a risk and a cost, so only when it changes something.
        if (resolution.resolved == profile.landmarksResolved && resolution.landmarks == profile.landmarks) return
        // An unresolved answer is partial (offline after an earlier fetch, a tile reclaimed). It may add
        // names but must never take away what the profile already shows; a final one replaces freely.
        if (!resolution.resolved && resolution.landmarks.size <= profile.landmarks.size) return

        val named = profile.withLandmarks(resolution.landmarks, resolution.resolved)
        store(named)
        // A newer request may have replaced the profile while the tiles were arriving; do not put this one back.
        if (current() === profile) publish(named)
    }

    companion object {
        /** Spacing of retries for one shown profile: a location fix every few seconds must not each wait out a timeout. */
        const val RETRY_INTERVAL_MS = 30_000L

        /**
         * Whether a profile that is already on screen should be named again by a repeated request.
         *
         * Only [HorizonRepository.resolve] named profiles at first, so a user who opened the screen
         * offline and later got a connection at the same spot never saw a peak name until the position
         * or settings changed: the repeated request was dropped as "nothing new". An unresolved profile
         * is therefore retried, but not while a naming job runs and not more often than
         * [RETRY_INTERVAL_MS] — a failed attempt costs a connection timeout, and the location provider
         * repeats itself every few seconds. [sinceLastAttemptMs] is null when none has been made.
         */
        fun needsNames(profile: HorizonProfile?, running: Boolean, sinceLastAttemptMs: Long?): Boolean =
            profile != null && !profile.landmarksResolved && !running &&
                (sinceLastAttemptMs == null || sinceLastAttemptMs >= RETRY_INTERVAL_MS)
    }
}

package com.dazix.caelis.data.horizon

import android.util.Log
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.LandmarkPeak
import com.dazix.caelis.domain.horizon.LandmarkVisibility
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Turns "the peak tiles around this profile" into the named peaks standing on its skyline.
 *
 * ## Nothing in here may fail a profile
 *
 * Names are decoration on a skyline that already works. Every way this can go wrong — no network, no
 * consent, a 404, a server error, a corrupt file, a full disk — ends in "not resolved yet", never in
 * an exception, so the caller can store and draw the profile it has. Only cancellation passes through,
 * because swallowing it would keep a cancelled job running.
 *
 * @param mayDownload the caller's own policy (Wi-Fi only, the download switch) on top of the consent
 *   [DemTileDownloader] checks itself; asked before every tile.
 */
class LandmarkSource(
    private val cache: LandmarkTileCache,
    private val downloader: DemTileDownloader,
    private val mayDownload: suspend () -> Boolean,
) {

    /**
     * @property resolved true only when every tile answered with data. Only then is the list final;
     *   otherwise it is "ask again later". A tile that said "not covered" keeps it unresolved on
     *   purpose, even when others were covered: a profile at the edge of the published coverage would
     *   otherwise become final and never pick up tiles published later under the pinned tag. Asking
     *   again is cheap, because [notCovered] answers for the rest of the process without a request.
     */
    data class Resolution(val landmarks: List<HorizonLandmark>, val resolved: Boolean)

    /** Tiles the server said it does not have, for this process, so a region we do not cover costs one request. */
    private val notCovered = ConcurrentHashMap.newKeySet<DemTileKey>()

    /** One tile's answer. [covered] is false for "the server has no such tile", which carries no peaks. */
    private class TileAnswer(val peaks: List<LandmarkPeak>, val covered: Boolean)

    /**
     * What one [resolve] or [prefetch] call has learnt about the network so far. After the first
     * failure the remaining tiles are not tried: with no connection each would only wait out its own
     * timeout, one after another, for the same answer.
     */
    private class Attempt {
        @Volatile
        var networkFailed = false
    }

    /** Selects the visible named peaks for [profile] out of the tiles [keys] (its search radius's covering box). */
    suspend fun resolve(profile: HorizonProfile, keys: List<DemTileKey>): Resolution {
        val answers = answerAll(keys)
        val peaks = ArrayList<LandmarkPeak>()
        for (answer in answers) if (answer != null) peaks += answer.peaks
        val resolved = answers.isNotEmpty() && answers.all { it != null && it.covered }
        val landmarks = try {
            withContext(Dispatchers.Default) { LandmarkVisibility.select(peaks, profile) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "selecting landmarks failed", e)
            return Resolution(emptyList(), resolved = false)
        }
        return Resolution(landmarks, resolved)
    }

    /** Makes sure the peak tiles of [keys] are cached, for an offline region. @return true when all answered. */
    suspend fun prefetch(keys: List<DemTileKey>): Boolean = answerAll(keys).all { it != null }

    /**
     * Every tile's answer in the order of [keys], fetched a few at a time: a region is up to a dozen
     * tiles of a few kilobytes each, and one after another each paid its own round trip.
     */
    private suspend fun answerAll(keys: List<DemTileKey>): List<TileAnswer?> {
        val attempt = Attempt()
        val slots = Semaphore(PARALLEL_TILES)
        return coroutineScope {
            keys.map { key -> async { slots.withPermit { answer(key, attempt) } } }.awaitAll()
        }
    }

    /** The answer for one tile; `null` when it could not be obtained now. */
    private suspend fun answer(key: DemTileKey, attempt: Attempt): TileAnswer? {
        try {
            withContext(Dispatchers.IO) { cache.read(key) }?.let { return TileAnswer(it, covered = true) }
            if (key in notCovered) return TileAnswer(emptyList(), covered = false)
            if (attempt.networkFailed || !mayDownload()) return null

            return when (cache.store(key) { downloader.downloadLandmarks(key, it) }) {
                LandmarkTileCache.Stored.NOT_COVERED -> {
                    notCovered += key
                    TileAnswer(emptyList(), covered = false)
                }
                LandmarkTileCache.Stored.STORED ->
                    withContext(Dispatchers.IO) { cache.read(key) }?.let { TileAnswer(it, covered = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DemTileDownloader.NotPermittedException) {
            // Consent is withdrawn for every tile alike.
            attempt.networkFailed = true
            return null
        } catch (e: LandmarkTileCache.CorruptTileException) {
            // One bad file says nothing about the connection: skip this tile, keep fetching the rest.
            Log.w(TAG, "peak tile ${key.name} is corrupt", e)
            return null
        } catch (e: DemTileDownloader.ServerException) {
            // An HTTP status error is this tile's answer, not a lost connection.
            Log.w(TAG, "peak tile ${key.name} refused with HTTP ${e.status}", e)
            return null
        } catch (e: java.io.IOException) {
            // Unknown host, refused connection, timeout, a dropped transfer: the remaining tiles would
            // each wait out the same failure.
            attempt.networkFailed = true
            Log.w(TAG, "peak tile ${key.name} unavailable", e)
            return null
        } catch (e: Exception) {
            // A security or runtime failure from the platform concerns this tile only.
            Log.w(TAG, "peak tile ${key.name} failed", e)
            return null
        }
    }

    private companion object {
        const val TAG = "LandmarkSource"

        /** Enough to hide the round trips, few enough to stay a polite client of one raw file host. */
        const val PARALLEL_TILES = 3
    }
}

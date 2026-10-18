package com.dazix.caelis.data.horizon

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.domain.horizon.CompositeElevationGrid
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.HorizonProfileCalculator
import com.dazix.caelis.domain.horizon.HorizonRangeEstimator
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.notify.TerrainNotifications
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos

/** What the terrain horizon layer is currently doing, for the UI to report honestly. */
sealed interface HorizonState {

    /** Nothing asked for yet, or the feature is off. */
    data object Idle : HorizonState

    /** Waiting for GPS location fix before resolving or downloading. */
    data object Locating : HorizonState

    /** Tiles are needed and the app cannot fetch them itself — the user has to import them. */
    data class NeedsTiles(val keys: List<DemTileKey>) : HorizonState

    /**
     * Tile [index] of [total] is on its way; [progress] and the byte counts are of that tile alone.
     * The bytes are compressed, as they cross the network; [totalBytes] is
     * [DemTileDownloader.UNKNOWN_LENGTH] until, or unless, the server announces a length.
     */
    data class Downloading(
        val key: DemTileKey,
        val index: Int,
        val total: Int,
        val progress: Float,
        val receivedBytes: Long = 0L,
        val totalBytes: Long = DemTileDownloader.UNKNOWN_LENGTH,
    ) : HorizonState

    data class Computing(val progress: Float) : HorizonState

    data class Ready(val profile: HorizonProfile) : HorizonState

    data class Failed(val reason: Reason, val cause: Throwable? = null) : HorizonState {
        enum class Reason { NETWORK, METERED, STORAGE, NO_DATA, LOCATION }
    }
}

/**
 * Owns the path from "where the observer is standing" to "a horizon profile to draw".
 *
 * Three sources are tried in order, and the first that answers wins:
 *  1. a profile already computed for this spot, read straight off disk — this is what makes the
 *     feature work in a valley with no signal, which is exactly where it is wanted;
 *  2. elevation tiles already in the cache, imported or downloaded earlier;
 *  3. a download, and only when the user has agreed to one.
 *
 * The tiles are disposable; the profile is not. See [DemTileCache] and [HorizonProfileCodec].
 */
class HorizonRepository private constructor(
    context: Context,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val cache = DemTileCache(appContext)
    private val importer = DemTileImporter(appContext)
    private val downloader = DemTileDownloader(
        // Read per call, not captured once: turning consent off in settings must take effect now.
        consent = { settingsStore.currentHorizon().networkDownloadAllowed },
    )

    private val landmarkCache = LandmarkTileCache(appContext)
    private val landmarkSource = LandmarkSource(
        cache = landmarkCache,
        downloader = downloader,
        mayDownload = {
            val settings = settingsStore.currentHorizon()
            canDownload && settings.networkDownloadAllowed &&
                !(settings.wifiOnly && NetworkAvailability.isMetered(appContext))
        },
    )

    private val profileDirectory = File(appContext.filesDir, "horizons")
    private val notifications = TerrainNotifications(appContext)

    val offlineRegionRepository = OfflineRegionRepository(appContext)
    val offlineDownloader = OfflineTerrainDownloader(appContext, offlineRegionRepository, cache, downloader)

    private val _state = MutableStateFlow<HorizonState>(HorizonState.Idle)
    val state: StateFlow<HorizonState> = _state.asStateFlow()

    init {
        scope.launch {
            state.collect { notifications.update(it) }
        }
    }

    private val _profile = MutableStateFlow<HorizonProfile?>(null)
    val profile: StateFlow<HorizonProfile?> = _profile.asStateFlow()

    private var inFlight: Job? = null

    /** Names being added to a profile that is already shown; separate from [inFlight], which is a whole resolve. */
    private var namingJob: Job? = null
    private var lastNamingAttemptMs: Long? = null
    private val _effectiveRangeKm = MutableStateFlow<Int?>(null)

    /** The radius actually in use, which with an automatic setting is worked out from the terrain. */
    val effectiveRangeKm: StateFlow<Int?> = _effectiveRangeKm.asStateFlow()

    private val _activeTileCount = MutableStateFlow(0)

    /** The number of DEM tiles spanning the current effective search radius. */
    val activeTileCount: StateFlow<Int> = _activeTileCount.asStateFlow()

    /** Automatic radius per rounded position, so a settling GPS does not re-probe every few seconds. */
    private val estimatedRangeKm = mutableMapOf<Pair<Double, Double>, Int>()

    private var lastRequest: Request? = null

    private data class Request(val latitude: Double, val longitude: Double, val rangeKm: Int, val eyeCm: Int)

    /** True when this build can download at all. False in a source build with `INTERNET` removed. */
    val canDownload: Boolean get() = NetworkAvailability.hasInternetPermission(appContext)

    /**
     * Makes sure a profile exists for [location], computing or fetching one if needed.
     *
     * Repeated calls for the same rounded position and the same settings are ignored while one is
     * already running: the location provider emits every few seconds, and each emission must not
     * start another 26 MB download.
     */
    fun ensureProfile(location: GeoLocation, settings: HorizonSettings) {
        if (!settings.enabled) {
            inFlight?.cancel()
            _state.value = HorizonState.Idle
            return
        }

        val request = Request(
            latitude = roundPosition(location.latitude),
            longitude = roundPosition(location.longitude),
            // The *requested* radius, not the effective one: with an automatic radius the effective
            // value is not known until the observer's tile has been read, and a request that has not
            // run yet must still be recognised as the same request.
            rangeKm = if (settings.autoRange) AUTO_RANGE_REQUEST else settings.maxRangeKm,
            eyeCm = settings.eyeHeightCm,
        )
        if (request == lastRequest && inFlight?.isActive == true) return
        if (request == lastRequest && _profile.value != null) {
            // Same spot, same settings, a profile on screen: nothing to compute, but one shown without
            // names (offline at the time, consent since given) is named now, from its stored form.
            retryNames(location, settings)
            return
        }

        lastRequest = request
        namingJob?.cancel()
        inFlight?.cancel()
        inFlight = scope.launch { resolve(location, settings) }
    }

    /**
     * Names the profile on screen again when it was published without. Needs only the profile and the
     * peak tiles, never the DEM, so it works with the elevation tiles long reclaimed.
     */
    private fun retryNames(location: GeoLocation, requested: HorizonSettings) {
        val shown = _profile.value
        val last = lastNamingAttemptMs
        val since = last?.let { SystemClock.elapsedRealtime() - it }
        if (!ProfileNamer.needsNames(shown, namingJob?.isActive == true, since)) return
        // The radius the profile was made with, not the requested one: it names the stored file and
        // the tiles to ask for. Unknown only before the first resolve has got that far.
        val rangeKm = _effectiveRangeKm.value ?: return
        val settings = requested.copy(maxRangeKm = rangeKm)
        namingJob = scope.launch { addLandmarks(shown ?: return@launch, tilesFor(location, settings), settings) }
    }

    /** Forgets the current position so the next [ensureProfile] recomputes rather than reusing. */
    fun invalidate() {
        namingJob?.cancel()
        lastNamingAttemptMs = null
        lastRequest = null
        _profile.value = null
        _effectiveRangeKm.value = null
        _activeTileCount.value = 0
        _state.value = HorizonState.Idle
    }

    /** Indicates the app is waiting for GPS coordinates before downloading or computing. */
    fun setLocating() {
        inFlight?.cancel()
        _state.value = HorizonState.Locating
    }

    /** Sets the state to failed due to location timeout / unavailable GPS fix. */
    fun setLocationFailed() {
        inFlight?.cancel()
        _state.value = HorizonState.Failed(HorizonState.Failed.Reason.LOCATION)
    }

    private suspend fun resolve(location: GeoLocation, requested: HorizonSettings) {
        // The square the observer is standing in is not one tile among several: without it the ray
        // march finds nothing within the first two kilometres on every azimuth, gives up, and falls
        // back to the geometric dip — a flat ring that draws exactly like a real skyline. Everything
        // below treats it as mandatory and the outer ring as best-effort.
        val observerKey = DemTileKey.containing(location.latitude, location.longitude)

        // Resolve the search radius before anything else is fetched. With an automatic radius the
        // answer comes out of the observer's own tile, which has to be downloaded either way, so a
        // valley floor never pulls down eight neighbours to discover they change nothing.
        val settings = withEffectiveRange(location, requested, observerKey) ?: return
        _effectiveRangeKm.value = settings.maxRangeKm

        val keys = tilesFor(location, settings)
        _activeTileCount.value = keys.size

        val cached = withContext(Dispatchers.IO) { readStoredProfile(location, settings) }
        if (cached != null) {
            publish(cached)
            // A profile from before peak names existed, or from a run with no connection, gets them now.
            if (!cached.landmarksResolved) addLandmarks(cached, keys, settings)
            return
        }

        val (present, missing) = cache.partition(keys)
        if (missing.isNotEmpty()) {
            val fetched = fetchMissing(missing, settings)
            if (!fetched) return
        }

        val tiles = withContext(Dispatchers.IO) { keys.mapNotNull { cache.open(it) } }
        // Trim only now, and never the tiles this run needs. Reclaiming space from the working set
        // does not save a download; it corrupts the answer.
        withContext(Dispatchers.IO) { cache.trim(protect = keys.toSet()) }

        if (tiles.none { it.key == observerKey }) {
            Log.w(TAG, "no tile for the observer's own square $observerKey; refusing to invent a horizon")
            _state.value = HorizonState.NeedsTiles(listOf(observerKey))
            return
        }
        Log.i(TAG, "computing horizon from ${tiles.size} tile(s), ${present.size} were already cached")

        val profile = withContext(Dispatchers.Default) {
            HorizonProfileCalculator.compute(
                grid = CompositeElevationGrid(tiles),
                observer = location,
                eyeHeightMeters = settings.eyeHeightMeters,
                maxRangeMeters = settings.maxRangeMeters,
                onProgress = { _state.value = HorizonState.Computing(it) },
            )
        }

        if (!profile.isUsable) {
            // Mostly the mathematical horizon wearing a terrain colour. Saying "no data" is the
            // honest answer; drawing it would be a confident wrong one.
            Log.w(TAG, "profile covers only ${profile.coveredSamples} of ${HorizonProfile.SAMPLE_COUNT} azimuths")
            _state.value = HorizonState.Failed(HorizonState.Failed.Reason.NO_DATA)
            return
        }

        // The skyline is stored and shown before any name is looked for: peak tiles are a second round
        // of requests, and a slow or absent connection must not hold the horizon back.
        withContext(Dispatchers.IO) { writeStoredProfile(profile, settings) }
        publish(profile)
        addLandmarks(profile, keys, settings)
    }

    private suspend fun addLandmarks(profile: HorizonProfile, keys: List<DemTileKey>, settings: HorizonSettings) {
        lastNamingAttemptMs = SystemClock.elapsedRealtime()
        ProfileNamer(
            source = landmarkSource,
            store = { withContext(Dispatchers.IO) { writeStoredProfile(it, settings) } },
            current = { _profile.value },
            publish = ::publish,
        ).name(profile, keys)
    }

    /**
     * Returns [requested] with a concrete search radius filled in, or null when the observer's own
     * tile could not be obtained and there is therefore nothing to estimate from.
     *
     * Estimates are remembered per rounded position: the location provider emits every few seconds
     * and the probe is a full profile computation, cheap but not free.
     */
    private suspend fun withEffectiveRange(
        location: GeoLocation,
        requested: HorizonSettings,
        observerKey: DemTileKey,
    ): HorizonSettings? {
        if (!requested.autoRange) return requested

        val positionKey = roundPosition(location.latitude) to roundPosition(location.longitude)
        estimatedRangeKm[positionKey]?.let { return requested.copy(maxRangeKm = it) }
        // The estimate is stored next to the profiles: working it out again opens the observer's
        // tile and marches its rays, which kept a stored profile off the screen for over a second
        // after every process start.
        withContext(Dispatchers.IO) { readStoredRange(positionKey) }?.let {
            estimatedRangeKm[positionKey] = it
            return requested.copy(maxRangeKm = it)
        }

        if (cache.partition(listOf(observerKey)).second.isNotEmpty()) {
            if (!fetchMissing(listOf(observerKey), requested)) return null
        }
        val observerTile = withContext(Dispatchers.IO) { cache.open(observerKey) }
        if (observerTile == null) {
            _state.value = HorizonState.NeedsTiles(listOf(observerKey))
            return null
        }

        val suggested = withContext(Dispatchers.Default) {
            HorizonRangeEstimator.suggestRangeMeters(
                grid = observerTile,
                observer = location,
                eyeHeightMeters = requested.eyeHeightMeters,
                probeRangeMeters = HorizonSettings.AUTO_PROBE_RANGE_KM * 1000.0,
                maxRangeMeters = HorizonSettings.RANGE_CHOICES_KM.last() * 1000.0,
            )
        }
        // Rounded up onto one of the offered values so a profile's file name still says, exactly,
        // what it was computed with — and so the same spot does not recompute over a 300 m drift.
        val km = HorizonSettings.RANGE_CHOICES_KM.firstOrNull { it * 1000.0 >= suggested }
            ?: HorizonSettings.RANGE_CHOICES_KM.last()
        Log.i(TAG, "automatic range for ${observerKey.name}: ${(suggested / 1000).toInt()} km -> $km km")
        estimatedRangeKm[positionKey] = km
        withContext(Dispatchers.IO) { writeStoredRange(positionKey, km) }
        return requested.copy(maxRangeKm = km)
    }

    private fun rangeFile(position: Pair<Double, Double>) =
        File(profileDirectory, "auto-range_${position.first}_${position.second}.txt")

    /** A stored automatic radius, or null when there is none or it is not one of the offered values. */
    private fun readStoredRange(position: Pair<Double, Double>): Int? = try {
        rangeFile(position).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?.takeIf { it in HorizonSettings.RANGE_CHOICES_KM }
    } catch (e: IOException) {
        null
    }

    private fun writeStoredRange(position: Pair<Double, Double>, km: Int) {
        try {
            profileDirectory.mkdirs()
            rangeFile(position).writeText(km.toString())
        } catch (e: IOException) {
            // Losing it costs one estimate next time, nothing more.
            Log.w(TAG, "cannot store automatic range", e)
        }
    }

    /** @return true when every missing tile is now on disk. */
    private suspend fun fetchMissing(missing: List<DemTileKey>, settings: HorizonSettings): Boolean {
        if (!canDownload || !settings.networkDownloadAllowed) {
            _state.value = HorizonState.NeedsTiles(missing)
            return false
        }
        if (settings.wifiOnly && NetworkAvailability.isMetered(appContext)) {
            _state.value = HorizonState.Failed(HorizonState.Failed.Reason.METERED)
            return false
        }

        missing.forEachIndexed { index, key ->
            _state.value = HorizonState.Downloading(key, index + 1, missing.size, 0f)
            try {
                cache.store(key) { out ->
                    downloader.download(key, out) { received, totalBytes ->
                        _state.value = HorizonState.Downloading(
                            key = key,
                            index = index + 1,
                            total = missing.size,
                            progress = DemTileDownloader.fraction(received, totalBytes),
                            receivedBytes = received,
                            totalBytes = totalBytes,
                        )
                    }
                }
            } catch (e: DemTileDownloader.NotPermittedException) {
                _state.value = HorizonState.NeedsTiles(missing)
                return false
            } catch (e: IOException) {
                Log.w(TAG, "tile ${key.name} failed", e)
                _state.value = HorizonState.Failed(HorizonState.Failed.Reason.NETWORK, e)
                return false
            }
        }
        return true
    }

    private fun publish(profile: HorizonProfile) {
        _profile.value = profile
        _state.value = HorizonState.Ready(profile)
    }

    /** Every tile the search radius can reach from [location]. */
    fun tilesFor(location: GeoLocation, settings: HorizonSettings): List<DemTileKey> {
        val rangeMeters = settings.maxRangeMeters
        val latSpan = rangeMeters / HorizonProfileCalculator.EARTH_RADIUS_METERS * (180.0 / Math.PI)
        // Longitude degrees shrink towards the poles; the guard keeps the divisor away from zero so
        // an observer in Svalbard asks for a sane number of tiles instead of the whole parallel.
        val cosLat = cos(location.latitude * Math.PI / 180.0).coerceAtLeast(MIN_COS_LATITUDE)
        val lonSpan = latSpan / cosLat

        return DemTileKey.coveringBox(
            minLatitude = (location.latitude - latSpan).coerceIn(-90.0, 89.999),
            maxLatitude = (location.latitude + latSpan).coerceIn(-90.0, 89.999),
            minLongitude = location.longitude - lonSpan,
            maxLongitude = location.longitude + lonSpan,
        )
    }

    /** Tiles the search radius needs that are not on disk yet. */
    fun missingTilesFor(location: GeoLocation, settings: HorizonSettings): List<DemTileKey> =
        cache.partition(tilesFor(location, settings)).second

    suspend fun importTile(uri: Uri): DemTileImporter.Result = withContext(Dispatchers.IO) {
        importer.import(uri, cache).also { if (it is DemTileImporter.Result.Imported) lastRequest = null }
    }

    fun cachedTileBytes(): Long = cache.totalBytes()

    fun getDemCacheSizeBytes(): Long = cache.totalBytes()

    fun getProfilesSizeBytes(): Long =
        profileDirectory.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L

    fun storedProfileCount(): Int = profileDirectory.listFiles()?.count { it.extension == "bin" } ?: 0

    fun getStoredProfileCount(): Int = storedProfileCount()

    fun getDemOfflineSizeBytes(): Long = offlineRegionRepository.getOfflineSizeBytes()

    fun getOfflineRegionCount(): Int = offlineRegionRepository.getRegionCount()

    /** Returns a current snapshot of disk metrics for DEM tile caches, offline regions, and computed horizon profiles. */
    fun getStorageMetrics(): StorageMetrics = StorageMetrics(
        demCacheSizeBytes = getDemCacheSizeBytes(),
        demCacheLimitBytes = DemTileCache.DEFAULT_MAX_BYTES,
        profilesSizeBytes = getProfilesSizeBytes(),
        profileCount = storedProfileCount(),
        demOfflineSizeBytes = getDemOfflineSizeBytes(),
        offlineRegionCount = getOfflineRegionCount(),
    )

    /**
     * Safely drops every downloaded DEM tile. Cancels any in-flight download or calculation first
     * to avoid race conditions or torn file reads. Computed profiles are untouched.
     */
    fun clearDemCache() {
        inFlight?.cancel()
        inFlight = null
        cache.clear()
        landmarkCache.clear()
        _activeTileCount.value = 0
        _effectiveRangeKm.value = null
        lastRequest = null
    }

    /** Drops every downloaded tile. Computed profiles are untouched: they cost nothing and are the point. */
    fun clearTileCache() = clearDemCache()

    /** Drops all stored horizon profiles and automatic radii from filesDir/horizons/ and invalidates in-memory profile. */
    fun clearProfiles() {
        profileDirectory.listFiles()?.filter { it.extension == "bin" || it.extension == "part" || it.name.startsWith("auto-range_") }?.forEach { it.delete() }
        estimatedRangeKm.clear()
        _profile.value = null
        invalidate()
    }

    /** Drops every computed profile as well, returning the feature to a fresh state. */
    fun clearEverything() {
        clearDemCache()
        clearProfiles()
        notifications.cancel()
    }

    private fun profileFile(location: GeoLocation, settings: HorizonSettings) = File(
        profileDirectory,
        HorizonProfileCodec.fileName(
            latitude = roundPosition(location.latitude),
            longitude = roundPosition(location.longitude),
            maxRangeKm = settings.maxRangeKm,
            eyeHeightCm = settings.eyeHeightCm,
        ),
    )

    private fun readStoredProfile(location: GeoLocation, settings: HorizonSettings): HorizonProfile? {
        val file = profileFile(location, settings)
        if (!file.isFile) return null
        return try {
            // An unusable cached profile is treated as no cache at all, so a flat one written by an
            // earlier run is recomputed instead of being served back forever.
            file.inputStream().use { HorizonProfileCodec.read(it) }?.takeIf { it.isUsable }
        } catch (e: IOException) {
            null
        }
    }

    private fun writeStoredProfile(profile: HorizonProfile, settings: HorizonSettings) {
        try {
            profileDirectory.mkdirs()
            // Temp file then atomic move: this also rewrites an already good profile when names are
            // added, and a kill or full disk halfway through a direct write left a truncated file that
            // reads as null, losing the skyline and the DEM download behind it.
            val temporary = File.createTempFile("profile-", ".part", profileDirectory)
            try {
                temporary.outputStream().use { HorizonProfileCodec.write(it, profile) }
                Files.move(
                    temporary.toPath(),
                    profileFile(profile.observer, settings).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                temporary.delete()
            }
        } catch (e: IOException) {
            // A profile that cannot be cached is still a profile that can be drawn. Losing the cache
            // costs a recomputation next time; failing the screen over it would cost the feature.
            Log.w(TAG, "cannot cache horizon profile", e)
        }
    }

    companion object {
        private const val TAG = "HorizonRepository"

        /** Stands in for "work it out yourself" in the de-duplication key. Not a real radius. */
        private const val AUTO_RANGE_REQUEST = -1

        @Volatile
        private var instance: HorizonRepository? = null

        /**
         * The one repository for the whole process.
         *
         * A singleton for the same reason [com.dazix.caelis.data.SessionRepository] is one: three
         * screens care about the same state. With one instance per view model, opening Settings and
         * then Sun AR started the same 26 MB download twice, and both would have driven the same
         * progress notification from different coroutines.
         *
         * Its scope outlives every view model on purpose — a download must survive leaving the
         * screen that started it, which is precisely when the phone goes into a pocket.
         */
        fun getInstance(context: Context): HorizonRepository =
            instance ?: synchronized(this) {
                instance ?: HorizonRepository(
                    context = context.applicationContext,
                    settingsStore = SettingsStore(context.applicationContext),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ).also { instance = it }
            }

        /** About 110 m of latitude: close enough that the skyline has not meaningfully moved. */
        private const val POSITION_ROUNDING = 1000.0

        private const val MIN_COS_LATITUDE = 0.05

        fun roundPosition(value: Double): Double = Math.round(value * POSITION_ROUNDING) / POSITION_ROUNDING
    }
}

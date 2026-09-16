package com.dazix.caelis.data.horizon

import android.content.Context
import android.util.Log
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HgtTile
import com.dazix.caelis.domain.horizon.OfflineRegion
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** State of an offline terrain region download. */
sealed interface OfflineDownloadState {
    data object Idle : OfflineDownloadState

    data class Downloading(
        val regionName: String,
        val currentTile: DemTileKey,
        val tileIndex: Int,
        val totalTiles: Int,
        val tileProgress: Float,
        val overallProgress: Float,
    ) : OfflineDownloadState

    data class Success(val region: OfflineRegion) : OfflineDownloadState

    data class Failed(val error: String, val cause: Throwable? = null) : OfflineDownloadState
}

/** Pre-computed storage and network requirements for a requested region. */
data class DownloadEstimate(
    val requiredTiles: List<DemTileKey>,
    val missingFromOffline: List<DemTileKey>,
    val promoteFromCache: List<DemTileKey>,
    val needNetworkDownload: List<DemTileKey>,
    val estimatedTotalBytes: Long,
    val estimatedNetworkBytes: Long,
)

/**
 * Orchestrates smart offline terrain tile fetching and persistence.
 *
 * Checks storage layers in order:
 * 1. `filesDir/dem_offline/` -> Skip download if present.
 * 2. `cacheDir/dem/` -> Gracefully promote (move/copy) to `dem_offline/` to save network bandwidth.
 * 3. Network -> Fetch decompressed tile stream via [DemTileDownloader] directly into `dem_offline/`.
 */
class OfflineTerrainDownloader(
    context: Context,
    private val regionRepository: OfflineRegionRepository,
    private val tileCache: DemTileCache,
    private val downloader: DemTileDownloader,
) {

    private val offlineDirectory = File(context.filesDir, OfflineRegionRepository.OFFLINE_DIR_NAME)
    private val cacheDirectory = File(context.cacheDir, "dem")

    private val _state = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val state: StateFlow<OfflineDownloadState> = _state.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Estimates tile counts and network requirements for a prospective region without downloading.
     */
    fun estimate(lat: Double, lon: Double, radiusKm: Int): DownloadEstimate {
        val required = OfflineRegion.tilesFor(lat, lon, radiusKm)
        val missingFromOffline = required.filterNot { regionRepository.hasOfflineTile(it) }
        val promoteFromCache = missingFromOffline.filter {
            val cacheFile = File(cacheDirectory, it.fileName)
            cacheFile.isFile && cacheFile.length() > 0
        }
        val needNetwork = missingFromOffline.filterNot { promoteFromCache.contains(it) }

        val tileBytesEst = ESTIMATED_TILE_BYTES
        val totalBytes = required.sumOf { key ->
            val offlineFile = regionRepository.fileFor(key)
            if (offlineFile.isFile && offlineFile.length() > 0) offlineFile.length() else tileBytesEst
        }
        val netBytes = needNetwork.size * tileBytesEst

        return DownloadEstimate(
            requiredTiles = required,
            missingFromOffline = missingFromOffline,
            promoteFromCache = promoteFromCache,
            needNetworkDownload = needNetwork,
            estimatedTotalBytes = totalBytes,
            estimatedNetworkBytes = netBytes,
        )
    }

    /**
     * Downloads and stores all tiles for [name] covering [radiusKm] around ([latitude], [longitude]).
     */
    suspend fun downloadRegion(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
    ): Result<OfflineRegion> = withContext(Dispatchers.IO) {
        offlineDirectory.mkdirs()
        val requiredTiles = OfflineRegion.tilesFor(latitude, longitude, radiusKm)
        val totalCount = requiredTiles.size

        try {
            requiredTiles.forEachIndexed { index, key ->
                val tileIndex = index + 1
                val offlineDestination = regionRepository.fileFor(key)

                // 1. Check if already in permanent offline storage
                if (offlineDestination.isFile && offlineDestination.length() > 0) {
                    val progress = tileIndex.toFloat() / totalCount
                    _state.value = OfflineDownloadState.Downloading(
                        regionName = name,
                        currentTile = key,
                        tileIndex = tileIndex,
                        totalTiles = totalCount,
                        tileProgress = 1f,
                        overallProgress = progress,
                    )
                    return@forEachIndexed
                }

                // 2. Check if in volatile cache — promote to save bandwidth
                val cachedFile = File(cacheDirectory, key.fileName)
                if (cachedFile.isFile && cachedFile.length() > 0 && HgtTile.isPlausibleGridLength(cachedFile.length())) {
                    Log.i(TAG, "promoting cached tile ${key.name} to offline storage")
                    if (cachedFile.renameTo(offlineDestination) || copyFile(cachedFile, offlineDestination)) {
                        val progress = tileIndex.toFloat() / totalCount
                        _state.value = OfflineDownloadState.Downloading(
                            regionName = name,
                            currentTile = key,
                            tileIndex = tileIndex,
                            totalTiles = totalCount,
                            tileProgress = 1f,
                            overallProgress = progress,
                        )
                        return@forEachIndexed
                    }
                }

                // 3. Otherwise download via DemTileDownloader directly to offline directory
                val tempPart = File(offlineDirectory, "${key.name}.part")
                try {
                    _state.value = OfflineDownloadState.Downloading(
                        regionName = name,
                        currentTile = key,
                        tileIndex = tileIndex,
                        totalTiles = totalCount,
                        tileProgress = 0f,
                        overallProgress = (tileIndex - 1).toFloat() / totalCount,
                    )

                    tempPart.outputStream().use { out ->
                        downloader.download(key, out) { tileProgress ->
                            val overall = (tileIndex - 1 + tileProgress) / totalCount
                            _state.value = OfflineDownloadState.Downloading(
                                regionName = name,
                                currentTile = key,
                                tileIndex = tileIndex,
                                totalTiles = totalCount,
                                tileProgress = tileProgress,
                                overallProgress = overall,
                            )
                        }
                    }

                    val length = tempPart.length()
                    if (length < DemTileCache.MIN_PLAUSIBLE_TILE_BYTES || !HgtTile.isPlausibleGridLength(length)) {
                        throw IOException("tile ${key.name} came back as $length bytes (corrupted grid)")
                    }

                    if (offlineDestination.exists()) offlineDestination.delete()
                    if (!tempPart.renameTo(offlineDestination)) {
                        throw IOException("cannot rename ${tempPart.name} to ${offlineDestination.name}")
                    }
                } finally {
                    tempPart.delete()
                }
            }

            // Calculate actual physical size of all required tiles on disk
            val actualTotalBytes = requiredTiles.sumOf { regionRepository.fileFor(it).length() }
            val region = OfflineRegion(
                id = UUID.randomUUID().toString(),
                name = name,
                targetLat = latitude,
                targetLon = longitude,
                radiusKm = radiusKm,
                requiredTiles = requiredTiles,
                totalSizeBytes = actualTotalBytes,
            )

            regionRepository.saveRegion(region)
            _state.value = OfflineDownloadState.Success(region)
            Result.success(region)
        } catch (e: CancellationException) {
            Log.i(TAG, "offline region download cancelled")
            _state.value = OfflineDownloadState.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "offline region download failed", e)
            _state.value = OfflineDownloadState.Failed(e.message ?: "Download failed", e)
            Result.failure(e)
        }
    }

    fun resetState() {
        _state.value = OfflineDownloadState.Idle
    }

    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            dst.delete()
            false
        }
    }

    companion object {
        private const val TAG = "OfflineTerrainDownloader"
        /** Typical uncompressed Skadi 1-arcsecond .hgt tile size (~25.96 MB). */
        const val ESTIMATED_TILE_BYTES = 1201L * 1201L * 2L
    }
}

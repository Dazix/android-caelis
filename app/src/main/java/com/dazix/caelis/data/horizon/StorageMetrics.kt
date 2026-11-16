package com.dazix.caelis.data.horizon

/**
 * Snapshot of disk space used by DEM elevation tile caches, permanent offline regions, and computed horizon profiles.
 */
data class StorageMetrics(
    /** Current size of decompressed `.hgt` and `.part` files in `cacheDir/dem/` (in bytes). */
    val demCacheSizeBytes: Long = 0L,
    /** Configured upper bound for DEM tile cache before LRU trimming (default 1536 MB). */
    val demCacheLimitBytes: Long = DemTileCache.DEFAULT_MAX_BYTES,
    /** Current size of computed `.bin` profiles in `filesDir/horizons/` (in bytes). */
    val profilesSizeBytes: Long = 0L,
    /** Number of stored `.bin` profiles available for offline reuse. */
    val profileCount: Int = 0,
    /** Current size of permanent `.hgt` files in `filesDir/dem_offline/` (in bytes). */
    val demOfflineSizeBytes: Long = 0L,
    /** Number of saved offline regions. */
    val offlineRegionCount: Int = 0,
) {
    val demCacheFormatted: String get() = formatBytes(demCacheSizeBytes)
    val demCacheLimitFormatted: String get() = formatBytes(demCacheLimitBytes)
    val profilesFormatted: String get() = formatBytes(profilesSizeBytes)
    val demOfflineFormatted: String get() = formatBytes(demOfflineSizeBytes)

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
            bytes >= 1024L -> "${bytes / 1024L} kB"
            else -> "$bytes B"
        }
    }
}

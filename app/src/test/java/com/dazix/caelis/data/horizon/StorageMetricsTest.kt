package com.dazix.caelis.data.horizon

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageMetricsTest {

    @Test
    fun `default metrics are all zeroed with default 1536 MB limit`() {
        val metrics = StorageMetrics()
        assertEquals(0L, metrics.demCacheSizeBytes)
        assertEquals(DemTileCache.DEFAULT_MAX_BYTES, metrics.demCacheLimitBytes)
        assertEquals(0L, metrics.profilesSizeBytes)
        assertEquals(0, metrics.profileCount)
        assertEquals(0L, metrics.demOfflineSizeBytes)
        assertEquals(0, metrics.offlineRegionCount)

        assertEquals("0 B", metrics.demCacheFormatted)
        assertEquals("1536 MB", metrics.demCacheLimitFormatted)
        assertEquals("0 B", metrics.profilesFormatted)
        assertEquals("0 B", metrics.demOfflineFormatted)
    }

    @Test
    fun `formatBytes correctly formats B, kB, and MB boundaries`() {
        assertEquals("0 B", StorageMetrics.formatBytes(0L))
        assertEquals("512 B", StorageMetrics.formatBytes(512L))
        assertEquals("1 kB", StorageMetrics.formatBytes(1024L))
        assertEquals("12 kB", StorageMetrics.formatBytes(12 * 1024L))
        assertEquals("116 kB", StorageMetrics.formatBytes(116 * 1024L + 500L))
        assertEquals("1 MB", StorageMetrics.formatBytes(1024L * 1024L))
        assertEquals("145 MB", StorageMetrics.formatBytes(145L * 1024L * 1024L + 250_000L))
        assertEquals("512 MB", StorageMetrics.formatBytes(512L * 1024L * 1024L))
    }

    @Test
    fun `populated storage metrics expose formatted properties accurately`() {
        val metrics = StorageMetrics(
            demCacheSizeBytes = 145L * 1024L * 1024L,
            demCacheLimitBytes = 512L * 1024L * 1024L,
            profilesSizeBytes = 116L * 1024L,
            profileCount = 20,
            demOfflineSizeBytes = 78L * 1024L * 1024L,
            offlineRegionCount = 3,
        )

        assertEquals("145 MB", metrics.demCacheFormatted)
        assertEquals("512 MB", metrics.demCacheLimitFormatted)
        assertEquals("116 kB", metrics.profilesFormatted)
        assertEquals(20, metrics.profileCount)
        assertEquals("78 MB", metrics.demOfflineFormatted)
        assertEquals(3, metrics.offlineRegionCount)
    }
}

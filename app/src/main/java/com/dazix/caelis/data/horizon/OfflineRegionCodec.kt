package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.OfflineRegion

/**
 * Pure Kotlin serialization codec for [OfflineRegion] records.
 *
 * Ensures zero external library dependencies and fast, deterministic JVM unit testing.
 * Uses a tab-delimited format with one region per line.
 */
object OfflineRegionCodec {

    private const val FIELD_COUNT = 8

    /**
     * Serializes a list of [OfflineRegion]s into a newline/tab-delimited string.
     */
    fun encode(regions: List<OfflineRegion>): String {
        return regions.joinToString("\n") { region ->
            listOf(
                region.id,
                escape(region.name),
                region.targetLat.toString(),
                region.targetLon.toString(),
                region.radiusKm.toString(),
                region.requiredTiles.joinToString(",") { it.name },
                region.totalSizeBytes.toString(),
                region.createdAtMs.toString(),
            ).joinToString("\t")
        }
    }

    /**
     * Deserializes a string back into a list of [OfflineRegion]s.
     * Safely ignores malformed or corrupted lines without throwing exceptions.
     */
    fun decode(raw: String): List<OfflineRegion> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val tokens = line.split("\t")
                if (tokens.size < FIELD_COUNT) return@mapNotNull null
                val id = tokens[0].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = unescape(tokens[1])
                val targetLat = tokens[2].toDoubleOrNull() ?: return@mapNotNull null
                val targetLon = tokens[3].toDoubleOrNull() ?: return@mapNotNull null
                val radiusKm = tokens[4].toIntOrNull() ?: return@mapNotNull null
                val requiredTiles = if (tokens[5].isBlank()) {
                    emptyList()
                } else {
                    tokens[5].split(",").mapNotNull { DemTileKey.parse(it) }
                }
                val totalSizeBytes = tokens[6].toLongOrNull() ?: return@mapNotNull null
                val createdAtMs = tokens[7].toLongOrNull() ?: 0L

                OfflineRegion(
                    id = id,
                    name = name,
                    targetLat = targetLat,
                    targetLon = targetLon,
                    radiusKm = radiusKm,
                    requiredTiles = requiredTiles,
                    totalSizeBytes = totalSizeBytes,
                    createdAtMs = createdAtMs,
                )
            }
            .toList()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
}

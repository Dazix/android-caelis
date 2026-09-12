package com.dazix.caelis.domain.horizon

import kotlin.math.floor

/**
 * A source of terrain elevation addressed in geographic coordinates.
 *
 * Pure Kotlin, no Android imports: the horizon maths has to be unit-testable against synthetic
 * terrain without a device and without a 26 MB fixture on disk.
 */
interface ElevationGrid {

    /**
     * Terrain elevation in metres above sea level at [latitude] / [longitude], or `null` where this
     * grid holds no usable value — outside its coverage, or a void pixel in the source data.
     *
     * `null` is deliberately not 0.0. A void pixel sits most often in a steep valley or on a glacier,
     * exactly where a spurious sea-level reading would invent a horizon dip that is not there.
     * Callers must skip the sample instead of substituting a number.
     */
    fun elevationAt(latitude: Double, longitude: Double): Double?
}

/**
 * Dispatches lookups across several [HgtTile]s, so a ray that walks out of one 1°×1° tile continues
 * into its neighbour.
 *
 * Tiles are indexed by their south-west integer corner, which is how the source names them.
 * A lookup that lands outside every loaded tile returns `null` rather than throwing: an observer
 * near the coast legitimately has azimuths whose rays leave the covered area within a few kilometres.
 *
 * Sampling right on a tile boundary reads only the tile the point falls into, never the neighbour's
 * duplicate edge row. Both carry the same edge values, so the worst case is a hairline discontinuity
 * below one DEM post (~30 m) — far under the resolution the horizon profile is stored at.
 */
class CompositeElevationGrid(tiles: Collection<HgtTile>) : ElevationGrid {

    private val byKey: Map<DemTileKey, HgtTile> = tiles.associateBy { it.key }

    /** The tiles actually available, for reporting which ones are still missing. */
    val keys: Set<DemTileKey> get() = byKey.keys

    val isEmpty: Boolean get() = byKey.isEmpty()

    override fun elevationAt(latitude: Double, longitude: Double): Double? {
        val tile = byKey[DemTileKey.containing(latitude, longitude)] ?: return null
        return tile.elevationAt(latitude, longitude)
    }
}

/**
 * Bilinear blend of up to four corner samples, ignoring the ones that came back `null`.
 *
 * The weights of the valid corners are renormalised, so a single void neighbour degrades the
 * interpolation instead of poisoning it, and an all-void quad returns `null`.
 */
internal fun blendCorners(
    v00: Double?,
    v10: Double?,
    v01: Double?,
    v11: Double?,
    fracRow: Double,
    fracCol: Double,
): Double? {
    var weighted = 0.0
    var weight = 0.0

    fun add(value: Double?, w: Double) {
        if (value != null && w > 0.0) {
            weighted += value * w
            weight += w
        }
    }

    add(v00, (1.0 - fracRow) * (1.0 - fracCol))
    add(v01, (1.0 - fracRow) * fracCol)
    add(v10, fracRow * (1.0 - fracCol))
    add(v11, fracRow * fracCol)

    return if (weight <= 0.0) null else weighted / weight
}

/** Normalises a longitude into [-180.0, 180.0), so a ray crossing the antimeridian keeps working. */
internal fun normalizeLongitude(longitude: Double): Double {
    var lon = (longitude + 180.0) % 360.0
    if (lon < 0.0) lon += 360.0
    return lon - 180.0
}

/** Floor of a longitude after normalisation, i.e. the west edge of the tile that contains it. */
internal fun floorLongitude(longitude: Double): Int = floor(normalizeLongitude(longitude)).toInt()

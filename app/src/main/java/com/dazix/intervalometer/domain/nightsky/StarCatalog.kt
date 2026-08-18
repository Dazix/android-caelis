package com.dazix.intervalometer.domain.nightsky

/**
 * In-memory zero-allocation storage for the static star catalog.
 *
 * All star coordinates and visual magnitudes are packed into a flat [rawData] array:
 * - `rawData[i * 3 + 0]` = Right Ascension in degrees [0.0, 360.0) J2000.0
 * - `rawData[i * 3 + 1]` = Declination in degrees [-90.0, +90.0] J2000.0
 * - `rawData[i * 3 + 2]` = Apparent visual magnitude (e.g. -1.46 for Sirius)
 */
class StarCatalog(
    val rawData: FloatArray,
    val starCount: Int = rawData.size / 3,
) {
    fun getRa(starIndex: Int): Float = rawData[starIndex * 3]
    fun getDec(starIndex: Int): Float = rawData[starIndex * 3 + 1]
    fun getMag(starIndex: Int): Float = rawData[starIndex * 3 + 2]

    companion object {
        val EMPTY = StarCatalog(FloatArray(0), 0)
    }
}

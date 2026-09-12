package com.dazix.caelis.domain.horizon

import kotlin.math.floor

/**
 * Identifies one 1°×1° elevation tile by its south-west integer corner.
 *
 * This is the naming scheme SRTM established and the one the Skadi distribution of the Tilezen
 * terrain tiles follows, e.g. `N50E014` covers 50°..51° N and 14°..15° E.
 *
 * Pure Kotlin on purpose: the same key names the file a user picks by hand in a file chooser and the
 * object the downloader fetches, so both paths agree on which tile they are talking about without
 * either of them owning the rule.
 */
data class DemTileKey(val latSouth: Int, val lonWest: Int) {

    init {
        require(latSouth in -90..89) { "latSouth must be in [-90, 89], got $latSouth" }
        require(lonWest in -180..179) { "lonWest must be in [-180, 179], got $lonWest" }
    }

    /** e.g. `N50` — the directory the tile lives in, one per degree of latitude. */
    val directory: String
        get() = if (latSouth >= 0) "N%02d".format(latSouth) else "S%02d".format(-latSouth)

    /** e.g. `N50E014` — the tile's bare name, without extension. */
    val name: String
        get() {
            val lonPart = if (lonWest >= 0) "E%03d".format(lonWest) else "W%03d".format(-lonWest)
            return directory + lonPart
        }

    /** e.g. `N50E014.hgt` — the decompressed file name. */
    val fileName: String get() = "$name.hgt"

    /** Path under the tile server root, e.g. `skadi/N50/N50E014.hgt.gz`. */
    val remotePath: String get() = "skadi/$directory/$name.hgt.gz"

    override fun toString(): String = name

    companion object {

        /** The tile that contains [latitude] / [longitude]. */
        fun containing(latitude: Double, longitude: Double): DemTileKey = DemTileKey(
            latSouth = floor(latitude).toInt().coerceIn(-90, 89),
            lonWest = floorLongitude(longitude),
        )

        /**
         * Every tile touched by the given bounding box, in north-west to south-east order.
         *
         * [minLongitude] may be greater than [maxLongitude] when the box straddles the antimeridian;
         * the longitude walk wraps rather than producing an empty list, because an observer at 179.9° E
         * genuinely needs the tile on the other side of the line.
         */
        fun coveringBox(
            minLatitude: Double,
            maxLatitude: Double,
            minLongitude: Double,
            maxLongitude: Double,
        ): List<DemTileKey> {
            val latFrom = floor(minLatitude).toInt().coerceIn(-90, 89)
            val latTo = floor(maxLatitude).toInt().coerceIn(-90, 89)
            val lonFrom = floorLongitude(minLongitude)
            val lonTo = floorLongitude(maxLongitude)

            // Walking with an explicit count instead of a range keeps the antimeridian case honest:
            // from 179 to -180 is one step east, not a 359-step journey backwards around the globe.
            val lonSteps = if (lonTo >= lonFrom) lonTo - lonFrom else lonTo + 360 - lonFrom

            val keys = ArrayList<DemTileKey>((latTo - latFrom + 1) * (lonSteps + 1))
            for (lat in latTo downTo latFrom) {
                for (step in 0..lonSteps) {
                    var lon = lonFrom + step
                    if (lon > 179) lon -= 360
                    keys += DemTileKey(lat, lon)
                }
            }
            return keys
        }

        private val NAME_PATTERN = Regex("^([NS])(\\d{2})([EW])(\\d{3})$", RegexOption.IGNORE_CASE)

        /**
         * Parses a tile name such as `N50E014`, tolerating a `.hgt` or `.hgt.gz` suffix and any
         * leading directory, so a file the user picked in a chooser can be identified by its name.
         *
         * Returns `null` for anything that is not a tile name — the import path has to reject a
         * wrong file with a readable message rather than mis-place it somewhere in the ocean.
         */
        fun parse(fileName: String): DemTileKey? {
            val bare = fileName.substringAfterLast('/')
                .removeSuffix(".gz")
                .removeSuffix(".hgt")
            val match = NAME_PATTERN.matchEntire(bare) ?: return null
            val (latHemisphere, latDigits, lonHemisphere, lonDigits) = match.destructured

            val lat = latDigits.toInt().let { if (latHemisphere.equals("S", true)) -it else it }
            val lon = lonDigits.toInt().let { if (lonHemisphere.equals("W", true)) -it else it }
            if (lat !in -90..89 || lon !in -180..179) return null

            return DemTileKey(lat, lon)
        }
    }
}

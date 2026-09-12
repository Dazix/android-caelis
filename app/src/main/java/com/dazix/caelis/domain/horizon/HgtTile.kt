package com.dazix.caelis.domain.horizon

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One 1°×1° elevation tile in the SRTM `.hgt` layout: a square grid of big-endian signed 16-bit
 * metres, row 0 being the **northernmost** row and column 0 the westernmost.
 *
 * The grid side is derived from the data length rather than assumed, because the same layout ships
 * at 1 arcsecond (3601 posts, ~30 m) and 3 arcseconds (1201 posts, ~90 m) and a hand-imported file
 * may be either. Rows overlap their neighbours by one post, which is why the spacing is `n - 1`
 * intervals across a whole degree and not `n`.
 *
 * Backed by a [ShortBuffer] so a memory-mapped file can be read without pulling 26 MB onto the heap.
 */
class HgtTile(
    val key: DemTileKey,
    private val samples: ShortBuffer,
) : ElevationGrid {

    /** Number of posts along one side of the grid, e.g. 3601 for 1 arcsecond data. */
    val gridSize: Int = sqrt(samples.capacity().toDouble()).roundToInt().also {
        require(it >= 2) { "grid too small: ${samples.capacity()} samples" }
        require(it * it == samples.capacity()) {
            "${samples.capacity()} samples is not a square grid (nearest side $it)"
        }
    }

    /** Ground spacing between neighbouring posts, in degrees. */
    val postSpacingDegrees: Double get() = 1.0 / (gridSize - 1)

    override fun elevationAt(latitude: Double, longitude: Double): Double? {
        val last = gridSize - 1

        // Longitude is reduced relative to the tile's own west edge, so a tile sitting on the
        // antimeridian still sees its eastern half as "+1 degree" and not "-359 degrees".
        var deltaLon = normalizeLongitude(longitude) - key.lonWest
        if (deltaLon < -180.0) deltaLon += 360.0
        if (deltaLon > 180.0) deltaLon -= 360.0

        val row = (key.latSouth + 1 - latitude) * last
        val col = deltaLon * last
        if (row < 0.0 || row > last || col < 0.0 || col > last) return null

        // Clamping the anchor to last - 1 keeps the exact south and east edges inside the grid:
        // there the fraction lands on 1.0 and the blend degenerates to the edge posts themselves.
        val row0 = floor(row).toInt().coerceIn(0, last - 1)
        val col0 = floor(col).toInt().coerceIn(0, last - 1)

        return blendCorners(
            v00 = sample(row0, col0),
            v10 = sample(row0 + 1, col0),
            v01 = sample(row0, col0 + 1),
            v11 = sample(row0 + 1, col0 + 1),
            fracRow = row - row0,
            fracCol = col - col0,
        )
    }

    private fun sample(row: Int, col: Int): Double? {
        val raw = samples.get(row * gridSize + col).toInt()
        return if (raw == VOID_SAMPLE) null else raw.toDouble()
    }

    companion object {

        /** The value SRTM writes where it has no measurement. Never a real elevation. */
        const val VOID_SAMPLE: Int = -32768

        /**
         * Wraps raw `.hgt` bytes, transparently decompressing a gzip stream first.
         *
         * The gzip branch exists because the tile server serves `.hgt.gz` and a user importing a
         * tile by hand will have downloaded exactly that file; asking them to unzip it first is a
         * step that would be skipped and would fail with an unreadable error.
         */
        fun parse(key: DemTileKey, bytes: ByteArray): HgtTile {
            val raw = if (isGzip(bytes)) gunzip(bytes) else bytes
            require(raw.size >= 8 && raw.size % 2 == 0) {
                "not a .hgt payload: ${raw.size} bytes"
            }
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
            return HgtTile(key, buffer)
        }

        /** True when [bytes] starts with the gzip magic number. */
        fun isGzip(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        /**
         * True when [byteLength] could be a whole `.hgt` grid: an even number of bytes holding a
         * perfect square of posts.
         *
         * Used to reject a truncated transfer the moment it lands rather than three screens later.
         * A half-downloaded tile is still a blob that maps fine and would quietly invent a skyline
         * out of whatever the missing rows decoded to.
         */
        fun isPlausibleGridLength(byteLength: Long): Boolean {
            if (byteLength <= 0L || byteLength % 2L != 0L) return false
            val posts = byteLength / 2L
            // Rounded, not truncated: sqrt of a perfect square can land a hair under the integer.
            val side = Math.round(sqrt(posts.toDouble()))
            return side >= 2L && side * side == posts
        }

        private fun gunzip(bytes: ByteArray): ByteArray =
            GZIPInputStream(bytes.inputStream()).use { input ->
                // 26 MB is the expected size for a 1 arcsecond tile; starting there avoids a dozen
                // array copies on the way up from the default 32 bytes.
                val out = ByteArrayOutputStream(26 * 1024 * 1024)
                input.copyTo(out, DEFAULT_COPY_BUFFER)
                out.toByteArray()
            }

        private const val DEFAULT_COPY_BUFFER = 64 * 1024
    }
}

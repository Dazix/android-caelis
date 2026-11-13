package com.dazix.caelis.domain.horizon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * The `.peaks` tile: every named peak in one 1°×1° cell, as `data_pipeline/generate_peaks.py` writes it.
 *
 * Big-endian, in the style of the profile codec: `CLMK`, a version, a count, then per peak
 * `lat_e6:int lon_e6:int ele_m:short`, the local name (`writeUTF`), and an unsigned byte counting
 * the other names, each a `writeUTF` key (a language, or [LandmarkNames.LATIN]) and a `writeUTF` name.
 * The Python writer is the one that produces real files; [write] exists for tests and for anything
 * that needs to put a tile on disk from Kotlin.
 *
 * No Android imports. The app downloads these from a pinned tag of the data repository, so a change
 * of layout is a new [VERSION] here and in the script, not an edit. Version 1 (local, Czech and
 * English in fixed slots) is refused: a cached v1 tile is then deleted and fetched again as v2.
 */
object LandmarkTileCodec {

    /** `CLMK`. */
    private const val MAGIC = 0x434C4D4B

    private const val VERSION = 2

    /** Every key a tile may carry today is a few letters; a longer one is not a key. */
    private const val MAX_KEY_LENGTH = 8

    private const val E6 = 1_000_000.0

    /**
     * Reads a tile, or returns `null` when the bytes are not a complete, well-formed one.
     *
     * An empty list is a real answer — the region is covered and has no peaks here — and is never
     * confused with `null`, which means "do not trust this file". A truncated download is refused as
     * a whole: half the peaks of a tile would draw as a confident, wrong set of names.
     */
    fun read(input: InputStream): List<LandmarkPeak>? = try {
        DataInputStream(input.buffered()).run {
            if (readInt() != MAGIC) return null
            if (readInt() != VERSION) return null
            val count = readInt()
            if (count < 0) return null

            // Not preallocated from [count]: a corrupt header must end in an EOF, not an OutOfMemoryError.
            val peaks = ArrayList<LandmarkPeak>()
            repeat(count) {
                val latE6 = readInt()
                val lonE6 = readInt()
                val elevation = readShort().toInt()
                val name = readUTF()
                val others = readUnsignedByte()
                // A key the app does not use yet (a language added to the data before the app) is
                // kept like any other: it costs a few bytes and is simply never asked for.
                val names = HashMap<String, String>(others * 2)
                repeat(others) {
                    val key = readUTF()
                    val value = readUTF()
                    if (key.isEmpty() || key.length > MAX_KEY_LENGTH || value.isEmpty()) return null
                    if (names.put(key, value) != null) return null
                }

                if (latE6 !in -90_000_000..90_000_000) return null
                if (lonE6 !in -180_000_000..180_000_000) return null
                if (name.isEmpty()) return null

                peaks += LandmarkPeak(latE6 / E6, lonE6 / E6, elevation, name, names)
            }
            if (read() != -1) return null
            peaks
        }
    } catch (_: IOException) {
        // EOFException and UTFDataFormatException are both IOExceptions.
        null
    }

    fun write(out: OutputStream, peaks: List<LandmarkPeak>) {
        DataOutputStream(out.buffered()).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeInt(peaks.size)
            for (peak in peaks) {
                writeInt((peak.latitude * E6).roundToInt())
                writeInt((peak.longitude * E6).roundToInt())
                writeShort(peak.elevationMeters)
                writeUTF(peak.name)
                val names = peak.names.entries.sortedBy { it.key }
                require(names.size <= 0xFF) { "at most 255 other names, got ${names.size}" }
                writeByte(names.size)
                for ((key, value) in names) {
                    writeUTF(key)
                    writeUTF(value)
                }
            }
            flush()
        }
    }
}

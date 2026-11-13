package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * On-disk form of a [HorizonProfile]: about 5.8 kB of big-endian primitives, plus the few named peaks.
 *
 * Deliberately hand-rolled rather than serialised through a library. This is the one artefact of the
 * terrain pipeline that is kept forever, it has to survive app updates, and a format that is eight
 * fields and an array is cheaper to keep stable than a dependency is.
 *
 * No Android imports, so the round-trip is held down by a plain JVM test.
 */
object HorizonProfileCodec {

    /** `CAHZ` — enough to tell a truncated profile from a JPEG someone dropped in the folder. */
    private const val MAGIC = 0x4341485A

    /**
     * 2 added the coverage count. 3 appended the named peaks. 4 stores each peak's names as a list
     * of (language, name), as the v2 peak tiles carry them, in place of three fixed slots.
     *
     * A version bump is how a v1 profile — which could be the flat, observer-tile-missing kind —
     * is discarded and recomputed rather than trusted. [read] returns null on any version it does not
     * know. Versions 2 and 3 are still read, as a profile whose peaks are not resolved yet: its
     * skyline is as good as ever and throwing it away would cost a 26 MB download to learn the same
     * ridge. A v3 profile keeps its Czech and English names until the peak tiles name it again with
     * every language.
     */
    private const val VERSION = 4
    private const val VERSION_WITH_TWO_NAMES = 3
    private const val VERSION_WITHOUT_LANDMARKS = 2

    /** Bounds a corrupt count; a tile carries a handful of names per peak. */
    private const val MAX_NAMES = 0xFF

    /** A sanity bound, not a limit anyone reaches: the selector keeps a dozen. */
    private const val MAX_LANDMARKS = 1000

    fun write(out: OutputStream, profile: HorizonProfile) {
        DataOutputStream(out.buffered()).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeDouble(profile.observer.latitude)
            writeDouble(profile.observer.longitude)
            writeDouble(profile.observer.altitudeMeters)
            writeDouble(profile.observerGroundElevationMeters)
            writeDouble(profile.eyeHeightMeters)
            writeDouble(profile.maxRangeMeters)
            writeLong(profile.generatedAtEpochMillis)
            writeInt(profile.coveredSamples)
            writeInt(profile.elevationsDegrees.size)
            profile.elevationsDegrees.forEach { writeFloat(it) }
            writeBoolean(profile.landmarksResolved)
            writeInt(profile.landmarks.size)
            for (landmark in profile.landmarks) {
                writeUTF(landmark.name)
                val names = landmark.names.entries.sortedBy { it.key }
                require(names.size <= MAX_NAMES) { "at most $MAX_NAMES other names, got ${names.size}" }
                writeByte(names.size)
                for ((key, value) in names) {
                    writeUTF(key)
                    writeUTF(value)
                }
                writeInt(landmark.elevationMeters)
                writeDouble(landmark.distanceMeters)
                writeDouble(landmark.azimuthDegrees)
                writeDouble(landmark.apparentElevationDegrees)
            }
            flush()
        }
    }

    /**
     * Reads a profile back, or returns `null` when the bytes are not one.
     *
     * A cached profile is a convenience, never the source of truth: a corrupt or half-written file
     * must degrade into "compute it again", not into a crash on the AR screen. Every failure mode —
     * wrong magic, future version, truncated array — therefore lands on the same `null`.
     */
    fun read(input: InputStream): HorizonProfile? = try {
        DataInputStream(input.buffered()).run {
            if (readInt() != MAGIC) return null
            val version = readInt()
            if (version != VERSION && version != VERSION_WITH_TWO_NAMES && version != VERSION_WITHOUT_LANDMARKS) return null

            val latitude = readDouble()
            val longitude = readDouble()
            val altitude = readDouble()
            val ground = readDouble()
            val eyeHeight = readDouble()
            val maxRange = readDouble()
            val generatedAt = readLong()
            val covered = readInt()

            val count = readInt()
            if (count != HorizonProfile.SAMPLE_COUNT) return null

            val elevations = FloatArray(count)
            for (i in 0 until count) elevations[i] = readFloat()

            var resolved = false
            val landmarks = ArrayList<HorizonLandmark>()
            if (version != VERSION_WITHOUT_LANDMARKS) {
                // A v3 profile was named from v1 tiles, Czech and English only: shown as it is, and
                // named again, so a German or Polish reader gets their names without a new skyline.
                resolved = readBoolean() && version == VERSION
                val landmarkCount = readInt()
                if (landmarkCount !in 0..MAX_LANDMARKS) return null
                repeat(landmarkCount) {
                    val name = readUTF()
                    val names = if (version == VERSION) readNames() ?: return null else readTwoNames()
                    landmarks += HorizonLandmark(
                        name = name,
                        names = names,
                        elevationMeters = readInt(),
                        distanceMeters = readDouble(),
                        azimuthDegrees = readDouble(),
                        apparentElevationDegrees = readDouble(),
                    )
                }
            }

            HorizonProfile(
                elevationsDegrees = elevations,
                observer = GeoLocation(latitude, longitude, altitude),
                observerGroundElevationMeters = ground,
                eyeHeightMeters = eyeHeight,
                maxRangeMeters = maxRange,
                generatedAtEpochMillis = generatedAt,
                coveredSamples = covered,
                landmarks = landmarks,
                landmarksResolved = resolved,
            )
        }
    } catch (e: EOFException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // GeoLocation rejects coordinates off the globe, and HorizonProfile a wrong sample count.
        null
    }

    /** The v4 list of (key, name), or null when it repeats a key or holds an empty entry. */
    private fun DataInputStream.readNames(): Map<String, String>? {
        val count = readUnsignedByte()
        val names = HashMap<String, String>(count * 2)
        repeat(count) {
            val key = readUTF()
            val value = readUTF()
            if (key.isEmpty() || value.isEmpty() || names.put(key, value) != null) return null
        }
        return names
    }

    /** The v3 Czech and English slots, an empty one left out. */
    private fun DataInputStream.readTwoNames(): Map<String, String> {
        val cs = readUTF()
        val en = readUTF()
        return buildMap {
            if (cs.isNotEmpty()) put("cs", cs)
            if (en.isNotEmpty()) put("en", en)
        }
    }

    /**
     * File name a profile is stored under.
     *
     * The observer position is rounded to three decimal places — about 110 m — so walking around a
     * viewpoint reuses the profile instead of recomputing it, while moving to the next valley does
     * not. Range and eye height are part of the name because changing either changes the skyline,
     * and a stale file under the same name would silently be the wrong answer.
     */
    fun fileName(
        latitude: Double,
        longitude: Double,
        maxRangeKm: Int,
        eyeHeightCm: Int,
    ): String = String.format(
        java.util.Locale.US,
        "%.3f_%.3f_r%d_e%d.bin",
        latitude,
        longitude,
        maxRangeKm,
        eyeHeightCm,
    )
}

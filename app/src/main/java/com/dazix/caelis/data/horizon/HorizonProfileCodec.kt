package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.HorizonLandmark
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.RidgeSamples
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * On-disk form of a [HorizonProfile]: about 5.8 kB of big-endian primitives, plus the few named peaks
 * and the ridge samples (some 20-40 kB in the mountains, at most ~70 kB).
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
     * 2 added the coverage count. 3 appended the named peaks. 4 appended the ridge samples.
     *
     * A version bump is how a v1 profile — which could be the flat, observer-tile-missing kind —
     * is discarded and recomputed rather than trusted. [read] returns null on any version it does not
     * know. Version 2 is still read, as a profile whose peaks are not resolved yet: its skyline is
     * as good as ever and throwing it away would cost a 26 MB download to learn the same ridge.
     * Version 3 is read with no ridges for the same reason; the repository recomputes it when the
     * tiles are still cached, and otherwise the skyline is drawn as one ring, as before.
     */
    private const val VERSION = 4
    private const val VERSION_WITHOUT_RIDGES = 3
    private const val VERSION_WITHOUT_LANDMARKS = 2

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
                writeUTF(landmark.nameCs)
                writeUTF(landmark.nameEn)
                writeInt(landmark.elevationMeters)
                writeDouble(landmark.distanceMeters)
                writeDouble(landmark.azimuthDegrees)
                writeDouble(landmark.apparentElevationDegrees)
            }
            val ridges = profile.ridges
            writeBoolean(ridges != null)
            if (ridges != null) {
                for (i in 0 until HorizonProfile.SAMPLE_COUNT) {
                    writeByte(ridges.count(i))
                    for (k in ridges.offsets[i] until ridges.offsets[i + 1]) {
                        writeFloat(ridges.distancesMeters[k])
                        writeFloat(ridges.altitudesDegrees[k])
                    }
                }
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
            if (version != VERSION && version != VERSION_WITHOUT_RIDGES && version != VERSION_WITHOUT_LANDMARKS) return null

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
            if (version >= VERSION_WITHOUT_RIDGES) {
                resolved = readBoolean()
                val landmarkCount = readInt()
                if (landmarkCount !in 0..MAX_LANDMARKS) return null
                repeat(landmarkCount) {
                    landmarks += HorizonLandmark(
                        name = readUTF(),
                        nameCs = readUTF(),
                        nameEn = readUTF(),
                        elevationMeters = readInt(),
                        distanceMeters = readDouble(),
                        azimuthDegrees = readDouble(),
                        apparentElevationDegrees = readDouble(),
                    )
                }
            }

            var ridges: RidgeSamples? = null
            if (version == VERSION && readBoolean()) ridges = readRidges() ?: return null

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
                ridges = ridges,
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

    /** The ridge block, or null when a count is out of range or a value is not a finite number. */
    private fun DataInputStream.readRidges(): RidgeSamples? {
        val offsets = IntArray(HorizonProfile.SAMPLE_COUNT + 1)
        val distances = FloatArray(HorizonProfile.SAMPLE_COUNT * (RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 1))
        val altitudes = FloatArray(distances.size)
        var n = 0
        for (i in 0 until HorizonProfile.SAMPLE_COUNT) {
            val count = readByte().toInt()
            if (count !in 0..RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 1) return null
            repeat(count) {
                val distance = readFloat()
                val altitude = readFloat()
                if (!distance.isFinite() || !altitude.isFinite()) return null
                distances[n] = distance
                altitudes[n] = altitude
                n++
            }
            offsets[i + 1] = n
        }
        return RidgeSamples(offsets, distances.copyOf(n), altitudes.copyOf(n))
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

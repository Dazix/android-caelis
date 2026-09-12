package com.dazix.caelis.data.horizon

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * On-disk form of a [HorizonProfile]: about 5.8 kB of big-endian primitives.
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
     * 2 added the coverage count.
     *
     * A version bump is how a v1 profile — which could be the flat, observer-tile-missing kind —
     * is discarded and recomputed rather than trusted. [read] returns null on any other version.
     */
    private const val VERSION = 2

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
            if (readInt() != VERSION) return null

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

            HorizonProfile(
                elevationsDegrees = elevations,
                observer = GeoLocation(latitude, longitude, altitude),
                observerGroundElevationMeters = ground,
                eyeHeightMeters = eyeHeight,
                maxRangeMeters = maxRange,
                generatedAtEpochMillis = generatedAt,
                coveredSamples = covered,
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

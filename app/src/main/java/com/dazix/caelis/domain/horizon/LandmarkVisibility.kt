package com.dazix.caelis.domain.horizon

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides which named peaks are worth printing on the observer's real skyline.
 *
 * A peak list knows where summits are, not what an observer can see. Naming every summit inside the
 * profile's range would label mountains hidden behind the hill in front, and slopes and saddles that
 * merely happen to lie on the line. The rule is therefore the profile's own: **a peak is drawn only
 * when it is the skyline there** — its apparent altitude reaches the ridge at its azimuth, and that
 * ridge has a top within half a degree. Whatever survives is thinned to a handful of labels, because
 * a skyline with forty names is a wall of text.
 *
 * Pure Kotlin, no Android imports.
 */
object LandmarkVisibility {

    const val DEFAULT_MAX_LABELS: Int = 12

    /** Closer than this a summit is the ground the observer is standing on, not a skyline feature. */
    const val MIN_DISTANCE_METERS: Double = 500.0

    /** Two labels nearer than this in azimuth would overprint at any practical field of view. */
    const val MIN_SEPARATION_DEGREES: Double = 2.0

    /**
     * How far the OSM `ele` of a summit and the elevation model's surface may disagree, as height.
     *
     * Converted to an angle at the peak's own distance: the same 30 m is 0.17° at 10 km and 0.03° at
     * 60 km, so a fixed angle would be too strict near and too lax far. Without any slack a peak the
     * model rounds down by a few metres is refused although it is plainly the ridge.
     */
    private const val HEIGHT_TOLERANCE_METERS = 30.0
    private const val MIN_TOLERANCE_DEGREES = 0.1
    private const val MAX_TOLERANCE_DEGREES = 1.5

    /** How far above the sampled ridge a narrow summit can honestly stand: it falls between two samples. */
    private const val SAMPLING_SLACK_DEGREES = 0.5

    /** The profile is sampled every 0.25°, so the ridge at an azimuth is the best of its neighbours. */
    private const val RIDGE_REACH_DEGREES = HorizonProfile.STEP_DEGREES

    /** A summit must have a top of the skyline this close, which is what separates it from a slope. */
    private const val TOP_REACH_DEGREES = 0.5

    /** The surroundings a summit's standing-out is measured against. */
    private const val RELIEF_WINDOW_DEGREES = 5.0

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    /**
     * The peaks of [peaks] that stand on [profile]'s skyline, at most [maxLabels], in azimuth order.
     *
     * Returns nothing for a profile that is not [HorizonProfile.isUsable]: its ridge is the geometric
     * dip wearing a terrain colour, and a peak "on" it would be placed on a skyline that is not there.
     */
    fun select(
        peaks: List<LandmarkPeak>,
        profile: HorizonProfile,
        maxLabels: Int = DEFAULT_MAX_LABELS,
    ): List<HorizonLandmark> {
        if (maxLabels <= 0 || !profile.isUsable) return emptyList()

        val eye = profile.observerEyeElevationMeters
        val observer = profile.observer
        val candidates = ArrayList<Ranked>()

        for (peak in peaks) {
            val distance = distanceMeters(observer.latitude, observer.longitude, peak.latitude, peak.longitude)
            if (distance < MIN_DISTANCE_METERS || distance > profile.maxRangeMeters) continue

            val azimuth = bearingDegrees(observer.latitude, observer.longitude, peak.latitude, peak.longitude)

            // Same radius and same refraction as the profile, so a summit and the ridge it forms are
            // compared in one geometry: curvature and refraction counted once, on both sides.
            val drop = distance * distance / (2.0 * HorizonProfileCalculator.EFFECTIVE_EARTH_RADIUS_METERS)
            val altitude = atan2(peak.elevationMeters - eye - drop, distance) * DEG

            val tolerance = (atan(HEIGHT_TOLERANCE_METERS / distance) * DEG)
                .coerceIn(MIN_TOLERANCE_DEGREES, MAX_TOLERANCE_DEGREES)
            val ridge = ridgeAt(profile, azimuth)
            if (altitude < ridge - tolerance) continue
            // And not far above it either: a summit well over every sample near it sits where the profile
            // has a gap or the edge of covered data, and a name pinned there would float over a skyline
            // that is not drawn. The slack is wider than the height tolerance because the 0.25 degree sampling
            // alone can leave a narrow real summit a few tenths of a degree above the samples beside it.
            if (altitude > ridge + tolerance + SAMPLING_SLACK_DEGREES) continue
            if (!hasTopNear(profile, azimuth)) continue

            candidates += Ranked(
                landmark = HorizonLandmark(
                    name = peak.name,
                    names = peak.names,
                    elevationMeters = peak.elevationMeters,
                    distanceMeters = distance,
                    azimuthDegrees = azimuth,
                    apparentElevationDegrees = altitude,
                ),
                relief = altitude - lowestNear(profile, azimuth),
            )
        }

        // Most striking first: how far a summit stands above the skyline around it, then how high it is.
        candidates.sortWith(
            compareByDescending<Ranked> { it.relief }.thenByDescending { it.landmark.elevationMeters },
        )

        val chosen = ArrayList<HorizonLandmark>(min(maxLabels, candidates.size))
        for (candidate in candidates) {
            if (chosen.size == maxLabels) break
            val clear = chosen.none {
                azimuthDelta(it.azimuthDegrees, candidate.landmark.azimuthDegrees) < MIN_SEPARATION_DEGREES
            }
            if (clear) chosen += candidate.landmark
        }
        chosen.sortBy { it.azimuthDegrees }
        return chosen
    }

    private class Ranked(val landmark: HorizonLandmark, val relief: Double)

    /** The highest skyline sample within one sample step of [azimuth]. */
    private fun ridgeAt(profile: HorizonProfile, azimuth: Double): Double {
        var best = -Double.MAX_VALUE
        forEachIndexNear(azimuth, RIDGE_REACH_DEGREES) { best = maxOf(best, profile.elevationsDegrees[it].toDouble()) }
        return best
    }

    private fun lowestNear(profile: HorizonProfile, azimuth: Double): Double {
        var lowest = Double.MAX_VALUE
        forEachIndexNear(azimuth, RELIEF_WINDOW_DEGREES) { lowest = minOf(lowest, profile.elevationsDegrees[it].toDouble()) }
        return lowest
    }

    /**
     * Whether some sample within [TOP_REACH_DEGREES] of [azimuth] is a top of the skyline: at least as
     * high as both neighbours and higher than one. A monotone slope has none, and a perfectly flat
     * skyline (a sea horizon) has none either, so neither is labelled.
     */
    private fun hasTopNear(profile: HorizonProfile, azimuth: Double): Boolean {
        val samples = profile.elevationsDegrees
        var found = false
        forEachIndexNear(azimuth, TOP_REACH_DEGREES) { if (isTop(samples, it)) found = true }
        return found
    }

    /**
     * [landmark] moved onto the top of [profile]'s skyline it stands on: the azimuth and altitude of
     * the sample within [TOP_REACH_DEGREES] of the summit's bearing that is a top, the nearest one
     * (the higher on a tie). The skyline is drawn through its samples, so the tick then stands on the
     * drawn top. At the summit's own bearing it stood up to half a degree beside it, and always
     * between two samples, which on the device read as a tick a few pixels off the hill. Unchanged
     * when no top is that close ([select] never keeps such a peak, but a stored list may predate it).
     * A pinned landmark pins to itself.
     */
    fun pinToSkyline(landmark: HorizonLandmark, profile: HorizonProfile): HorizonLandmark {
        val samples = profile.elevationsDegrees
        val azimuth = landmark.azimuthDegrees
        var best = -1
        var bestDelta = Double.MAX_VALUE
        forEachIndexNear(azimuth, TOP_REACH_DEGREES) {
            if (isTop(samples, it)) {
                val delta = azimuthDelta(HorizonProfile.azimuthForIndex(it), azimuth)
                val closer = delta < bestDelta - 1e-9
                val tiedHigher = delta <= bestDelta + 1e-9 && best >= 0 && samples[it] > samples[best]
                if (best < 0 || closer || tiedHigher) {
                    best = it
                    bestDelta = delta
                }
            }
        }
        if (best < 0) return landmark
        return landmark.copy(
            azimuthDegrees = HorizonProfile.azimuthForIndex(best),
            apparentElevationDegrees = samples[best].toDouble(),
        )
    }

    /** A top of the skyline: at least as high as both neighbours and higher than one. */
    private fun isTop(samples: FloatArray, index: Int): Boolean {
        val count = samples.size
        val here = samples[index]
        val before = samples[(index + count - 1) % count]
        val after = samples[(index + 1) % count]
        return here >= before && here >= after && (here > before || here > after)
    }

    /** Calls [action] with every sample index within [reachDegrees] of [azimuth], wrapping across north. */
    private inline fun forEachIndexNear(azimuth: Double, reachDegrees: Double, action: (Int) -> Unit) {
        val count = HorizonProfile.SAMPLE_COUNT
        val first = ceil((azimuth - reachDegrees) / HorizonProfile.STEP_DEGREES - 1e-9).toInt()
        val last = floor((azimuth + reachDegrees) / HorizonProfile.STEP_DEGREES + 1e-9).toInt()
        for (i in first..last) action(((i % count) + count) % count)
    }

    /** Great-circle distance on the mean Earth radius; the profile's own geometry handles the rest. */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * RAD
        val dLon = (lon2 - lon1) * RAD
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * RAD) * cos(lat2 * RAD) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * HorizonProfileCalculator.EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Initial great-circle bearing from the first point to the second, clockwise from true North. */
    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * RAD
        val phi2 = lat2 * RAD
        val dLon = (lon2 - lon1) * RAD
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return HorizonProfile.normalizeAzimuth(atan2(y, x) * DEG)
    }
}

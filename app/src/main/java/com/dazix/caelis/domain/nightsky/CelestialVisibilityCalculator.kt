package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.tan

/**
 * Calculated 24-hour daily visibility schedule for a celestial body (Star, Planet, DSO, or Constellation)
 * factoring in the observer's geographic location, astronomical refraction, and physical terrain skyline.
 */
sealed interface CelestialVisibility {
    /**
     * The celestial object rises and sets during the 24-hour daily passage centered around culmination.
     *
     * @param riseInstant The moment the object rises above the terrain / horizon.
     * @param transitInstant The moment the object crosses the local meridian (upper culmination / peak altitude).
     * @param setInstant The moment the object sets behind the terrain / horizon.
     * @param maxElevationDegrees Peak apparent elevation at culmination.
     * @param riseDelayFromAstronomical Delay in rise time caused by physical terrain elevation.
     * @param setDelayFromAstronomical Advance in set time caused by physical terrain elevation.
     * @param isObscuredByTerrain True if physical terrain significantly delayed rise or advanced set (> 60s).
     */
    data class RisesAndSets(
        val riseInstant: Instant,
        val transitInstant: Instant,
        val setInstant: Instant,
        val maxElevationDegrees: Double,
        val riseDelayFromAstronomical: Duration? = null,
        val setDelayFromAstronomical: Duration? = null,
        val isObscuredByTerrain: Boolean = false,
    ) : CelestialVisibility

    /**
     * Circumpolar object: stays continuously above the horizon and physical terrain for the entire 24-hour passage.
     *
     * @param transitInstant The moment of upper culmination.
     * @param maxElevationDegrees Maximum elevation at upper culmination.
     * @param minElevationDegrees Minimum elevation at lower culmination (nadir).
     */
    data class AlwaysAboveHorizon(
        val transitInstant: Instant,
        val maxElevationDegrees: Double,
        val minElevationDegrees: Double,
    ) : CelestialVisibility

    /**
     * Object never rises above the horizon or physical terrain for the observer's latitude.
     *
     * @param transitInstant The moment of culmination (highest point, still below horizon).
     * @param maxElevationDegrees Maximum elevation reached (<= 0° or below terrain).
     */
    data class AlwaysBelowHorizon(
        val transitInstant: Instant,
        val maxElevationDegrees: Double,
    ) : CelestialVisibility
}

/**
 * UI loading and error state wrapper for [CelestialVisibility].
 */
sealed class VisibilityState {
    data object Loading : VisibilityState()
    data class Loaded(val visibility: CelestialVisibility) : VisibilityState()
    data object Error : VisibilityState()
}

/**
 * Pure Kotlin calculator for celestial body daily visibility schedules (Rise, Culmination, Set).
 *
 * Integrates Meeus sidereal time, equatorial-to-horizontal spherical coordinate transformations,
 * Saemundsson atmospheric refraction, and bisection-refined terrain horizon intersection.
 */
object CelestialVisibilityCalculator {

    private const val RAD = Math.PI / 180.0
    // Sidereal rotation rate: ~360.98564736629 degrees per 86400 seconds (~0.0041780746 deg/s)
    private const val SIDEREAL_DEG_PER_SEC = 360.98564736629 / 86400.0
    private const val REFINEMENT_SECONDS = 1L
    private const val SAMPLING_STEP_SECONDS = 120L // 2-minute sampling interval

    /**
     * Calculates the daily visibility schedule for a celestial body on a specified local calendar date ([targetDate]).
     * Anchors the calculation at local noon to ensure a continuous 24-hour sampling window across the daily passage.
     *
     * @param raDegrees Right Ascension in degrees [0.0, 360.0).
     * @param decDegrees Declination in degrees [-90.0, +90.0].
     * @param targetDate Local calendar date to calculate visibility for.
     * @param location Observer's geographic latitude and longitude.
     * @param profile Optional terrain horizon profile for real-world skyline obstruction calculations.
     * @param zoneId Time zone of the observer, defaults to system default.
     */
    fun calculateVisibility(
        raDegrees: Double,
        decDegrees: Double,
        targetDate: LocalDate,
        location: GeoLocation,
        profile: HorizonProfile? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CelestialVisibility {
        val referenceInstant = targetDate.atTime(12, 0).atZone(zoneId).toInstant()
        return calculateVisibility(raDegrees, decDegrees, referenceInstant, location, profile)
    }

    /**
     * Calculates the daily visibility schedule for a celestial body at equatorial coordinates ([raDegrees], [decDegrees]).
     *
     * @param raDegrees Right Ascension in degrees [0.0, 360.0).
     * @param decDegrees Declination in degrees [-90.0, +90.0].
     * @param referenceInstant Reference UTC time (e.g. current live or virtual time).
     * @param location Observer's geographic latitude and longitude.
     * @param profile Optional terrain horizon profile for real-world skyline obstruction calculations.
     */
    fun calculateVisibility(
        raDegrees: Double,
        decDegrees: Double,
        referenceInstant: Instant,
        location: GeoLocation,
        profile: HorizonProfile? = null,
    ): CelestialVisibility {
        // 1. Calculate the exact transit (upper culmination) instant where LST == RA
        val transitInstant = computeTransitInstant(raDegrees, referenceInstant, location.longitude)

        // 2. Sample 24-hour trajectory centered around transit: [transit - 12h, transit + 12h]
        // This guarantees that endpoints are near lower culmination (nadir), making the rise/culmination/set
        // curve completely continuous without midnight wrap-around discontinuities.
        val startInstant = transitInstant.minus(Duration.ofHours(12))
        val endInstant = transitInstant.plus(Duration.ofHours(12))

        val points = mutableListOf<SamplePoint>()
        var curSec = startInstant.epochSecond
        val endSec = endInstant.epochSecond

        while (curSec <= endSec) {
            val inst = Instant.ofEpochSecond(curSec)
            val lst = SiderealTime.calculateLST(SiderealTime.toJulianDate(inst), location.longitude)
            val (az, el) = EquatorialCoordinates.equatorialToHorizontal(raDegrees, decDegrees, lst, location.latitude)
            val apparentEl = applyRefraction(el)
            val terrainEl = profile?.elevationAt(az) ?: 0.0
            val isAbove = if (profile != null) apparentEl >= terrainEl else apparentEl >= 0.0
            val isAboveAstro = apparentEl >= 0.0

            points.add(
                SamplePoint(
                    instant = inst,
                    azimuthDeg = az,
                    elevationDeg = el,
                    apparentElevationDeg = apparentEl,
                    terrainElevationDeg = terrainEl,
                    isAbove = isAbove,
                    isAboveAstronomical = isAboveAstro,
                )
            )
            curSec += SAMPLING_STEP_SECONDS
        }

        // Compute peak elevation at transit
        val transitLst = SiderealTime.calculateLST(SiderealTime.toJulianDate(transitInstant), location.longitude)
        val (_, transitEl) = EquatorialCoordinates.equatorialToHorizontal(raDegrees, decDegrees, transitLst, location.latitude)
        val maxApparentElevation = applyRefraction(transitEl)

        // Compute nadir elevation (minimum)
        val nadirLst = SiderealTime.calculateLST(SiderealTime.toJulianDate(startInstant), location.longitude)
        val (_, nadirEl) = EquatorialCoordinates.equatorialToHorizontal(raDegrees, decDegrees, nadirLst, location.latitude)
        val minApparentElevation = applyRefraction(nadirEl)

        // 3. Check edge cases
        val allAbove = points.all { it.isAbove }
        if (allAbove) {
            return CelestialVisibility.AlwaysAboveHorizon(
                transitInstant = transitInstant,
                maxElevationDegrees = maxApparentElevation,
                minElevationDegrees = minApparentElevation,
            )
        }

        val allBelow = points.none { it.isAbove }
        if (allBelow) {
            return CelestialVisibility.AlwaysBelowHorizon(
                transitInstant = transitInstant,
                maxElevationDegrees = maxApparentElevation,
            )
        }

        // 4. Find Rise (before transit) and Set (after transit)
        // Crossing from below to above in [startInstant, transitInstant]
        var riseCrossing: Instant? = null
        var astroRiseCrossing: Instant? = null

        val transitIndex = points.indexOfFirst { it.instant >= transitInstant }.coerceAtLeast(1)

        for (i in 1..transitIndex) {
            val prev = points[i - 1]
            val curr = points[i]
            if (!prev.isAbove && curr.isAbove && riseCrossing == null) {
                riseCrossing = refineCrossing(
                    earlier = prev.instant,
                    later = curr.instant,
                    raDegrees = raDegrees,
                    decDegrees = decDegrees,
                    location = location,
                    profile = profile,
                )
            }
            if (!prev.isAboveAstronomical && curr.isAboveAstronomical && astroRiseCrossing == null) {
                astroRiseCrossing = refineCrossing(
                    earlier = prev.instant,
                    later = curr.instant,
                    raDegrees = raDegrees,
                    decDegrees = decDegrees,
                    location = location,
                    profile = null,
                )
            }
        }

        // Crossing from above to below in [transitInstant, endInstant]
        var setCrossing: Instant? = null
        var astroSetCrossing: Instant? = null

        for (i in transitIndex until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (prev.isAbove && !curr.isAbove && setCrossing == null) {
                setCrossing = refineCrossing(
                    earlier = prev.instant,
                    later = curr.instant,
                    raDegrees = raDegrees,
                    decDegrees = decDegrees,
                    location = location,
                    profile = profile,
                )
            }
            if (prev.isAboveAstronomical && !curr.isAboveAstronomical && astroSetCrossing == null) {
                astroSetCrossing = refineCrossing(
                    earlier = prev.instant,
                    later = curr.instant,
                    raDegrees = raDegrees,
                    decDegrees = decDegrees,
                    location = location,
                    profile = null,
                )
            }
        }

        // Fallbacks if one edge crossed right at nadir/transit boundary
        val finalRise = riseCrossing ?: startInstant
        val finalSet = setCrossing ?: endInstant

        val riseDelay = if (profile != null && astroRiseCrossing != null) {
            Duration.between(astroRiseCrossing, finalRise)
        } else null

        val setDelay = if (profile != null && astroSetCrossing != null) {
            Duration.between(finalSet, astroSetCrossing)
        } else null

        val isObscured = (riseDelay != null && riseDelay.seconds > 60) ||
                (setDelay != null && setDelay.seconds > 60)

        return CelestialVisibility.RisesAndSets(
            riseInstant = finalRise,
            transitInstant = transitInstant,
            setInstant = finalSet,
            maxElevationDegrees = maxApparentElevation,
            riseDelayFromAstronomical = riseDelay,
            setDelayFromAstronomical = setDelay,
            isObscuredByTerrain = isObscured,
        )
    }

    /**
     * Calculates the nearest transit (upper culmination) instant where LST == RA.
     */
    fun computeTransitInstant(raDegrees: Double, referenceInstant: Instant, longitudeDegrees: Double): Instant {
        val refJd = SiderealTime.toJulianDate(referenceInstant)
        val currentLst = SiderealTime.calculateLST(refJd, longitudeDegrees)

        var deltaAngle = (raDegrees - currentLst) % 360.0
        if (deltaAngle > 180.0) deltaAngle -= 360.0
        if (deltaAngle < -180.0) deltaAngle += 360.0

        // Initial estimate in seconds
        val deltaSeconds = deltaAngle / SIDEREAL_DEG_PER_SEC
        val estInstant = referenceInstant.plusMillis((deltaSeconds * 1000.0).toLong())

        // Refine with exact SiderealTime Meeus formula
        val estJd = SiderealTime.toJulianDate(estInstant)
        val estLst = SiderealTime.calculateLST(estJd, longitudeDegrees)
        var residual = (raDegrees - estLst) % 360.0
        if (residual > 180.0) residual -= 360.0
        if (residual < -180.0) residual += 360.0

        val correctionSeconds = residual / SIDEREAL_DEG_PER_SEC
        return estInstant.plusMillis((correctionSeconds * 1000.0).toLong())
    }

    /**
     * Bisects the bracket down to 1-second accuracy to find the exact horizon/terrain crossing moment.
     */
    private fun refineCrossing(
        earlier: Instant,
        later: Instant,
        raDegrees: Double,
        decDegrees: Double,
        location: GeoLocation,
        profile: HorizonProfile?,
    ): Instant {
        var low = earlier
        var high = later
        val startAbove = isAboveHorizon(low, raDegrees, decDegrees, location, profile)

        while (Duration.between(low, high).seconds > REFINEMENT_SECONDS) {
            val mid = low.plusSeconds(Duration.between(low, high).seconds / 2)
            val midAbove = isAboveHorizon(mid, raDegrees, decDegrees, location, profile)
            if (midAbove == startAbove) low = mid else high = mid
        }
        return high
    }

    private fun isAboveHorizon(
        instant: Instant,
        raDegrees: Double,
        decDegrees: Double,
        location: GeoLocation,
        profile: HorizonProfile?,
    ): Boolean {
        val lst = SiderealTime.calculateLST(SiderealTime.toJulianDate(instant), location.longitude)
        val (az, el) = EquatorialCoordinates.equatorialToHorizontal(raDegrees, decDegrees, lst, location.latitude)
        val apparentEl = applyRefraction(el)
        return if (profile != null) {
            apparentEl >= profile.elevationAt(az)
        } else {
            apparentEl >= 0.0
        }
    }

    /**
     * Applies Saemundsson (1986) atmospheric refraction formula.
     * Valid for true elevation >= -0.8333°.
     */
    fun applyRefraction(elevationDeg: Double): Double {
        return if (elevationDeg in -0.8333..85.0) {
            val rArcmin = 1.02 / tan((elevationDeg + 10.3 / (elevationDeg + 5.11)) * RAD)
            elevationDeg + maxOf(0.0, rArcmin / 60.0)
        } else {
            elevationDeg
        }
    }

    private data class SamplePoint(
        val instant: Instant,
        val azimuthDeg: Double,
        val elevationDeg: Double,
        val apparentElevationDeg: Double,
        val terrainElevationDeg: Double,
        val isAbove: Boolean,
        val isAboveAstronomical: Boolean,
    )
}

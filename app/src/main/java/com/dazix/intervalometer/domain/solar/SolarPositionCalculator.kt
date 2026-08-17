package com.dazix.intervalometer.domain.solar

import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure Kotlin astronomical solar position calculator.
 *
 * Implements high-precision solar coordinates based on the NOAA Solar Position Algorithm
 * and Jean Meeus Astronomical Algorithms. Accurate to < 0.01 degrees.
 */
object SolarPositionCalculator {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    /**
     * Computes the Sun's horizontal coordinates (Azimuth and Elevation) for a given UTC [instant]
     * and observer [location].
     */
    fun computePosition(instant: Instant, location: GeoLocation): SolarCoordinates {
        val epochSeconds = instant.epochSecond + instant.nano / 1e9
        val jd = 2440587.5 + epochSeconds / 86400.0
        val t = (jd - 2451545.0) / 36525.0

        // Geometric mean longitude of the Sun (degrees)
        val l0 = normalizeDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t)

        // Mean anomaly of the Sun (degrees)
        val m = normalizeDegrees(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = m * RAD

        // Sun's Equation of Center
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2.0 * mRad) +
            0.000289 * sin(3.0 * mRad)

        // True solar longitude (degrees)
        val sunTrueLong = l0 + c

        // Apparent longitude (degrees)
        val omega = 125.04 - 1934.136 * t
        val lambda = sunTrueLong - 0.00569 - 0.00478 * sin(omega * RAD)
        val lambdaRad = lambda * RAD

        // Mean and corrected obliquity of the ecliptic
        val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
            (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0
        val eps = eps0 + 0.00256 * cos(omega * RAD)
        val epsRad = eps * RAD

        // Sun Declination (radians)
        val sinDelta = sin(epsRad) * sin(lambdaRad)
        val deltaRad = asin(sinDelta)

        // Sun Right Ascension (degrees)
        val yRa = cos(epsRad) * sin(lambdaRad)
        val xRa = cos(lambdaRad)
        val raDeg = normalizeDegrees(atan2(yRa, xRa) * DEG)

        // Greenwich Mean Sidereal Time (degrees)
        val gmstDeg = normalizeDegrees(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - (t * t * t) / 38710000.0)

        // Local Sidereal Time (degrees)
        val lstDeg = normalizeDegrees(gmstDeg + location.longitude)

        // Local Hour Angle (degrees in [-180, 180])
        var haDeg = (lstDeg - raDeg) % 360.0
        if (haDeg > 180.0) haDeg -= 360.0
        if (haDeg < -180.0) haDeg += 360.0
        val haRad = haDeg * RAD

        val latRad = location.latitude * RAD

        // Elevation / Altitude (degrees)
        val sinElevation = sin(latRad) * sin(deltaRad) + cos(latRad) * cos(deltaRad) * cos(haRad)
        val elevationDeg = asin(sinElevation.coerceIn(-1.0, 1.0)) * DEG

        // Azimuth (degrees clockwise from North)
        val yAz = -sin(haRad) * cos(deltaRad)
        val xAz = cos(latRad) * sin(deltaRad) - sin(latRad) * cos(deltaRad) * cos(haRad)
        val azimuthDeg = normalizeDegrees(atan2(yAz, xAz) * DEG)

        // Atmospheric refraction correction
        // Saemundsson (1986) approximation; valid for true elevation >= -0.8333°.
        // For elevation >= 85°, refraction is negligible (< 0.001°) and omitted to prevent
        // numerical instability near zenith (tan(90°)).
        val apparentElevationDeg = if (elevationDeg in -0.8333..85.0) {
            val rArcmin = 1.02 / tan((elevationDeg + 10.3 / (elevationDeg + 5.11)) * RAD)
            elevationDeg + maxOf(0.0, rArcmin / 60.0)
        } else {
            elevationDeg
        }

        return SolarCoordinates(
            azimuthDegrees = azimuthDeg,
            elevationDegrees = elevationDeg,
            apparentElevationDegrees = apparentElevationDeg,
        )
    }

    /** Normalizes an angle in degrees into [0.0, 360.0). */
    fun normalizeDegrees(deg: Double): Double {
        val result = deg % 360.0
        return if (result < 0.0) result + 360.0 else result
    }
}

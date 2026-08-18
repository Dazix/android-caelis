package com.dazix.intervalometer.domain.nightsky

import java.time.Instant

/**
 * Astronomical Sidereal Time calculation (Greenwich Mean Sidereal Time and Local Sidereal Time).
 *
 * Implements the standard IAU/Meeus algorithm based on the J2000.0 epoch (2000 January 1.5 TT).
 * Pure Kotlin, no Android imports.
 */
object SiderealTime {

    private const val J2000_EPOCH_JD = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val MS_PER_DAY = 86400000.0

    /**
     * Converts a Unix [Instant] to Julian Date (JD).
     */
    fun toJulianDate(instant: Instant): Double {
        return UNIX_EPOCH_JD + (instant.toEpochMilli().toDouble() / MS_PER_DAY)
    }

    /**
     * Computes Greenwich Mean Sidereal Time (GMST) in degrees [0.0, 360.0) for a given UTC [instant].
     */
    fun computeGmstDegrees(instant: Instant): Double {
        val jd = toJulianDate(instant)
        val d = jd - J2000_EPOCH_JD
        val t = d / 36525.0

        // IAU formula for GMST in degrees
        var gmstDeg = 280.46061837 + 360.98564736629 * d + 0.000387933 * (t * t) - (t * t * t) / 38710000.0
        gmstDeg %= 360.0
        if (gmstDeg < 0.0) {
            gmstDeg += 360.0
        }
        return gmstDeg
    }

    /**
     * Computes Local Sidereal Time (LST) in degrees [0.0, 360.0) for a given UTC [instant]
     * and observer [longitudeDegrees] (East positive, West negative).
     */
    fun computeLstDegrees(instant: Instant, longitudeDegrees: Double): Double {
        val gmst = computeGmstDegrees(instant)
        var lst = (gmst + longitudeDegrees) % 360.0
        if (lst < 0.0) {
            lst += 360.0
        }
        return lst
    }

    /**
     * Computes Local Sidereal Time (LST) in hours [0.0, 24.0).
     */
    fun computeLstHours(instant: Instant, longitudeDegrees: Double): Double {
        return computeLstDegrees(instant, longitudeDegrees) / 15.0
    }

    /**
     * Formats LST into standard astronomical string format "HH:MM:SS" (e.g. "18h 36m 56s").
     */
    fun formatLstHours(lstHours: Double): String {
        var h = lstHours % 24.0
        if (h < 0.0) h += 24.0
        val hours = h.toInt()
        val mRem = (h - hours) * 60.0
        val minutes = mRem.toInt()
        val seconds = ((mRem - minutes) * 60.0).toInt()
        return String.format(java.util.Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
    }
}

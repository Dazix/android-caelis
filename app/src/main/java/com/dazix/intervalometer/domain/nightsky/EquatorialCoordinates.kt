package com.dazix.intervalometer.domain.nightsky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equatorial coordinates (Right Ascension, Declination) and horizontal coordinate transformations.
 * Pure Kotlin, no Android imports.
 */
object EquatorialCoordinates {

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Converts Equatorial coordinates (RA, Dec) to Horizontal coordinates (Azimuth, Elevation).
     *
     * @param rightAscensionDegrees Right Ascension in degrees [0.0, 360.0).
     * @param declinationDegrees Declination in degrees [-90.0, +90.0].
     * @param lstDegrees Local Sidereal Time in degrees [0.0, 360.0).
     * @param latitudeDegrees Observer latitude in degrees [-90.0, +90.0].
     * @return Pair of (AzimuthDegrees [0.0, 360.0) from North, ElevationDegrees [-90.0, +90.0]).
     */
    fun equatorialToHorizontal(
        rightAscensionDegrees: Double,
        declinationDegrees: Double,
        lstDegrees: Double,
        latitudeDegrees: Double,
    ): Pair<Double, Double> {
        val haDeg = (lstDegrees - rightAscensionDegrees)
        val haRad = haDeg * DEG_TO_RAD
        val decRad = declinationDegrees * DEG_TO_RAD
        val latRad = latitudeDegrees * DEG_TO_RAD

        val sinDec = sin(decRad)
        val cosDec = cos(decRad)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val cosHa = cos(haRad)
        val sinHa = sin(haRad)

        // Elevation / Altitude
        val sinAlt = sinLat * sinDec + cosLat * cosDec * cosHa
        val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
        val altDeg = altRad * RAD_TO_DEG

        // Azimuth (from North: 0° N, 90° E, 180° S, 270° W)
        val x = -cosDec * sinHa
        val y = cosLat * sinDec - sinLat * cosDec * cosHa
        var azDeg = atan2(x, y) * RAD_TO_DEG
        if (azDeg < 0.0) {
            azDeg += 360.0
        }
        if (azDeg >= 360.0) {
            azDeg -= 360.0
        }

        return Pair(azDeg, altDeg)
    }

    /**
     * Computes the 3D unit vector [vx, vy, vz] in ENU world coordinates (East, North, Up)
     * directly from Equatorial coordinates (RA, Dec), LST, and observer latitude.
     *
     * @param outVector FloatArray of size at least 3 to store [East, North, Up].
     * @param offset Offset into [outVector].
     */
    fun computeWorldVector(
        rightAscensionDegrees: Double,
        declinationDegrees: Double,
        lstDegrees: Double,
        latitudeDegrees: Double,
        outVector: FloatArray,
        offset: Int = 0,
    ) {
        val haRad = (lstDegrees - rightAscensionDegrees) * DEG_TO_RAD
        val decRad = declinationDegrees * DEG_TO_RAD
        val latRad = latitudeDegrees * DEG_TO_RAD

        val sinDec = sin(decRad)
        val cosDec = cos(decRad)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val cosHa = cos(haRad)
        val sinHa = sin(haRad)

        // vx (East) = -cos(Dec) * sin(H)
        outVector[offset] = (-cosDec * sinHa).toFloat()
        // vy (North) = cos(Lat) * sin(Dec) - sin(Lat) * cos(Dec) * cos(H)
        outVector[offset + 1] = (cosLat * sinDec - sinLat * cosDec * cosHa).toFloat()
        // vz (Up) = sin(Lat) * sin(Dec) + cos(Lat) * cos(Dec) * cos(H)
        outVector[offset + 2] = (sinLat * sinDec + cosLat * cosDec * cosHa).toFloat()
    }
}

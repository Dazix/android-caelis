package com.dazix.intervalometer.domain.solar

import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object LunarPositionCalculator {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    fun computePosition(instant: Instant, location: GeoLocation): LunarCoordinates {
        val epochMilli = instant.toEpochMilli()
        val jd = 2440587.5 + (epochMilli / 86400000.0)
        val t = (jd - 2451545.0) / 36525.0

        // Fundamental arguments in degrees
        var lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t
        var d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t
        var m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t
        var mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t
        var f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t

        lp = normalizeDeg(lp)
        d = normalizeDeg(d)
        m = normalizeDeg(m)
        mp = normalizeDeg(mp)
        f = normalizeDeg(f)

        val dRad = d * RAD
        val mRad = m * RAD
        val mpRad = mp * RAD
        val fRad = f * RAD

        // Periodic terms in Longitude (degrees)
        val deltaL = 6.288774 * sin(mpRad) +
            1.274027 * sin(2.0 * dRad - mpRad) +
            0.658314 * sin(2.0 * dRad) +
            0.213618 * sin(2.0 * mpRad) -
            0.185116 * sin(mRad) -
            0.114332 * sin(2.0 * fRad) +
            0.058793 * sin(2.0 * dRad - 2.0 * mpRad) +
            0.057066 * sin(2.0 * dRad - mRad - mpRad) +
            0.053322 * sin(2.0 * dRad + mpRad) +
            0.045758 * sin(2.0 * dRad - mRad) -
            0.040923 * sin(mRad - mpRad) -
            0.034720 * sin(dRad) -
            0.030383 * sin(mRad + mpRad) +
            0.015327 * sin(2.0 * dRad - 2.0 * fRad) -
            0.012528 * sin(2.0 * dRad + mpRad - mRad) +
            0.010980 * sin(mpRad + 2.0 * fRad) +
            0.010675 * sin(4.0 * dRad - mpRad) +
            0.010034 * sin(3.0 * mpRad) +
            0.008541 * sin(4.0 * dRad - 2.0 * mpRad) -
            0.007888 * sin(2.0 * dRad + mRad - mpRad) -
            0.006766 * sin(2.0 * dRad + mRad) -
            0.005163 * sin(dRad - mpRad) +
            0.004987 * sin(dRad + mRad) +
            0.004036 * sin(2.0 * dRad - mpRad + 2.0 * fRad)

        // Periodic terms in Latitude (degrees)
        val deltaB = 5.128122 * sin(fRad) +
            0.280602 * sin(mpRad + fRad) +
            0.277693 * sin(mpRad - fRad) +
            0.173237 * sin(2.0 * dRad - fRad) +
            0.055413 * sin(2.0 * dRad - mpRad + fRad) +
            0.046271 * sin(2.0 * dRad - mpRad - fRad) +
            0.032573 * sin(2.0 * dRad + fRad) +
            0.017198 * sin(2.0 * mpRad + fRad) +
            0.009266 * sin(2.0 * dRad + mpRad - fRad) +
            0.008822 * sin(2.0 * mpRad - fRad) +
            0.008216 * sin(2.0 * dRad - mRad - fRad) +
            0.004324 * sin(2.0 * dRad - 2.0 * mpRad - fRad) +
            0.004200 * sin(2.0 * dRad + mpRad + fRad) -
            0.003359 * sin(2.0 * dRad + mRad - fRad)

        // Distance in km
        val distanceKm = 385000.56 -
            20905.355 * cos(mpRad) -
            3699.111 * cos(2.0 * dRad - mpRad) -
            2955.968 * cos(2.0 * dRad) -
            569.925 * cos(2.0 * mpRad) +
            246.158 * cos(2.0 * dRad - 2.0 * mpRad) -
            152.138 * cos(2.0 * dRad - mRad - mpRad) -
            170.733 * cos(2.0 * dRad + mpRad) -
            204.586 * cos(2.0 * dRad - mRad) -
            129.620 * cos(mRad - mpRad) +
            108.743 * cos(dRad) +
            104.755 * cos(mRad + mpRad)

        val lambdaDeg = normalizeDeg(lp + deltaL)
        val betaDeg = deltaB

        val lambdaRad = lambdaDeg * RAD
        val betaRad = betaDeg * RAD

        // Obliquity of ecliptic
        val epsDeg = 23.439291 - 0.0130042 * t
        val epsRad = epsDeg * RAD

        // Ecliptic to Equatorial (Geocentric RA and Dec)
        val sinDelta = sin(betaRad) * cos(epsRad) + cos(betaRad) * sin(epsRad) * sin(lambdaRad)
        val deltaGeoRad = asin(sinDelta.coerceIn(-1.0, 1.0))
        val y = cos(betaRad) * sin(lambdaRad) * cos(epsRad) - sin(betaRad) * sin(epsRad)
        val x = cos(betaRad) * cos(lambdaRad)
        var alphaGeoRad = atan2(y, x)
        if (alphaGeoRad < 0.0) alphaGeoRad += 2.0 * Math.PI

        // Greenwhich Mean Sidereal Time (GMST) in degrees
        val dDays = jd - 2451545.0
        val gmstDeg = normalizeDeg(280.46061837 + 360.98564736629 * dDays + 0.000387933 * t * t)
        val lmstDeg = normalizeDeg(gmstDeg + location.longitude)
        val lmstRad = lmstDeg * RAD

        // Hour Angle H = LMST - RA
        val hGeoRad = lmstRad - alphaGeoRad

        // Topocentric Parallax Correction
        val latRad = location.latitude * RAD
        val u = atan(0.99664719 * tan(latRad))
        val rhoSinPhi = 0.99664719 * sin(u) + (location.altitudeMeters / 6378137.0) * sin(latRad)
        val rhoCosPhi = cos(u) + (location.altitudeMeters / 6378137.0) * cos(latRad)
        val sinPi = (6378.14 / distanceKm).coerceIn(0.0, 1.0)

        val cosDeltaGeo = cos(deltaGeoRad)
        val sinDeltaGeo = sin(deltaGeoRad)

        val deltaAlphaRad = atan2(
            -rhoCosPhi * sinPi * sin(hGeoRad),
            cosDeltaGeo - rhoCosPhi * sinPi * cos(hGeoRad)
        )
        val hTopoRad = hGeoRad - deltaAlphaRad

        val sinDeltaTopo = (sinDeltaGeo - rhoSinPhi * sinPi) * cos(deltaAlphaRad)
        val cosDeltaTopo = cosDeltaGeo - rhoCosPhi * sinPi * cos(hGeoRad)
        val deltaTopoRad = atan2(sinDeltaTopo, cosDeltaTopo)

        // Topocentric Horizontal Coordinates
        val sinH = sin(latRad) * sin(deltaTopoRad) + cos(latRad) * cos(deltaTopoRad) * cos(hTopoRad)
        val elevRad = asin(sinH.coerceIn(-1.0, 1.0))
        val elevDeg = elevRad * DEG

        val azY = -cos(deltaTopoRad) * sin(hTopoRad)
        val azX = sin(deltaTopoRad) * cos(latRad) - cos(deltaTopoRad) * sin(latRad) * cos(hTopoRad)
        var azDeg = atan2(azY, azX) * DEG
        azDeg = (azDeg % 360.0 + 360.0) % 360.0

        // Atmospheric refraction
        val apparentElevDeg = elevDeg + computeAtmosphericRefraction(elevDeg)

        // Moon phase and illumination fraction
        val phaseAngle = normalizeDeg(180.0 - d)
        val illumination = ((1.0 + cos(phaseAngle * RAD)) * 0.5).coerceIn(0.0, 1.0)

        val (phaseName, phaseGlyph) = determinePhase(d)

        return LunarCoordinates(
            azimuthDegrees = azDeg,
            elevationDegrees = elevDeg,
            apparentElevationDegrees = apparentElevDeg,
            distanceKm = distanceKm,
            illuminationFraction = illumination,
            elongationDegrees = d,
            phaseName = phaseName,
            phaseGlyph = phaseGlyph,
        )
    }

    private fun determinePhase(d: Double): Pair<String, String> {
        val norm = (d % 360.0 + 360.0) % 360.0
        return when {
            norm < 22.5 || norm >= 337.5 -> Pair("Nov", "🌑")
            norm < 67.5 -> Pair("Dorůstající srpek", "🌒")
            norm < 112.5 -> Pair("První čtvrť", "🌓")
            norm < 157.5 -> Pair("Dorůstající měsíc", "🌔")
            norm < 202.5 -> Pair("Úplněk", "🌕")
            norm < 247.5 -> Pair("Couvající měsíc", "🌖")
            norm < 292.5 -> Pair("Poslední čtvrť", "🌗")
            else -> Pair("Ubývající srpek", "🌘")
        }
    }

    private fun computeAtmosphericRefraction(geometricElevationDegrees: Double): Double {
        if (geometricElevationDegrees < -0.8333) return 0.0
        val h = geometricElevationDegrees.coerceAtLeast(-0.5)
        val rArcMinutes = 1.02 / tan((h + 10.3 / (h + 5.11)) * RAD)
        return (rArcMinutes / 60.0).coerceIn(0.0, 0.6)
    }

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
    }
}

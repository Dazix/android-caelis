package com.dazix.intervalometer.domain.nightsky

import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable state representing a dynamically calculated Solar System body (Planet or Moon).
 * Pure Kotlin, no Android imports.
 *
 * @param id Unique identifier (e.g. "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Moon").
 * @param nameEn English designation (e.g. "Jupiter").
 * @param nameCs Czech designation (e.g. "Jupiter").
 * @param raDegrees Right Ascension in degrees J2000.0 [0.0, 360.0).
 * @param decDegrees Declination in degrees J2000.0 [-90.0, +90.0].
 * @param magnitude Apparent visual magnitude (V band).
 * @param angularDiameterDeg Apparent angular diameter in degrees (used for deep-zoom texture scaling).
 * @param distanceFormatted Localized/human-readable distance (e.g. "4.21 AU" or "384,400 km").
 * @param illuminationFraction Phase illumination fraction [0.0, 1.0].
 * @param colorRgb AR Point light / core tint color (ARGB Int).
 * @param isMoon True if this body is the Earth's Moon.
 */
data class PlanetState(
    val id: String,
    val nameEn: String,
    val nameCs: String,
    val raDegrees: Double,
    val decDegrees: Double,
    val magnitude: Float,
    val angularDiameterDeg: Float,
    val distanceFormatted: String,
    val illuminationFraction: Float = 1.0f,
    val colorRgb: Int,
    val isMoon: Boolean = false,
) {
    fun localizedName(isCzech: Boolean): String = if (isCzech) nameCs else nameEn
}

/**
 * Pure Kotlin astronomical service that computes dynamic Keplerian orbital mechanics (J2000.0 epoch)
 * and truncated lunar theory for Solar System bodies.
 *
 * References:
 * - Standish, E. M. (1992) / Simon et al. (1994) Keplerian Elements for Approximate Planetary Positions.
 * - Meeus, J. Astronomical Algorithms (2nd Ed.), Chapters 31, 41, 47.
 */
object PlanetaryEphemerisCalculator {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI
    private const val J2000_JD = 2451545.0
    private const val ARCSEC_TO_DEG = 1.0 / 3600.0

    // Planet Colors for State A (Zoomed Out / Optical Star View)
    const val COLOR_MOON = 0xFFF5F5F0.toInt()
    const val COLOR_MERCURY = 0xFFE0D8C8.toInt()
    const val COLOR_VENUS = 0xFFFFFDF0.toInt()
    const val COLOR_MARS = 0xFFFF5722.toInt()
    const val COLOR_JUPITER = 0xFFFFE0B2.toInt()
    const val COLOR_SATURN = 0xFFFFD54F.toInt()
    const val COLOR_URANUS = 0xFF80DEEA.toInt()
    const val COLOR_NEPTUNE = 0xFF448AFF.toInt()

    private class PlanetElements(
        val id: String,
        val nameEn: String,
        val nameCs: String,
        val colorRgb: Int,
        val theta0Arcsec: Double, // Angular diameter at 1 AU in arcseconds
        val a0: Double, val aDot: Double,
        val e0: Double, val eDot: Double,
        val i0: Double, val iDot: Double,
        val l0: Double, val lDot: Double,
        val w0: Double, val wDot: Double, // Longitude of perihelion (varpi)
        val node0: Double, val nodeDot: Double, // Longitude of ascending node (Omega)
    )

    private val earthElements = PlanetElements(
        id = "Earth",
        nameEn = "Earth",
        nameCs = "Země",
        colorRgb = 0xFF2196F3.toInt(),
        theta0Arcsec = 0.0,
        a0 = 1.00000011, aDot = -0.00000005,
        e0 = 0.01671022, eDot = -0.00003804,
        i0 = 0.00005, iDot = -0.01294668,
        l0 = 100.46435, lDot = 35999.372449,
        w0 = 102.94719, wDot = 0.3232736,
        node0 = 0.0, nodeDot = 0.0,
    )

    private val majorPlanets = listOf(
        PlanetElements(
            id = "Mercury",
            nameEn = "Mercury",
            nameCs = "Merkur",
            colorRgb = COLOR_MERCURY,
            theta0Arcsec = 6.74,
            a0 = 0.38709893, aDot = 0.00000066,
            e0 = 0.20563069, eDot = 0.00002527,
            i0 = 7.00487, iDot = -0.0065194,
            l0 = 252.25084, lDot = 149472.67411,
            w0 = 77.45645, wDot = 0.1604768,
            node0 = 48.33167, nodeDot = -0.1253408,
        ),
        PlanetElements(
            id = "Venus",
            nameEn = "Venus",
            nameCs = "Venuše",
            colorRgb = COLOR_VENUS,
            theta0Arcsec = 16.92,
            a0 = 0.72333199, aDot = 0.00000092,
            e0 = 0.00677323, eDot = -0.00004938,
            i0 = 3.39471, iDot = -0.0007889,
            l0 = 181.97973, lDot = 58517.81538,
            w0 = 131.57294, wDot = 0.0026832,
            node0 = 76.68069, nodeDot = -0.2776941,
        ),
        PlanetElements(
            id = "Mars",
            nameEn = "Mars",
            nameCs = "Mars",
            colorRgb = COLOR_MARS,
            theta0Arcsec = 9.36,
            a0 = 1.52366231, aDot = -0.00007221,
            e0 = 0.09341233, eDot = 0.00011902,
            i0 = 1.85061, iDot = -0.0070503,
            l0 = 355.45332, lDot = 19140.30268,
            w0 = 336.04084, wDot = 0.4444108,
            node0 = 49.57854, nodeDot = -0.2949846,
        ),
        PlanetElements(
            id = "Jupiter",
            nameEn = "Jupiter",
            nameCs = "Jupiter",
            colorRgb = COLOR_JUPITER,
            theta0Arcsec = 196.9,
            a0 = 5.20336301, aDot = 0.00060737,
            e0 = 0.04839266, eDot = -0.00012880,
            i0 = 1.30530, iDot = -0.0041557,
            l0 = 34.40438, lDot = 3034.74612,
            w0 = 14.75385, wDot = 0.2125266,
            node0 = 100.55615, nodeDot = 0.2766544,
        ),
        PlanetElements(
            id = "Saturn",
            nameEn = "Saturn",
            nameCs = "Saturn",
            colorRgb = COLOR_SATURN,
            theta0Arcsec = 165.6, // Physical body diameter (rings scaled via bodyToImageRatio)
            a0 = 9.53707032, aDot = -0.00301530,
            e0 = 0.05415060, eDot = -0.00036762,
            i0 = 2.48446, iDot = 0.0019364,
            l0 = 49.94432, lDot = 1222.49362,
            w0 = 92.43194, wDot = -0.8176880,
            node0 = 113.71504, nodeDot = -0.2597400,
        ),
        PlanetElements(
            id = "Uranus",
            nameEn = "Uranus",
            nameCs = "Uran",
            colorRgb = COLOR_URANUS,
            theta0Arcsec = 70.0,
            a0 = 19.19126393, aDot = 0.00152025,
            e0 = 0.04716771, eDot = -0.00019150,
            i0 = 0.76986, iDot = -0.0004180,
            l0 = 313.23218, lDot = 428.48202,
            w0 = 170.96424, wDot = 0.5222914,
            node0 = 74.22988, nodeDot = 0.0424058,
        ),
        PlanetElements(
            id = "Neptune",
            nameEn = "Neptune",
            nameCs = "Neptun",
            colorRgb = COLOR_NEPTUNE,
            theta0Arcsec = 68.0,
            a0 = 30.06896348, aDot = -0.00125196,
            e0 = 0.00858587, eDot = 0.00002510,
            i0 = 1.76917, iDot = -0.0003530,
            l0 = 304.88003, lDot = 218.45945,
            w0 = 44.97135, wDot = -0.3224146,
            node0 = 131.72169, nodeDot = -0.0050866,
        ),
    )

    private class HeliocentricPos(
        val x: Double,
        val y: Double,
        val z: Double,
        val r: Double,
    )

    private fun computeHeliocentric(elements: PlanetElements, t: Double): HeliocentricPos {
        val a = elements.a0 + elements.aDot * t
        val e = elements.e0 + elements.eDot * t
        val iDeg = elements.i0 + elements.iDot * t
        val lDeg = elements.l0 + elements.lDot * t
        val wDeg = elements.w0 + elements.wDot * t
        val nodeDeg = elements.node0 + elements.nodeDot * t

        val omegaDeg = wDeg - nodeDeg
        var mDeg = (lDeg - wDeg) % 360.0
        if (mDeg < 0.0) mDeg += 360.0

        val mRad = mDeg * RAD

        // Solve Kepler's equation: M = E - e * sin(E)
        var eRad = mRad + e * sin(mRad) * (1.0 + e * cos(mRad))
        for (iter in 0 until 5) {
            val deltaE = (mRad - (eRad - e * sin(eRad))) / (1.0 - e * cos(eRad))
            eRad += deltaE
            if (abs(deltaE) < 1e-9) break
        }

        // Coordinates in orbital plane
        val xPrime = a * (cos(eRad) - e)
        val yPrime = a * sqrt((1.0 - e * e).coerceAtLeast(0.0)) * sin(eRad)

        // Rotation angles
        val omegaRad = omegaDeg * RAD
        val nodeRad = nodeDeg * RAD
        val iRad = iDeg * RAD

        val cosOmega = cos(omegaRad)
        val sinOmega = sin(omegaRad)
        val cosNode = cos(nodeRad)
        val sinNode = sin(nodeRad)
        val cosI = cos(iRad)
        val sinI = sin(iRad)

        // Heliocentric ecliptic coordinates
        val x = (cosOmega * cosNode - sinOmega * sinNode * cosI) * xPrime +
            (-sinOmega * cosNode - cosOmega * sinNode * cosI) * yPrime
        val y = (cosOmega * sinNode + sinOmega * cosNode * cosI) * xPrime +
            (-sinOmega * sinNode + cosOmega * cosNode * cosI) * yPrime
        val z = (sinOmega * sinI) * xPrime + (cosOmega * sinI) * yPrime
        val r = sqrt(x * x + y * y + z * z)

        return HeliocentricPos(x, y, z, r)
    }

    /**
     * Calculates planetary states for all 7 major planets plus the Moon for the given [instant].
     */
    fun computeAll(instant: Instant): List<PlanetState> {
        val jd = SiderealTime.toJulianDate(instant)
        val t = (jd - J2000_JD) / 36525.0

        // Obliquity of the Ecliptic J2000.0
        val epsDeg = 23.439291 - 0.0130042 * t
        val epsRad = epsDeg * RAD
        val cosEps = cos(epsRad)
        val sinEps = sin(epsRad)

        // Earth's heliocentric position
        val earth = computeHeliocentric(earthElements, t)

        val result = mutableListOf<PlanetState>()

        // 1. Calculate the Moon
        val moonState = computeMoon(instant, t, epsRad, cosEps, sinEps)
        result.add(moonState)

        // 2. Calculate major planets
        for (planet in majorPlanets) {
            val helio = computeHeliocentric(planet, t)

            // Geocentric ecliptic coordinates
            val xGeo = helio.x - earth.x
            val yGeo = helio.y - earth.y
            val zGeo = helio.z - earth.z
            val delta = sqrt(xGeo * xGeo + yGeo * yGeo + zGeo * zGeo) // Distance in AU

            // Convert to Equatorial coordinates
            val xEq = xGeo
            val yEq = yGeo * cosEps - zGeo * sinEps
            val zEq = yGeo * sinEps + zGeo * cosEps

            // Right Ascension & Declination
            var raRad = atan2(yEq, xEq)
            if (raRad < 0.0) raRad += 2.0 * Math.PI
            val raDeg = raRad * DEG
            val decDeg = asin((zEq / delta).coerceIn(-1.0, 1.0)) * DEG

            // Phase Angle beta
            val cosBeta = ((helio.r * helio.r + delta * delta - earth.r * earth.r) / (2.0 * helio.r * delta)).coerceIn(-1.0, 1.0)
            val betaDeg = acos(cosBeta) * DEG
            val illumination = ((1.0 + cosBeta) * 0.5).toFloat().coerceIn(0.0f, 1.0f)

            // Visual Magnitude V
            val mag = computePlanetMagnitude(planet.id, helio.r, delta, betaDeg)

            // Angular Diameter in degrees
            val angularDiameterDeg = ((planet.theta0Arcsec / delta) * ARCSEC_TO_DEG).toFloat()
            val distanceStr = String.format(java.util.Locale.US, "%.2f AU", delta)

            result.add(
                PlanetState(
                    id = planet.id,
                    nameEn = planet.nameEn,
                    nameCs = planet.nameCs,
                    raDegrees = raDeg,
                    decDegrees = decDeg,
                    magnitude = mag,
                    angularDiameterDeg = angularDiameterDeg,
                    distanceFormatted = distanceStr,
                    illuminationFraction = illumination,
                    colorRgb = planet.colorRgb,
                    isMoon = false,
                )
            )
        }

        return result
    }

    private fun computeMoon(instant: Instant, t: Double, epsRad: Double, cosEps: Double, sinEps: Double): PlanetState {
        // Truncated Meeus Lunar Ephemeris algorithm
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
            0.030383 * sin(mRad + mpRad)

        // Periodic terms in Latitude (degrees)
        val deltaB = 5.128122 * sin(fRad) +
            0.280602 * sin(mpRad + fRad) +
            0.277693 * sin(mpRad - fRad) +
            0.173237 * sin(2.0 * dRad - fRad) +
            0.055413 * sin(2.0 * dRad - mpRad + fRad) +
            0.046271 * sin(2.0 * dRad - mpRad - fRad) +
            0.032573 * sin(2.0 * dRad + fRad)

        // Distance in km
        val distanceKm = 385000.56 -
            20905.355 * cos(mpRad) -
            3699.111 * cos(2.0 * dRad - mpRad) -
            2955.968 * cos(2.0 * dRad) -
            569.925 * cos(2.0 * mpRad) +
            246.158 * cos(2.0 * dRad - 2.0 * mpRad)

        val lambdaRad = normalizeDeg(lp + deltaL) * RAD
        val betaRad = deltaB * RAD

        // Ecliptic to Equatorial (Geocentric RA & Dec)
        val sinDelta = sin(betaRad) * cosEps + cos(betaRad) * sinEps * sin(lambdaRad)
        val decRad = asin(sinDelta.coerceIn(-1.0, 1.0))
        val y = cos(betaRad) * sin(lambdaRad) * cosEps - sin(betaRad) * sinEps
        val x = cos(betaRad) * cos(lambdaRad)
        var raRad = atan2(y, x)
        if (raRad < 0.0) raRad += 2.0 * Math.PI

        // Lunar phase & illumination
        val phaseAngle = normalizeDeg(180.0 - d)
        val illumination = ((1.0 + cos(phaseAngle * RAD)) * 0.5).toFloat().coerceIn(0.0f, 1.0f)

        // Visual magnitude of Moon: V = -12.73 + 0.026*phase + 4e-9*phase^4
        val phi = phaseAngle
        val mag = (-12.73 + 0.026 * phi + 4e-9 * phi * phi * phi * phi).toFloat().coerceIn(-12.8f, -2.0f)

        // Moon angular diameter: theta_body = 2 * asin(1737.4 / distanceKm) in degrees.
        val physicalBodyDiameterDeg = 2.0 * asin((1737.4 / distanceKm).coerceIn(0.0, 1.0)) * DEG
        val angularDiameterDeg = physicalBodyDiameterDeg.toFloat()
        val distFormatted = String.format(java.util.Locale.US, "%,d km", distanceKm.toInt())

        return PlanetState(
            id = "Moon",
            nameEn = "Moon",
            nameCs = "Měsíc",
            raDegrees = raRad * DEG,
            decDegrees = decRad * DEG,
            magnitude = mag,
            angularDiameterDeg = angularDiameterDeg,
            distanceFormatted = distFormatted,
            illuminationFraction = illumination,
            colorRgb = COLOR_MOON,
            isMoon = true,
        )
    }

    private fun computePlanetMagnitude(id: String, r: Double, delta: Double, beta: Double): Float {
        val logTerm = 5.0 * log10(r * delta)
        return when (id) {
            "Mercury" -> (-0.42 + logTerm + 0.0380 * beta - 0.000273 * beta * beta + 0.000002 * beta * beta * beta).toFloat()
            "Venus" -> (-4.40 + logTerm + 0.0009 * beta + 0.000239 * beta * beta - 0.00000065 * beta * beta * beta).toFloat()
            "Mars" -> (-1.52 + logTerm + 0.016 * beta).toFloat()
            "Jupiter" -> (-9.40 + logTerm + 0.005 * beta).toFloat()
            "Saturn" -> (-8.88 + logTerm + 0.044 * beta).toFloat()
            "Uranus" -> (-7.19 + logTerm).toFloat()
            "Neptune" -> (-6.87 + logTerm).toFloat()
            else -> (-1.0f)
        }
    }

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
    }
}

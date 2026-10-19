package com.dazix.caelis.domain.solar

import kotlin.math.exp
import kotlin.math.tan

/**
 * Atmospheric refraction in degrees to add to a geometric elevation, shared by the Sun and the Moon.
 *
 * Saemundsson (1986) holds from the horizon (-0.8333° is where a body's centre stands as its upper limb
 * touches it) up to 85°, where it is negligible and tan(90°) would blow up. Below -0.8333° the formula
 * is out of range, and refraction there does not stop: the light still crosses the atmosphere, along a
 * longer path.
 *
 * The code used to return 0 below the horizon, which made the apparent elevation jump 0.6° between two
 * samples seconds apart. Fading it out (over 1°, then over 5°) removed the jump but not the fault: above
 * the horizon the day arc lies 0.1-0.6° above its geometric position, and bringing it back to that
 * position underground is an S-shaped bend in the line, measured at 1.5-2.5° of turn per two-minute step
 * against 0.3-0.5° elsewhere (`ArcBendTest`). Only the shape of the arc matters there, so the correction
 * is not taken away: below the horizon it keeps growing as the formula was growing and settles on a
 * constant, with value and slope continuous. The arc underground is then parallel to the geometric one,
 * about a degree above it, and has no bend.
 */
object AtmosphericRefraction {

    private const val RAD = Math.PI / 180.0
    private const val HORIZON_DEGREES = -0.8333
    private const val ZENITH_LIMIT_DEGREES = 85.0

    /** How far below the horizon the correction takes to settle on its constant (the e-folding distance). */
    const val SETTLE_DEGREES = 2.5

    private const val SLOPE_STEP_DEGREES = 1e-4

    private val horizonValue = saemundsson(HORIZON_DEGREES)

    // How fast the correction grows going down from the horizon: minus the formula's slope there.
    private val horizonGrowthPerDegree =
        -(saemundsson(HORIZON_DEGREES + SLOPE_STEP_DEGREES) - horizonValue) / SLOPE_STEP_DEGREES

    /** What the correction settles on far below the horizon. */
    val deepValue: Double = horizonValue + horizonGrowthPerDegree * SETTLE_DEGREES

    fun degrees(geometricElevationDegrees: Double): Double {
        val h = geometricElevationDegrees
        if (h > ZENITH_LIMIT_DEGREES) return 0.0
        if (h >= HORIZON_DEGREES) return saemundsson(h)
        // The horizon value plus a growth that starts at the formula's slope and dies away exponentially.
        return horizonValue + horizonGrowthPerDegree * SETTLE_DEGREES * (1.0 - exp((h - HORIZON_DEGREES) / SETTLE_DEGREES))
    }

    private fun saemundsson(elevationDegrees: Double): Double {
        val arcMinutes = 1.02 / tan((elevationDegrees + 10.3 / (elevationDegrees + 5.11)) * RAD)
        return maxOf(0.0, arcMinutes / 60.0)
    }
}

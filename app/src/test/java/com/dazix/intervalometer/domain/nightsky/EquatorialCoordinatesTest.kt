package com.dazix.intervalometer.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EquatorialCoordinatesTest {

    private val pragueLat = 50.0755

    @Test
    fun `verifies Polaris (near Dec +90°) has elevation equal to observer latitude and points North`() {
        // True North celestial pole at Dec = +90.0°
        val (az, alt) = EquatorialCoordinates.equatorialToHorizontal(
            rightAscensionDegrees = 0.0,
            declinationDegrees = 90.0,
            lstDegrees = 120.0, // arbitrary LST
            latitudeDegrees = pragueLat,
        )

        // Elevation at North Celestial Pole is exactly equal to latitude
        assertEquals(pragueLat, alt, 0.001)
        // Azimuth is due North (0° or 360°)
        assertTrue("Azimuth should be ~0° or ~360°, got $az", az < 0.01 || az > 359.99)
    }

    @Test
    fun `verifies Celestial Equator star on meridian (H = 0) transits due South with elevation 90 - latitude`() {
        // Star at Dec = 0.0°, transiting meridian when LST = RA (HA = 0)
        val ra = 180.0
        val (az, alt) = EquatorialCoordinates.equatorialToHorizontal(
            rightAscensionDegrees = ra,
            declinationDegrees = 0.0,
            lstDegrees = ra, // on meridian
            latitudeDegrees = pragueLat,
        )

        // Peak altitude for equatorial star at lat 50.0755° is 90° - 50.0755° = 39.9245°
        assertEquals(90.0 - pragueLat, alt, 0.001)
        // Azimuth at meridian transit south of zenith is 180° (South)
        assertEquals(180.0, az, 0.001)
    }

    @Test
    fun `verifies Zenith star (Dec = latitude, RA = LST) transits directly overhead (+90° elevation)`() {
        val ra = 90.0
        val (_, alt) = EquatorialCoordinates.equatorialToHorizontal(
            rightAscensionDegrees = ra,
            declinationDegrees = pragueLat,
            lstDegrees = ra,
            latitudeDegrees = pragueLat,
        )

        assertEquals(90.0, alt, 0.001)
    }

    @Test
    fun `verifies computeWorldVector produces unit vector matching horizontal coordinates`() {
        val outVec = FloatArray(3)
        EquatorialCoordinates.computeWorldVector(
            rightAscensionDegrees = 45.0,
            declinationDegrees = 30.0,
            lstDegrees = 180.0,
            latitudeDegrees = pragueLat,
            outVector = outVec,
            offset = 0,
        )

        val vx = outVec[0]
        val vy = outVec[1]
        val vz = outVec[2]

        val norm = sqrt(vx * vx + vy * vy + vz * vz)
        assertEquals(1.0f, norm, 0.0001f)

        // Compare against horizontal trig
        val (az, alt) = EquatorialCoordinates.equatorialToHorizontal(
            rightAscensionDegrees = 45.0,
            declinationDegrees = 30.0,
            lstDegrees = 180.0,
            latitudeDegrees = pragueLat,
        )
        val azRad = Math.toRadians(az)
        val altRad = Math.toRadians(alt)
        val expectedVx = (kotlin.math.sin(azRad) * kotlin.math.cos(altRad)).toFloat()
        val expectedVy = (kotlin.math.cos(azRad) * kotlin.math.cos(altRad)).toFloat()
        val expectedVz = kotlin.math.sin(altRad).toFloat()

        assertEquals(expectedVx, vx, 0.001f)
        assertEquals(expectedVy, vy, 0.001f)
        assertEquals(expectedVz, vz, 0.001f)
    }
}

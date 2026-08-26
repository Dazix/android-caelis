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

    @Test
    fun `verifies equatorialToHorizontal and horizontalToEquatorial form exact round-trip`() {
        val testObjects = listOf(
            Pair(101.28, -16.71), // Sirius
            Pair(279.23, 38.78),  // Vega
            Pair(88.79, 7.41),    // Betelgeuse
            Pair(184.8, 55.7),    // Ursa Major centroid
            Pair(0.0, 89.0),      // Near Polaris
            Pair(180.0, -60.0),   // Southern sky
        )
        val testLst = 135.5
        val testLat = 48.15

        for ((ra, dec) in testObjects) {
            val (az, el) = EquatorialCoordinates.equatorialToHorizontal(ra, dec, testLst, testLat)
            val (restoredRa, restoredDec) = EquatorialCoordinates.horizontalToEquatorial(az, el, testLst, testLat)

            assertEquals("Round-trip RA for ($ra, $dec)", ra, restoredRa, 1e-3)
            assertEquals("Round-trip Dec for ($ra, $dec)", dec, restoredDec, 1e-3)
        }
    }

    @Test
    fun `verifies getCurrentCameraRaDec extracts correct RA and Dec for cardinal horizons, Zenith, and Nadir`() {
        val testLst = 150.0
        val testLat = pragueLat

        // 1. Looking North at horizon (Az = 0°, El = 0°)
        val northMatrix = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 0.0, northMatrix)
        val (northRa, northDec) = EquatorialCoordinates.getCurrentCameraRaDec(northMatrix, testLst, testLat)
        val (expectedNorthRa, expectedNorthDec) = EquatorialCoordinates.horizontalToEquatorial(0.0, 0.0, testLst, testLat)
        assertEquals(expectedNorthRa, northRa, 1e-3)
        assertEquals(expectedNorthDec, northDec, 1e-3)

        // 2. Looking East at horizon (Az = 90°, El = 0°)
        val eastMatrix = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(90.0, 0.0, eastMatrix)
        val (eastRa, eastDec) = EquatorialCoordinates.getCurrentCameraRaDec(eastMatrix, testLst, testLat)
        val (expectedEastRa, expectedEastDec) = EquatorialCoordinates.horizontalToEquatorial(90.0, 0.0, testLst, testLat)
        assertEquals(expectedEastRa, eastRa, 1e-3)
        assertEquals(expectedEastDec, eastDec, 1e-3)

        // 3. Looking at Zenith (Az = 0°, El = 89.9°)
        val zenithMatrix = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(0.0, 89.9, zenithMatrix)
        val (zenithRa, zenithDec) = EquatorialCoordinates.getCurrentCameraRaDec(zenithMatrix, testLst, testLat)
        val (expectedZenithRa, expectedZenithDec) = EquatorialCoordinates.horizontalToEquatorial(0.0, 89.9, testLst, testLat)
        assertEquals(expectedZenithDec, zenithDec, 1e-3)

        // 4. Looking at Nadir (Az = 0°, El = -89.9°)
        val nadirMatrix = FloatArray(16)
        CelestialMatrixCalculator.computeManualRotationMatrix(0.0, -89.9, nadirMatrix)
        val (nadirRa, nadirDec) = EquatorialCoordinates.getCurrentCameraRaDec(nadirMatrix, testLst, testLat)
        val (expectedNadirRa, expectedNadirDec) = EquatorialCoordinates.horizontalToEquatorial(0.0, -89.9, testLst, testLat)
        assertEquals(expectedNadirDec, nadirDec, 1e-3)
    }

    @Test
    fun `verifies getCurrentCameraRaDec reconstructed from CelestialMatrixCalculator matches target star coordinates`() {
        val testObjects = listOf(
            Pair(101.28, -16.71), // Sirius
            Pair(279.23, 38.78),  // Vega
            Pair(88.79, 7.41),    // Betelgeuse
            Pair(184.8, 55.7),    // Ursa Major
        )
        val testLst = 85.0
        val testLat = 50.0755
        val matrixBuffer = FloatArray(16)

        for ((ra, dec) in testObjects) {
            // 1. Convert RA/Dec to Horizontal (Az, El)
            val (az, el) = EquatorialCoordinates.equatorialToHorizontal(ra, dec, testLst, testLat)

            // 2. Generate orthonormal rotation matrix targeted at (Az, El)
            CelestialMatrixCalculator.computeManualRotationMatrix(az, el, matrixBuffer)

            // 3. Extract optical RA/Dec from matrix
            val (extractedRa, extractedDec) = EquatorialCoordinates.getCurrentCameraRaDec(matrixBuffer, testLst, testLat)

            assertEquals("Extracted RA for ($ra, $dec)", ra, extractedRa, 0.01)
            assertEquals("Extracted Dec for ($ra, $dec)", dec, extractedDec, 0.01)
        }
    }
}



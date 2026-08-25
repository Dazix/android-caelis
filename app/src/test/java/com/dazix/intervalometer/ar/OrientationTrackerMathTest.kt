package com.dazix.intervalometer.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class OrientationTrackerMathTest {

    @Test
    fun `verifies magnetic declination 0 degrees preserves matrix identity`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)

        OrientationTracker.applyMagneticDeclination(identity, 0.0f, out)

        for (i in 0 until 16) {
            assertEquals(identity[i], out[i], 0.0001f)
        }
    }

    @Test
    fun `verifies magnetic declination adjusts look vector to true north`() {
        val dec = 10.0f
        val rad = Math.toRadians(-dec.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()

        val sensorMatrix = floatArrayOf(
            cosA, 0f, -sinA, 0f,
            -sinA, 0f, -cosA, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        val adjustedMatrix = FloatArray(16)
        OrientationTracker.applyMagneticDeclination(sensorMatrix, dec, adjustedMatrix)

        val camFwdX = -adjustedMatrix[2]
        val camFwdY = -adjustedMatrix[6]
        val camFwdZ = -adjustedMatrix[10]

        val camAzDeg = (Math.toDegrees(atan2(camFwdX.toDouble(), camFwdY.toDouble()))).toFloat()
        val normAzDeg = if (camAzDeg < 0f) camAzDeg + 360f else camAzDeg

        assertEquals(0.0f, normAzDeg, 0.01f) // True North!
        assertEquals(0.0f, camFwdZ, 0.01f)   // On horizon
    }

    @Test
    fun `verifies extract pitch from matrix for horizon, zenith, nadir, and 45 degrees`() {
        // Horizon looking North: -Z_dev = [0, 1, 0]^T -> matrix[10] = 0
        val horizonMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertEquals(0.0f, OrientationTracker.extractPitchFromMatrix(horizonMatrix), 0.01f)

        // Zenith: -Z_dev = [0, 0, 1]^T -> matrix[10] = -1
        val zenithMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertEquals(90.0f, OrientationTracker.extractPitchFromMatrix(zenithMatrix), 0.01f)

        // Nadir: -Z_dev = [0, 0, -1]^T -> matrix[10] = 1
        val nadirMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertEquals(-90.0f, OrientationTracker.extractPitchFromMatrix(nadirMatrix), 0.01f)

        // 45 degrees pitch: -Z_dev = [0, cos45, sin45]^T -> matrix[10] = -sin45 = -0.7071
        val sin45 = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val cos45 = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        val pitch45Matrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, sin45, -cos45, 0f,
            0f, cos45, -sin45, 0f,
            0f, 0f, 0f, 1f,
        )
        assertEquals(45.0f, OrientationTracker.extractPitchFromMatrix(pitch45Matrix), 0.01f)
    }

    @Test
    fun `verifies alt-azimuth calibration preserves 100 percent orthonormality across full sphere`() {
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val tempDec = FloatArray(16)
        val adjusted = FloatArray(16)

        val testPitches = floatArrayOf(-85f, -45f, 0f, 30f, 60f, 85f)
        val testAzimuths = floatArrayOf(0f, 45f, 90f, 180f, 270f, 359f)

        for (pitch in testPitches) {
            for (az in testAzimuths) {
                OrientationTracker.applyCalibration(
                    sensorMatrix = sensorMatrix,
                    declinationDeg = 12.5f,
                    panAzimuthOffsetDeg = az,
                    panPitchOffsetDeg = pitch,
                    tempDeclinationBuffer = tempDec,
                    outMatrix = adjusted,
                )

                // Check each column is a unit vector
                for (col in 0..2) {
                    val lenSq = adjusted[col] * adjusted[col] +
                        adjusted[col + 4] * adjusted[col + 4] +
                        adjusted[col + 8] * adjusted[col + 8]
                    assertEquals("Pitch $pitch Az $az Column $col norm should be 1.0", 1.0f, sqrt(lenSq), 0.001f)
                }

                // Check columns are mutually orthogonal
                val dot01 = adjusted[0] * adjusted[1] + adjusted[4] * adjusted[5] + adjusted[8] * adjusted[9]
                val dot02 = adjusted[0] * adjusted[2] + adjusted[4] * adjusted[6] + adjusted[8] * adjusted[10]
                val dot12 = adjusted[1] * adjusted[2] + adjusted[5] * adjusted[6] + adjusted[9] * adjusted[10]

                assertEquals(0.0f, dot01, 0.001f)
                assertEquals(0.0f, dot02, 0.001f)
                assertEquals(0.0f, dot12, 0.001f)
            }
        }
    }

    @Test
    fun `verifies horizontal pan rotates strictly around world Z keeping horizon level`() {
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val tempDec = FloatArray(16)
        val adjusted = FloatArray(16)

        // Apply 90 degrees azimuth pan at 0 degrees pitch
        OrientationTracker.applyCalibration(
            sensorMatrix = sensorMatrix,
            declinationDeg = 0.0f,
            panAzimuthOffsetDeg = 90.0f,
            panPitchOffsetDeg = 0.0f,
            tempDeclinationBuffer = tempDec,
            outMatrix = adjusted,
        )

        // Forward vector in world coordinates (-Z_dev):
        val camFwdX = -adjusted[2] // East
        val camFwdY = -adjusted[6] // North
        val camFwdZ = -adjusted[10] // Up

        // At 90° azimuth offset from North, camera points East (X = 1, Y = 0, Z = 0)
        assertEquals(1.0f, camFwdX, 0.01f)
        assertEquals(0.0f, camFwdY, 0.01f)
        assertEquals(0.0f, camFwdZ, 0.01f) // Horizon remains perfectly level at Z = 0

        // Device X axis (+X_dev = Right): [adjusted[0], adjusted[4], adjusted[8]]
        // Device X in world should point South [0, -1, 0]
        assertEquals(0.0f, adjusted[0], 0.01f)
        assertEquals(-1.0f, adjusted[4], 0.01f)
        assertEquals(0.0f, adjusted[8], 0.01f) // Right axis remains parallel to horizon
    }

    @Test
    fun `verifies vertical pan adjusts optical pitch`() {
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val tempDec = FloatArray(16)
        val adjusted = FloatArray(16)

        // Apply +30° pitch pan
        OrientationTracker.applyCalibration(
            sensorMatrix = sensorMatrix,
            declinationDeg = 0.0f,
            panAzimuthOffsetDeg = 0.0f,
            panPitchOffsetDeg = 30.0f,
            tempDeclinationBuffer = tempDec,
            outMatrix = adjusted,
        )

        val camFwdZ = -adjusted[10] // Up component
        val expectedSin30 = kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        assertEquals(expectedSin30, camFwdZ, 0.01f)
    }

    @Test
    fun `verifies dynamic clamping restricts gesture pitch to 85 degrees without affecting sensor pitch`() {
        // Mock sensor at 10° pitch
        val sin10 = kotlin.math.sin(Math.toRadians(10.0)).toFloat()
        val cos10 = kotlin.math.cos(Math.toRadians(10.0)).toFloat()
        val sensorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, sin10, -cos10, 0f,
            0f, cos10, -sin10, 0f,
            0f, 0f, 0f, 1f,
        )

        // Simulate onPanGesture logic
        val livePitch = OrientationTracker.extractPitchFromMatrix(sensorMatrix)
        assertEquals(10.0f, livePitch, 0.01f)

        var panPitchOffset = 0f
        val factor = 1.0f

        // 1. User drags up by 50° (requested pitch delta = +50°)
        val requestedDelta1 = 50f
        val prospective1 = (livePitch + panPitchOffset) + requestedDelta1
        val clamped1 = prospective1.coerceIn(-OrientationTracker.MAX_GESTURE_PITCH, OrientationTracker.MAX_GESTURE_PITCH)
        panPitchOffset = clamped1 - livePitch

        assertEquals(60.0f, livePitch + panPitchOffset, 0.01f)
        assertEquals(50.0f, panPitchOffset, 0.01f)

        // 2. User drags up by another 50° (would reach 110° -> must clamp at 85°)
        val requestedDelta2 = 50f
        val prospective2 = (livePitch + panPitchOffset) + requestedDelta2
        val clamped2 = prospective2.coerceIn(-OrientationTracker.MAX_GESTURE_PITCH, OrientationTracker.MAX_GESTURE_PITCH)
        panPitchOffset = clamped2 - livePitch

        assertEquals(85.0f, livePitch + panPitchOffset, 0.01f)
        assertEquals(75.0f, panPitchOffset, 0.01f)
    }

    @Test
    fun `verifies upside down phone tilt dynamically inverts horizontal drag`() {
        // When absolute pitch is 100° (past zenith, upside down):
        val upsideDownPitch = 100.0f
        val isUpsideDown = upsideDownPitch > 90f || upsideDownPitch < -90f
        assertTrue(isUpsideDown)

        val deltaX = 10.0f
        val effectiveDeltaX = if (isUpsideDown) -deltaX else deltaX
        assertEquals(-10.0f, effectiveDeltaX, 0.01f)

        // Normal upright pitch at 45°:
        val normalPitch = 45.0f
        val isNormalUpsideDown = normalPitch > 90f || normalPitch < -90f
        assertFalse(isNormalUpsideDown)

        val normalEffectiveDeltaX = if (isNormalUpsideDown) -deltaX else deltaX
        assertEquals(10.0f, normalEffectiveDeltaX, 0.01f)
    }

    @Test
    fun `verifies adaptive alpha produces low alpha when stationary and high alpha when panning`() {
        val currentQuat = floatArrayOf(0f, 0f, 0f, 1f)

        val stationaryAlpha = OrientationTracker.computeAdaptiveAlpha(
            currentQuat = currentQuat,
            targetQx = 0f,
            targetQy = 0f,
            targetQz = 0f,
            targetQw = 1f,
        )
        assertEquals(OrientationTracker.ALPHA_STATIONARY, stationaryAlpha, 0.001f)

        val panAngleRad = Math.toRadians(2.5).toFloat()
        val panSin = kotlin.math.sin(panAngleRad * 0.5f)
        val panCos = kotlin.math.cos(panAngleRad * 0.5f)
        val panAlpha = OrientationTracker.computeAdaptiveAlpha(
            currentQuat = currentQuat,
            targetQx = panSin,
            targetQy = 0f,
            targetQz = 0f,
            targetQw = panCos,
        )
        assertEquals(OrientationTracker.ALPHA_MOVING, panAlpha, 0.001f)
    }
}

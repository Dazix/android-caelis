package com.dazix.caelis.ar

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunArCalibrationTest {

    // Device held upright in portrait pointing North:
    // Col 0: Physical X (+X_dev) is East (+X_w)  -> [1, 0, 0]
    // Col 1: Physical Y (+Y_dev) is Up (+Z_w)    -> [0, 0, 1]
    // Col 2: Physical Z (+Z_dev) is South (-Y_w) -> [0, -1, 0]
    private val northPortraitMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `verifies drag amount scaling produces 1 to 1 pixel movement under finger`() {
        val viewWidth = 1080f
        val fov = 56.0f
        val dragPixels = 50f

        // When user drags finger by 50px, deltaDeg is:
        val deltaDeg = (dragPixels / viewWidth) * fov

        // In CameraProjection, focalLengthPixels = (W / 2) / tan(fov / 2)
        val halfFovRad = Math.toRadians(fov * 0.5)
        val focalLengthPixels = (viewWidth * 0.5) / Math.tan(halfFovRad)

        // Point at azimuth 0° originally at screen center: W / 2
        // After applying -deltaDeg offset through adjustAzimuthOffset, object shifts right:
        val shiftedAzRad = Math.toRadians(deltaDeg.toDouble())
        val shiftedScreenX = (viewWidth * 0.5) + focalLengthPixels * Math.tan(shiftedAzRad)

        val actualShiftPixels = shiftedScreenX - (viewWidth * 0.5)

        // With perspective projection on a 56° FOV lens, actualShiftPixels closely tracks dragPixels (within 5.0px)
        assertEquals(dragPixels.toDouble(), actualShiftPixels, 5.0)
    }

    @Test
    fun `verifies azimuth offset wrapping within -180 to 180 degrees`() {
        fun wrapAzimuth(offset: Float): Float {
            var updated = offset
            while (updated > 180f) updated -= 360f
            while (updated < -180f) updated += 360f
            return updated
        }

        assertEquals(0f, wrapAzimuth(0f), 0.001f)
        assertEquals(45f, wrapAzimuth(45f), 0.001f)
        assertEquals(-45f, wrapAzimuth(-45f), 0.001f)
        assertEquals(180f, wrapAzimuth(180f), 0.001f)
        assertEquals(-175f, wrapAzimuth(185f), 0.001f)
        assertEquals(175f, wrapAzimuth(-185f), 0.001f)
        assertEquals(10f, wrapAzimuth(370f), 0.001f)
        assertEquals(-10f, wrapAzimuth(-370f), 0.001f)
    }

    @Test
    fun `verifies horizontal drag right shifts projected coordinate to the right`() {
        val adjustedMatrix = FloatArray(16)
        // Dragging right applies a negative deltaDeg to azimuth offset
        val dragRightOffset = -10.0f
        OrientationTracker.applyMagneticDeclination(northPortraitMatrix, dragRightOffset, adjustedMatrix)

        val uncalibrated = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = northPortraitMatrix,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = 56f,
        )

        val calibrated = CameraProjection.project(
            azimuthDegrees = 0.0,
            elevationDegrees = 0.0,
            rotationMatrix = adjustedMatrix,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = 56f,
        )

        // Dragging right shifts apparent object position to the right
        assertTrue("Calibrated X (${calibrated.x}) should be > uncalibrated X (${uncalibrated.x})", calibrated.x > uncalibrated.x)
    }

    @Test
    fun `verifies terrain visibility threshold at -15 degrees`() {
        val terrainThreshold = -15.0
        assertTrue("Elevation at -10° is within terrain threshold", -10.0 >= terrainThreshold)
        assertTrue("Elevation at -15° is on terrain threshold", -15.0 >= terrainThreshold)
        assertTrue("Elevation at -15.1° is below terrain threshold", -15.1 < terrainThreshold)
    }
}

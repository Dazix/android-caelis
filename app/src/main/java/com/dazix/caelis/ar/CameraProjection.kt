package com.dazix.caelis.ar

import android.view.Surface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 2D projected screen coordinate for an AR celestial object on the Sky Dome.
 *
 * @param x Screen X coordinate in pixels.
 * @param y Screen Y coordinate in pixels.
 * @param isVisible True if point is in front of the camera (zCamera > 0) and within screen bounds.
 * @param isInFront True if point is in front of the camera plane (zCamera > 0).
 * @param zCamera Distance along the camera optical axis (positive is in front, negative is behind).
 * @param offscreenBearingDegrees Angle in degrees pointing towards the object if off-screen (0° = Top, 90° = Right, 180° = Bottom, 270° = Left).
 */
data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val isVisible: Boolean,
    val isInFront: Boolean,
    val zCamera: Float,
    val offscreenBearingDegrees: Float? = null,
)

object CameraProjection {

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    /**
     * Projects a celestial direction (azimuth, elevation) on the unit Sky Dome
     * into 2D screen pixel coordinates using [FovResult].
     */
    fun project(
        azimuthDegrees: Double,
        elevationDegrees: Double,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fov: FovResult,
    ): ProjectedPoint = project(
        azimuthDegrees = azimuthDegrees,
        elevationDegrees = elevationDegrees,
        rotationMatrix = rotationMatrix,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        displayRotation = displayRotation,
        fovHorizontalDegrees = fov.horizontalDegrees,
    )

    /**
     * Projects a celestial direction (azimuth, elevation) on the unit Sky Dome
     * into 2D screen pixel coordinates $(x, y)$ given the device orientation matrix,
     * display rotation, and camera horizontal field of view.
     *
     * @param azimuthDegrees Bearing clockwise from North [0.0, 360.0).
     * @param elevationDegrees Altitude above horizon in degrees [-90.0, +90.0].
     * @param rotationMatrix $4 \times 4$ or $3 \times 3$ rotation matrix from [android.hardware.SensorManager].
     * @param displayRotation Current screen orientation ([Surface.ROTATION_0], [Surface.ROTATION_90], etc.).
     * @param viewportWidth Screen width in pixels.
     * @param viewportHeight Screen height in pixels.
     * @param fovHorizontalDegrees Camera horizontal field of view in degrees (default 56° for smartphone back cameras).
     */
    fun project(
        azimuthDegrees: Double,
        elevationDegrees: Double,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fovHorizontalDegrees: Float = 56f,
    ): ProjectedPoint {
        val azRad = azimuthDegrees * RAD
        val elRad = elevationDegrees * RAD

        // 1. Direction vector on the 3D unit Sky Dome in ENU world coordinates (East, North, Up)
        val vxWorld = (sin(azRad) * cos(elRad)).toFloat()
        val vyWorld = (cos(azRad) * cos(elRad)).toFloat()
        val vzWorld = sin(elRad).toFloat()

        // 2. Transform into device coordinates: V_dev = R^T * V_world
        // Since SensorManager R transforms device -> world, the inverse transform world -> device is R^T
        val r = rotationMatrix
        val (xDev, yDev, zDev) = if (r.size >= 16) {
            val x = r[0] * vxWorld + r[4] * vyWorld + r[8] * vzWorld
            val y = r[1] * vxWorld + r[5] * vyWorld + r[9] * vzWorld
            val z = r[2] * vxWorld + r[6] * vyWorld + r[10] * vzWorld
            Triple(x, y, z)
        } else if (r.size >= 9) {
            val x = r[0] * vxWorld + r[3] * vyWorld + r[6] * vzWorld
            val y = r[1] * vxWorld + r[4] * vyWorld + r[7] * vzWorld
            val z = r[2] * vxWorld + r[5] * vyWorld + r[8] * vzWorld
            Triple(x, y, z)
        } else {
            Triple(vxWorld, vyWorld, vzWorld)
        }

        // 3. Map physical device axes to Camera Screen coordinates based on display rotation
        // In natural portrait (ROTATION_0): Screen Right is +X_dev, Screen Up is +Y_dev, Camera Forward is -Z_dev
        val (xs, ys) = when (displayRotation) {
            Surface.ROTATION_0 -> Pair(xDev, yDev)
            Surface.ROTATION_90 -> Pair(yDev, -xDev)
            Surface.ROTATION_180 -> Pair(-xDev, -yDev)
            Surface.ROTATION_270 -> Pair(-yDev, xDev)
            else -> Pair(xDev, yDev)
        }
        val zs = -zDev

        val isInFront = zs > 0.001f

        // 4. Perspective projection on the Sky Dome
        val halfFovHRad = (fovHorizontalDegrees * 0.5f * RAD.toFloat())
        val focalLengthPixels = (viewportWidth * 0.5f) / tan(halfFovHRad)

        val screenX: Float
        val screenY: Float

        if (isInFront) {
            screenX = (viewportWidth * 0.5f) + focalLengthPixels * (xs / zs)
            screenY = (viewportHeight * 0.5f) - focalLengthPixels * (ys / zs)
        } else {
            // Point is behind the observer
            screenX = (viewportWidth * 0.5f) - focalLengthPixels * (xs / -zs)
            screenY = (viewportHeight * 0.5f) + focalLengthPixels * (ys / -zs)
        }

        val margin = 80f
        val isInsideScreen = screenX in -margin..(viewportWidth + margin) &&
            screenY in -margin..(viewportHeight + margin)
        val isVisible = isInFront && isInsideScreen

        // Calculate directional bearing for off-screen radar pointer (0° is Up, 90° Right, 180° Down, 270° Left)
        val offscreenBearing = if (!isVisible) {
            val angleRad = atan2(xs.toDouble(), ys.toDouble())
            var angleDeg = (angleRad * DEG).toFloat()
            if (angleDeg < 0f) angleDeg += 360f
            angleDeg
        } else {
            null
        }

        return ProjectedPoint(
            x = screenX,
            y = screenY,
            isVisible = isVisible,
            isInFront = isInFront,
            zCamera = zs,
            offscreenBearingDegrees = offscreenBearing,
        )
    }

    /**
     * In-place zero-allocation perspective projection using [FovResult].
     */
    fun projectFast(
        azimuthDegrees: Double,
        elevationDegrees: Double,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fov: FovResult,
        outBuffer: FloatArray,
        offset: Int = 0,
    ): Boolean = projectFast(
        azimuthDegrees = azimuthDegrees,
        elevationDegrees = elevationDegrees,
        rotationMatrix = rotationMatrix,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        displayRotation = displayRotation,
        fovHorizontalDegrees = fov.horizontalDegrees,
        outBuffer = outBuffer,
        offset = offset,
    )

    /**
     * In-place zero-allocation perspective projection writing $(x, y, z_s)$ directly into [outBuffer]
     * to ensure sustained 60 FPS performance without GC pressure.
     *
     * @param outBuffer Output FloatArray where:
     *   `outBuffer[offset + 0]` = screenX in pixels
     *   `outBuffer[offset + 1]` = screenY in pixels
     *   `outBuffer[offset + 2]` = zCamera (> 0 in front of camera, < 0 behind)
     * @return true if point is in front of the camera plane (zCamera > 0.001f).
     */
    fun projectFast(
        azimuthDegrees: Double,
        elevationDegrees: Double,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fovHorizontalDegrees: Float = 56f,
        outBuffer: FloatArray,
        offset: Int = 0,
    ): Boolean {
        val azRad = azimuthDegrees * RAD
        val elRad = elevationDegrees * RAD

        val cosEl = cos(elRad)
        val vxWorld = (sin(azRad) * cosEl).toFloat()
        val vyWorld = (cos(azRad) * cosEl).toFloat()
        val vzWorld = sin(elRad).toFloat()

        val r = rotationMatrix
        val xDev: Float
        val yDev: Float
        val zDev: Float

        if (r.size >= 16) {
            xDev = r[0] * vxWorld + r[4] * vyWorld + r[8] * vzWorld
            yDev = r[1] * vxWorld + r[5] * vyWorld + r[9] * vzWorld
            zDev = r[2] * vxWorld + r[6] * vyWorld + r[10] * vzWorld
        } else if (r.size >= 9) {
            xDev = r[0] * vxWorld + r[3] * vyWorld + r[6] * vzWorld
            yDev = r[1] * vxWorld + r[4] * vyWorld + r[7] * vzWorld
            zDev = r[2] * vxWorld + r[5] * vyWorld + r[8] * vzWorld
        } else {
            xDev = vxWorld
            yDev = vyWorld
            zDev = vzWorld
        }

        val xs: Float
        val ys: Float
        when (displayRotation) {
            Surface.ROTATION_0 -> { xs = xDev; ys = yDev }
            Surface.ROTATION_90 -> { xs = yDev; ys = -xDev }
            Surface.ROTATION_180 -> { xs = -xDev; ys = -yDev }
            Surface.ROTATION_270 -> { xs = -yDev; ys = xDev }
            else -> { xs = xDev; ys = yDev }
        }
        val zs = -zDev
        val isInFront = zs > 0.001f

        val halfFovHRad = (fovHorizontalDegrees * 0.5f * RAD.toFloat())
        val focalLengthPixels = (viewportWidth * 0.5f) / tan(halfFovHRad)

        val screenX: Float
        val screenY: Float
        if (isInFront) {
            screenX = (viewportWidth * 0.5f) + focalLengthPixels * (xs / zs)
            screenY = (viewportHeight * 0.5f) - focalLengthPixels * (ys / zs)
        } else {
            screenX = (viewportWidth * 0.5f) - focalLengthPixels * (xs / -zs)
            screenY = (viewportHeight * 0.5f) + focalLengthPixels * (ys / -zs)
        }

        outBuffer[offset] = screenX
        outBuffer[offset + 1] = screenY
        outBuffer[offset + 2] = zs

        return isInFront
    }

    /**
     * Inverse projection using [FovResult].
     */
    fun unprojectFast(
        screenX: Float,
        screenY: Float,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fov: FovResult,
        outAzEl: DoubleArray,
        offset: Int = 0,
    ) = unprojectFast(
        screenX = screenX,
        screenY = screenY,
        rotationMatrix = rotationMatrix,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        displayRotation = displayRotation,
        fovHorizontalDegrees = fov.horizontalDegrees,
        outAzEl = outAzEl,
        offset = offset,
    )

    /**
     * The exact inverse of [projectFast]: which direction in the world a screen pixel looks at.
     *
     * Needed to read the sky out of the camera image. The automatic horizon alignment finds the
     * sky/terrain boundary in pixels and has to ask what azimuth and elevation each of those pixels
     * is pointing at before it can compare them with the terrain profile.
     *
     * @param outAzEl receives `[azimuthDegrees, elevationDegrees]`; azimuth is in [0, 360).
     */
    fun unprojectFast(
        screenX: Float,
        screenY: Float,
        rotationMatrix: FloatArray,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = Surface.ROTATION_0,
        fovHorizontalDegrees: Float = 56f,
        outAzEl: DoubleArray,
        offset: Int = 0,
    ) {
        val halfFovHRad = fovHorizontalDegrees * 0.5f * RAD.toFloat()
        val focalLengthPixels = (viewportWidth * 0.5f) / tan(halfFovHRad)

        // Undo the perspective divide by fixing the camera-axis component at 1: only the direction
        // matters, and the ray through a pixel is the same at any distance.
        val xs = (screenX - viewportWidth * 0.5f) / focalLengthPixels
        val ys = -(screenY - viewportHeight * 0.5f) / focalLengthPixels
        val zs = 1f

        val xDev: Float
        val yDev: Float
        when (displayRotation) {
            Surface.ROTATION_0 -> { xDev = xs; yDev = ys }
            Surface.ROTATION_90 -> { xDev = -ys; yDev = xs }
            Surface.ROTATION_180 -> { xDev = -xs; yDev = -ys }
            Surface.ROTATION_270 -> { xDev = ys; yDev = -xs }
            else -> { xDev = xs; yDev = ys }
        }
        val zDev = -zs

        // projectFast computes V_dev = R^T · V_world, so the way back is V_world = R · V_dev.
        val r = rotationMatrix
        val vx: Float
        val vy: Float
        val vz: Float
        if (r.size >= 16) {
            vx = r[0] * xDev + r[1] * yDev + r[2] * zDev
            vy = r[4] * xDev + r[5] * yDev + r[6] * zDev
            vz = r[8] * xDev + r[9] * yDev + r[10] * zDev
        } else if (r.size >= 9) {
            vx = r[0] * xDev + r[1] * yDev + r[2] * zDev
            vy = r[3] * xDev + r[4] * yDev + r[5] * zDev
            vz = r[6] * xDev + r[7] * yDev + r[8] * zDev
        } else {
            vx = xDev
            vy = yDev
            vz = zDev
        }

        val length = kotlin.math.sqrt((vx * vx + vy * vy + vz * vz).toDouble())
        var azimuth = atan2(vx.toDouble(), vy.toDouble()) * DEG
        if (azimuth < 0.0) azimuth += 360.0
        val elevation = if (length > 1e-9) {
            kotlin.math.asin((vz / length).coerceIn(-1.0, 1.0)) * DEG
        } else {
            0.0
        }

        outAzEl[offset] = azimuth
        outAzEl[offset + 1] = elevation
    }
}

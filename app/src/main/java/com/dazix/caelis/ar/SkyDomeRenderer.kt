package com.dazix.caelis.ar

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Normal Color Palette
val NormalGoldSolar = Color(0xFFFFB300)
val NormalSunrise = Color(0xFFFF7043)
val NormalSunset = Color(0xFFE64A19)
val NormalHorizon = Color(0xDD4DD0E1)
val NormalGrid = Color(0x664DD0E1)
val NormalNightArc = Color(0x44FFB300)

// Red Vision (Astrophotography Night Mode) Palette
val RedPrimary = Color(0xFFFF1744)
val RedSecondary = Color(0xFFD32F2F)
val RedGrid = Color(0x33FF1744)
val RedNightArc = Color(0x33FF1744)

data class NightVisionPalette(
    val primary: Color,
    val secondary: Color,
    val grid: Color,
    val nightArc: Color,
    val shaderTintR: Float,
    val shaderTintG: Float,
    val shaderTintB: Float,
)

val RedNightVisionPalette = NightVisionPalette(
    primary = Color(0xFFFF1744),
    secondary = Color(0xFFD32F2F),
    grid = Color(0x33FF1744),
    nightArc = Color(0x33FF1744),
    shaderTintR = 1.0f,
    shaderTintG = 0.0f,
    shaderTintB = 0.0f,
)

val AmberNightVisionPalette = NightVisionPalette(
    primary = Color(0xFFFFB300),
    secondary = Color(0xFFFF8F00),
    grid = Color(0x33FFB300),
    nightArc = Color(0x33FFB300),
    shaderTintR = 1.0f,
    shaderTintG = 0.7f,
    shaderTintB = 0.0f,
)

val GreenNightVisionPalette = NightVisionPalette(
    primary = Color(0xFF00E676),
    secondary = Color(0xFF00C853),
    grid = Color(0x3300E676),
    nightArc = Color(0x3300E676),
    shaderTintR = 0.0f,
    shaderTintG = 1.0f,
    shaderTintB = 0.0f,
)

fun paletteForTheme(theme: com.dazix.caelis.domain.nightsky.NightVisionTheme): NightVisionPalette = when (theme) {
    com.dazix.caelis.domain.nightsky.NightVisionTheme.RED -> RedNightVisionPalette
    com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER -> AmberNightVisionPalette
    com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN -> GreenNightVisionPalette
}

/**
 * High-performance zero-allocation renderer for the 3D Sky Dome coordinate frame,
 * horizon ring, altitude parallels, azimuth meridians, and cardinal points.
 */
class SkyDomeRenderer {
    val tempRect = RectF()
    val tempPoint = FloatArray(3)
    val pathBuffer = FloatArray(1440 * 3)
    val horizonBuffer = FloatArray(180 * 3)
    val altitudeGridBuffer = FloatArray(72 * 3)
    val meridianBuffer = FloatArray(36 * 3)

    var zenithLabel: String = "ZENITH (+90°)"
    var nadirLabel: String = "NADIR (-90°)"

    val cardinalAzimuths = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
    val altitudeLevels = doubleArrayOf(-30.0, -15.0, 15.0, 30.0, 45.0, 60.0, 75.0)
    val meridianAzimuths = doubleArrayOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0, 210.0, 240.0, 270.0, 300.0, 330.0)
    val labelAzimuths = doubleArrayOf(0.0, 90.0, 180.0, 270.0, 45.0, 135.0, 225.0, 315.0)

    var cardinalLabels = arrayOf(
        "N (0°)", "NE (45°)", "E (90°)", "SE (135°)",
        "S (180°)", "SW (225°)", "W (270°)", "NW (315°)",
    )

    fun updateLabels(zenith: String, nadir: String, cardinals: Array<String>) {
        zenithLabel = zenith
        nadirLabel = nadir
        cardinalLabels = cardinals
    }

    // Strict minimalist paints: zero drop shadows, sharp thin vector lines
    val horizonTextPaint = Paint().apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        textSize = 10.5f
    }

    val gridTextPaint = Paint().apply {
        isFakeBoldText = false
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        textSize = 9.0f
    }

    val bubbleTextPaint = Paint().apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val bubbleBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val bubbleBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    val markerTextPaint = Paint().apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val markerBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val markerBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    val pointerTextPaint = Paint().apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val pointerBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val pointerBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    var activePalette: NightVisionPalette = AmberNightVisionPalette
        private set

    fun applyTheme(state: com.dazix.caelis.domain.nightsky.NightVisionState) {
        applyTheme(theme = state.theme, isNightVision = state.isActive)
    }

    fun applyTheme(
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        activePalette = paletteForTheme(theme)
        if (isNightVision) {
            val p = activePalette
            val pr = (p.primary.red * 255).toInt()
            val pg = (p.primary.green * 255).toInt()
            val pb = (p.primary.blue * 255).toInt()
            val sr = (p.secondary.red * 255).toInt()
            val sg = (p.secondary.green * 255).toInt()
            val sb = (p.secondary.blue * 255).toInt()

            val primaryInt = AndroidColor.rgb(pr, pg, pb)

            horizonTextPaint.color = AndroidColor.argb(143, pr, pg, pb)
            gridTextPaint.color = AndroidColor.argb(143, sr, sg, sb)
            bubbleTextPaint.color = primaryInt
            bubbleBgPaint.color = AndroidColor.argb(240, 0, 0, 0)
            bubbleBorderPaint.color = primaryInt
            markerTextPaint.color = primaryInt
            markerBgPaint.color = AndroidColor.argb(240, 0, 0, 0)
            markerBorderPaint.color = primaryInt
            pointerTextPaint.color = primaryInt
            pointerBgPaint.color = AndroidColor.argb(240, 0, 0, 0)
            pointerBorderPaint.color = primaryInt
        } else {
            horizonTextPaint.color = AndroidColor.argb(143, 255, 255, 255)
            gridTextPaint.color = AndroidColor.argb(143, 77, 208, 225)
            bubbleTextPaint.color = AndroidColor.WHITE
            bubbleBgPaint.color = AndroidColor.argb(220, 20, 20, 20)
            bubbleBorderPaint.color = AndroidColor.argb(255, 255, 179, 0)
            markerTextPaint.color = AndroidColor.WHITE
            markerBgPaint.color = AndroidColor.argb(230, 20, 20, 20)
            markerBorderPaint.color = AndroidColor.argb(255, 255, 179, 0)
            pointerTextPaint.color = AndroidColor.argb(255, 255, 179, 0)
            pointerBgPaint.color = AndroidColor.argb(220, 20, 20, 20)
            pointerBorderPaint.color = AndroidColor.argb(255, 255, 179, 0)
        }
    }

    fun applyTheme(redVisionMode: Boolean) {
        applyTheme(theme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER, isNightVision = redVisionMode)
    }

    /**
     * Draws the full 3D celestial wireframe grid (Horizon ring 0°, Altitude rings, Meridians, Cardinals, Zenith, Nadir).
     */
    fun drawSkyDomeGrid(
        drawScope: DrawScope,
        matrix: FloatArray,
        displayRotation: Int,
        fovH: Float,
        redVisionMode: Boolean,
        w: Float,
        h: Float,
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
    ) {
        val palette = paletteForTheme(theme)
        val horizonColor = if (redVisionMode) palette.secondary else NormalHorizon
        val majorGridColor = if (redVisionMode) palette.secondary.copy(alpha = 0.6f) else NormalHorizon.copy(alpha = 0.55f)
        val minorGridColor = if (redVisionMode) palette.grid else NormalGrid

        // 1. Azimuth Meridians (Vertical lines from Nadir -85° to Zenith +85°)
        val elevationSteps = 35 // -85° to +85° in 5° steps
        for (az in meridianAzimuths) {
            val isMajorMeridian = az == 0.0 || az == 90.0 || az == 180.0 || az == 270.0
            val lineColor = if (isMajorMeridian) majorGridColor else minorGridColor
            val strokeWidth = if (isMajorMeridian) 1.2f else 0.8f

            for (i in 0 until elevationSteps) {
                val el = -85.0 + i * 5.0
                CameraProjection.projectFast(
                    azimuthDegrees = az,
                    elevationDegrees = el,
                    rotationMatrix = matrix,
                    viewportWidth = w,
                    viewportHeight = h,
                    displayRotation = displayRotation,
                    fovHorizontalDegrees = fovH,
                    outBuffer = meridianBuffer,
                    offset = i * 3,
                )
            }

            for (i in 0 until elevationSteps - 1) {
                val off1 = i * 3
                val off2 = (i + 1) * 3
                val z1 = meridianBuffer[off1 + 2]
                val z2 = meridianBuffer[off2 + 2]

                if (z1 > 0.001f && z2 > 0.001f) {
                    drawScope.drawLine(
                        color = lineColor,
                        start = Offset(meridianBuffer[off1], meridianBuffer[off1 + 1]),
                        end = Offset(meridianBuffer[off2], meridianBuffer[off2 + 1]),
                        strokeWidth = strokeWidth,
                    )
                }
            }
        }

        // 2. Horizon Circle (0° elevation) - 180 vertices pre-projected in batch
        val horizonSteps = 180
        for (i in 0 until horizonSteps) {
            val az = (i * 2).toDouble()
            CameraProjection.projectFast(
                azimuthDegrees = az,
                elevationDegrees = 0.0,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovH,
                outBuffer = horizonBuffer,
                offset = i * 3,
            )
        }

        for (i in 0 until horizonSteps) {
            val nextIdx = (i + 1) % horizonSteps
            val off1 = i * 3
            val off2 = nextIdx * 3
            val z1 = horizonBuffer[off1 + 2]
            val z2 = horizonBuffer[off2 + 2]

            if (z1 > 0.001f && z2 > 0.001f) {
                drawScope.drawLine(
                    color = horizonColor,
                    start = Offset(horizonBuffer[off1], horizonBuffer[off1 + 1]),
                    end = Offset(horizonBuffer[off2], horizonBuffer[off2 + 1]),
                    strokeWidth = 1.8f,
                )
            }
        }

        // 3. Altitude Grid Circles (-30°, -15°, +15°, +30°, +45°, +60°, +75° elevation rings)
        for (elev in altitudeLevels) {
            val isMajorParallel = elev == 30.0 || elev == 60.0
            val lineColor = if (isMajorParallel) majorGridColor else minorGridColor
            val strokeWidth = if (isMajorParallel) 1.0f else 0.7f
            val gridSteps = 72 // 360 / 5°

            for (i in 0 until gridSteps) {
                val az = (i * 5).toDouble()
                CameraProjection.projectFast(
                    azimuthDegrees = az,
                    elevationDegrees = elev,
                    rotationMatrix = matrix,
                    viewportWidth = w,
                    viewportHeight = h,
                    displayRotation = displayRotation,
                    fovHorizontalDegrees = fovH,
                    outBuffer = altitudeGridBuffer,
                    offset = i * 3,
                )
            }

            for (i in 0 until gridSteps) {
                val nextIdx = (i + 1) % gridSteps
                val off1 = i * 3
                val off2 = nextIdx * 3
                val z1 = altitudeGridBuffer[off1 + 2]
                val z2 = altitudeGridBuffer[off2 + 2]

                if (z1 > 0.001f && z2 > 0.001f) {
                    drawScope.drawLine(
                        color = lineColor,
                        start = Offset(altitudeGridBuffer[off1], altitudeGridBuffer[off1 + 1]),
                        end = Offset(altitudeGridBuffer[off2], altitudeGridBuffer[off2 + 1]),
                        strokeWidth = strokeWidth,
                    )
                }
            }

            // Draw degree labels along the parallel at cardinal & intercardinal azimuths
            val labelStr = "${if (elev > 0) "+" else ""}${elev.toInt()}°"
            for (az in labelAzimuths) {
                val inFront = CameraProjection.projectFast(
                    azimuthDegrees = az,
                    elevationDegrees = elev,
                    rotationMatrix = matrix,
                    viewportWidth = w,
                    viewportHeight = h,
                    displayRotation = displayRotation,
                    fovHorizontalDegrees = fovH,
                    outBuffer = tempPoint,
                )
                val px = tempPoint[0]
                val py = tempPoint[1]
                if (inFront && px in 20f..(w - 20f) && py in 40f..(h - 40f)) {
                    drawScope.drawContext.canvas.nativeCanvas.drawText(
                        labelStr,
                        px,
                        py - 4f,
                        gridTextPaint,
                    )
                }
            }
        }

        // 4. Cardinal & Intercardinal Points on Horizon
        for (i in cardinalAzimuths.indices) {
            val az = cardinalAzimuths[i]
            val label = cardinalLabels[i]
            val inFront = CameraProjection.projectFast(
                azimuthDegrees = az,
                elevationDegrees = 0.0,
                rotationMatrix = matrix,
                viewportWidth = w,
                viewportHeight = h,
                displayRotation = displayRotation,
                fovHorizontalDegrees = fovH,
                outBuffer = tempPoint,
            )
            val px = tempPoint[0]
            val py = tempPoint[1]
            val isInside = px in -50f..(w + 50f) && py in -50f..(h + 50f)

            if (inFront && isInside) {
                drawScope.drawCircle(
                    color = horizonColor,
                    radius = 3.5f,
                    center = Offset(px, py),
                )
                drawScope.drawContext.canvas.nativeCanvas.drawText(
                    label,
                    px,
                    py - 10f,
                    horizonTextPaint,
                )
            }
        }

        // 5. Zenith Marker (+90° directly overhead)
        val zenithInFront = CameraProjection.projectFast(
            azimuthDegrees = 0.0,
            elevationDegrees = 90.0,
            rotationMatrix = matrix,
            viewportWidth = w,
            viewportHeight = h,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovH,
            outBuffer = tempPoint,
        )
        val zx = tempPoint[0]
        val zy = tempPoint[1]
        if (zenithInFront && zx in -50f..(w + 50f) && zy in -50f..(h + 50f)) {
            val r = 12f
            drawScope.drawLine(
                color = horizonColor,
                start = Offset(zx - r, zy),
                end = Offset(zx + r, zy),
                strokeWidth = 1.5f,
            )
            drawScope.drawLine(
                color = horizonColor,
                start = Offset(zx, zy - r),
                end = Offset(zx, zy + r),
                strokeWidth = 1.5f,
            )
            drawScope.drawCircle(
                color = horizonColor,
                radius = 6f,
                center = Offset(zx, zy),
                style = Stroke(width = 1.4f),
            )
            drawScope.drawContext.canvas.nativeCanvas.drawText(
                zenithLabel,
                zx,
                zy - 15f,
                horizonTextPaint,
            )
        }

        // 6. Nadir Marker (-90° directly below feet)
        val nadirInFront = CameraProjection.projectFast(
            azimuthDegrees = 0.0,
            elevationDegrees = -90.0,
            rotationMatrix = matrix,
            viewportWidth = w,
            viewportHeight = h,
            displayRotation = displayRotation,
            fovHorizontalDegrees = fovH,
            outBuffer = tempPoint,
        )
        val nx = tempPoint[0]
        val ny = tempPoint[1]
        if (nadirInFront && nx in -50f..(w + 50f) && ny in -50f..(h + 50f)) {
            val r = 10f
            drawScope.drawLine(
                color = minorGridColor,
                start = Offset(nx - r, ny),
                end = Offset(nx + r, ny),
                strokeWidth = 1.2f,
            )
            drawScope.drawLine(
                color = minorGridColor,
                start = Offset(nx, ny - r),
                end = Offset(nx, ny + r),
                strokeWidth = 1.2f,
            )
            drawScope.drawCircle(
                color = minorGridColor,
                radius = 5f,
                center = Offset(nx, ny),
                style = Stroke(width = 1.0f),
            )
            drawScope.drawContext.canvas.nativeCanvas.drawText(
                nadirLabel,
                nx,
                ny - 13f,
                gridTextPaint,
            )
        }
    }
}

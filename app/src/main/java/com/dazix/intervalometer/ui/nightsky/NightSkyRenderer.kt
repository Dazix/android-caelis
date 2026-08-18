package com.dazix.intervalometer.ui.nightsky

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Surface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.dazix.intervalometer.ar.SkyDomeRenderer
import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.domain.nightsky.EquatorialCoordinates
import com.dazix.intervalometer.domain.nightsky.StarFrustumCulling
import kotlin.math.tan

/**
 * High-performance zero-allocation renderer for the Night Sky AR star field and constellation stick figures.
 *
 * Directly indexes Google FlatBuffers contiguous binary memory pools without object instantiations.
 * Renders stars in batch via [android.graphics.Canvas.drawPoints] and constellation line networks via [android.graphics.Canvas.drawLines].
 */
class NightSkyRenderer {

    val skyDomeRenderer = SkyDomeRenderer()

    private val maxStars = 4096
    private val tier1Points = FloatArray(maxStars * 2)
    private val tier2Points = FloatArray(maxStars * 2)
    private val tier3Points = FloatArray(maxStars * 2)

    // Cached screen projection coordinates for constellation line resolution
    private val starScreenX = FloatArray(maxStars)
    private val starScreenY = FloatArray(maxStars)
    private val starInFront = BooleanArray(maxStars)

    private val maxConstellationSegments = 1024
    private val constellationLinesBuffer = FloatArray(maxConstellationSegments * 4)

    private val tempWorldVec = FloatArray(3)
    private val reusableConstellation = Constellation()

    val tier1Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    val tier2Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    val tier3Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    val constellationPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }

    val constellationLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        setupStarPaints()
    }

    private fun setupStarPaints() {
        // Stars must be prominent, brilliant white or celestial warm yellow under all circumstances
        tier1Paint.color = AndroidColor.argb(255, 255, 255, 255) // Brilliant celestial white (V < 1.5)
        tier2Paint.color = AndroidColor.argb(245, 255, 245, 175) // Celestial warm yellow (1.5 <= V < 3.0)
        tier3Paint.color = AndroidColor.argb(215, 255, 255, 255) // Crisp clear white (3.0 <= V <= 4.8)
    }

    fun applyTheme(redVisionMode: Boolean) {
        skyDomeRenderer.applyTheme(redVisionMode)
        setupStarPaints()

        if (redVisionMode) {
            constellationPaint.color = AndroidColor.argb(160, 255, 23, 68)
            constellationLabelPaint.color = AndroidColor.argb(210, 255, 23, 68)
        } else {
            constellationPaint.color = AndroidColor.argb(170, 77, 208, 225)
            constellationLabelPaint.color = AndroidColor.argb(220, 77, 208, 225)
        }
    }

    fun updateStrokeWidths(tier1Px: Float, tier2Px: Float, tier3Px: Float, constellationPx: Float = 2.0f) {
        tier1Paint.strokeWidth = tier1Px
        tier2Paint.strokeWidth = tier2Px
        tier3Paint.strokeWidth = tier3Px
        constellationPaint.strokeWidth = constellationPx
    }

    /**
     * Projects and renders visible stars and constellation lines in batch using FlatBuffers memory mapping.
     * Zero heap allocations per frame.
     *
     * @return Total count of visible stars on screen.
     */
    fun drawNightSky(
        drawScope: DrawScope,
        catalog: NightSkyCatalog?,
        lstDegrees: Double,
        latitudeDegrees: Double,
        matrix: FloatArray,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
        w: Float,
        h: Float,
        showConstellations: Boolean = true,
    ): Int {
        if (catalog == null || matrix.size < 16) return 0
        val totalStars = minOf(catalog.starsLength(), maxStars)
        if (totalStars == 0) return 0

        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()

        var tier1Count = 0
        var tier2Count = 0
        var tier3Count = 0

        // 1. Camera optical axis in world ENU: v_cam = [-R[2], -R[6], -R[10]]
        val camFwdX = -matrix[2]
        val camFwdY = -matrix[6]
        val camFwdZ = -matrix[10]

        // 2. Compute Frustum Dot Threshold
        val dotThreshold = StarFrustumCulling.computeFrustumDotThreshold(fovHorizontalDegrees, w, h)

        // 3. Perspective projection parameters
        val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
        val focalLengthPixels = (w * 0.5f) / tan(halfFovHRad)
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val margin = 40.0f

        // 4. Batch process stars with ZERO heap allocations
        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val ra = bb.getFloat(offset).toDouble()
            val dec = bb.getFloat(offset + 4).toDouble()
            val mag = bb.getFloat(offset + 8)

            // Compute ENU world unit vector
            EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
            val vx = tempWorldVec[0]
            val vy = tempWorldVec[1]
            val vz = tempWorldVec[2]

            // Transform World -> Device coordinates: V_dev = R^T * V_world
            val xDev = matrix[0] * vx + matrix[4] * vy + matrix[8] * vz
            val yDev = matrix[1] * vx + matrix[5] * vy + matrix[9] * vz
            val zDev = matrix[2] * vx + matrix[6] * vy + matrix[10] * vz

            // Map physical device axes to camera screen coordinate system
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

            if (zs <= 0.001f) {
                starInFront[i] = false
                continue
            }

            // Perspective screen coordinates
            val screenX = halfW + focalLengthPixels * (xs / zs)
            val screenY = halfH - focalLengthPixels * (ys / zs)

            starScreenX[i] = screenX
            starScreenY[i] = screenY
            starInFront[i] = true

            // Vector Dot-Product Frustum Culling
            if (!StarFrustumCulling.isStarInFrustum(camFwdX, camFwdY, camFwdZ, vx, vy, vz, dotThreshold)) {
                continue
            }

            if (screenX !in -margin..(w + margin) || screenY !in -margin..(h + margin)) {
                continue
            }

            // Bucket by apparent magnitude for batch drawing
            if (mag < 1.5f) {
                val idx = tier1Count * 2
                tier1Points[idx] = screenX
                tier1Points[idx + 1] = screenY
                tier1Count++
            } else if (mag < 3.0f) {
                val idx = tier2Count * 2
                tier2Points[idx] = screenX
                tier2Points[idx + 1] = screenY
                tier2Count++
            } else {
                val idx = tier3Count * 2
                tier3Points[idx] = screenX
                tier3Points[idx + 1] = screenY
                tier3Count++
            }
        }

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas

        // 5. Batch Render Constellation Lines
        if (showConstellations) {
            val constCount = catalog.constellationsLength()
            var lineSegmentCount = 0

            for (c in 0 until constCount) {
                catalog.constellations(reusableConstellation, c)
                val linesLen = reusableConstellation.linesLength()
                for (k in 0 until linesLen - 1 step 2) {
                    val i1 = reusableConstellation.lines(k)
                    val i2 = reusableConstellation.lines(k + 1)

                    if (i1 in 0 until totalStars && i2 in 0 until totalStars) {
                        if (starInFront[i1] && starInFront[i2]) {
                            val x1 = starScreenX[i1]
                            val y1 = starScreenY[i1]
                            val x2 = starScreenX[i2]
                            val y2 = starScreenY[i2]

                            if ((x1 in -margin..(w + margin) && y1 in -margin..(h + margin)) ||
                                (x2 in -margin..(w + margin) && y2 in -margin..(h + margin))) {
                                if (lineSegmentCount < maxConstellationSegments) {
                                    val idx = lineSegmentCount * 4
                                    constellationLinesBuffer[idx] = x1
                                    constellationLinesBuffer[idx + 1] = y1
                                    constellationLinesBuffer[idx + 2] = x2
                                    constellationLinesBuffer[idx + 3] = y2
                                    lineSegmentCount++
                                }
                            }
                        }
                    }
                }
            }

            if (lineSegmentCount > 0) {
                nativeCanvas.drawLines(constellationLinesBuffer, 0, lineSegmentCount * 4, constellationPaint)
            }
        }

        // 6. Batch render all visible stars using native Canvas.drawPoints
        if (tier3Count > 0) {
            nativeCanvas.drawPoints(tier3Points, 0, tier3Count * 2, tier3Paint)
        }
        if (tier2Count > 0) {
            nativeCanvas.drawPoints(tier2Points, 0, tier2Count * 2, tier2Paint)
        }
        if (tier1Count > 0) {
            nativeCanvas.drawPoints(tier1Points, 0, tier1Count * 2, tier1Paint)
        }

        return tier1Count + tier2Count + tier3Count
    }
}

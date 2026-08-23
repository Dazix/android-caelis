package com.dazix.intervalometer.ui.nightsky

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.dazix.intervalometer.ar.SkyDomeRenderer
import com.dazix.intervalometer.data.nightsky.Constellation
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.domain.nightsky.CelestialBodyDetails
import com.dazix.intervalometer.domain.nightsky.CelestialMatrixCalculator
import com.dazix.intervalometer.domain.nightsky.EquatorialCoordinates
import com.dazix.intervalometer.domain.nightsky.StarFrustumCulling
import com.dazix.intervalometer.domain.nightsky.StarHitTester
import com.dazix.intervalometer.domain.nightsky.StarOptics
import kotlin.math.tan

/**
 * High-performance zero-allocation renderer for the Night Sky AR star field and constellation stick figures.
 *
 * Directly indexes Google FlatBuffers contiguous binary memory pools without object instantiations.
 * Renders stars in batch via [android.graphics.Canvas.drawPoints] and constellation line networks via [android.graphics.Canvas.drawLines].
 * Renders background layers (Opaque Sky & Milky Way) using hardware-accelerated AGSL [android.graphics.RuntimeShader].
 */
class NightSkyRenderer {

    val skyDomeRenderer = SkyDomeRenderer()

    // Pre-allocated AGSL ViewMatrix uniform buffer (3x3 column-major)
    private val matrixBuffer = FloatArray(9)
    private var textureWidth = 2048f
    private var textureHeight = 1024f
    private var hasTexture = false

    private val opaqueColorNormal = Color(0xFF050810)
    private val opaqueColorRed = Color(0xFF080000)

    val runtimeShader: RuntimeShader? = runCatching {
        RuntimeShader(MilkyWayShader.SRC)
    }.getOrNull()

    val shaderBrush: ShaderBrush? = runtimeShader?.let { ShaderBrush(it) }

    val atmosphereShader: RuntimeShader? = runCatching {
        RuntimeShader(AtmosphereShader.SRC)
    }.getOrNull()

    val atmosphereBrush: ShaderBrush? = atmosphereShader?.let { ShaderBrush(it) }

    private val atmosphereMatrixBuffer = FloatArray(9)

    val landscapeShader: RuntimeShader? = runCatching {
        RuntimeShader(LandscapeShader.SRC)
    }.getOrNull()

    val landscapeBrush: ShaderBrush? = landscapeShader?.let { ShaderBrush(it) }

    private val landscapeMatrixBuffer = FloatArray(9)
    private var landscapeTextureWidth = 2048f
    private var landscapeTextureHeight = 1024f
    private var hasLandscapeTexture = false

    private val maxStars = 4096
    val numBuckets = StarOptics.NUM_BUCKETS
    val bucketPoints = Array(numBuckets) { FloatArray(maxStars * 2) }
    val bucketCounts = IntArray(numBuckets)

    // Cached screen projection coordinates for constellation line resolution
    private val starScreenX = FloatArray(maxStars)
    private val starScreenY = FloatArray(maxStars)
    private val starInFront = BooleanArray(maxStars)

    private val maxConstellationSegments = 1024
    private val constellationLinesBuffer = FloatArray(maxConstellationSegments * 4)

    private val tempWorldVec = FloatArray(3)
    private val reusableConstellation = Constellation()

    companion object {
        /**
         * Creates a hardware-accelerated continuous Gaussian radial gradient star sprite bitmap once.
         * Pure white nucleus fading smoothly to zero opacity edge without concentric ring artifacts.
         */
        fun createStarSpriteBitmap(sizePx: Int = StarOptics.STAR_SPRITE_SIZE): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val center = sizePx / 2.0f
            val radius = sizePx / 2.0f

            val gradient = RadialGradient(
                center, center, radius,
                intArrayOf(
                    0xFFFFFFFF.toInt(), // 100% Solid white core (0% to 15%)
                    0xD8FFFFFF.toInt(), // ~85% Bright inner core (15% to 38%)
                    0x60FFFFFF,         // ~38% Soft optical glow (38% to 70%)
                    0x15FFFFFF,         // ~8% Faint outer corona (70% to 88%)
                    0x00FFFFFF,         // 0% Transparent boundary (100%)
                ),
                floatArrayOf(0.0f, 0.15f, 0.38f, 0.70f, 1.0f),
                Shader.TileMode.CLAMP
            )

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
                isDither = true
            }
            canvas.drawCircle(center, center, radius, paint)
            return bitmap
        }
    }

    // Pre-Rendered Radial Gradient Star Sprite (64x64 px ARGB_8888)
    val starSpriteBitmap: Bitmap? = runCatching { createStarSpriteBitmap(StarOptics.STAR_SPRITE_SIZE) }.getOrNull()
    val starSpriteSize = StarOptics.STAR_SPRITE_SIZE.toFloat()
    val starSpriteHalfSize = starSpriteSize / 2.0f

    // Zero-allocation matrix and paint for blitting the star sprite
    val spriteMatrix = Matrix()
    val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private val redVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(255, 23, 68), PorterDuff.Mode.SRC_IN)

    // Base optical sprite glow diameters in DP for bright & medium star buckets (Bucket 0: mag < 1.0, Bucket 1: 1.0..2.0, Bucket 2: 2.0..3.0)
    private val baseSpriteDiameterDp = floatArrayOf(30.0f, 20.0f, 13.0f)
    private val spriteDiameterPx = FloatArray(3)

    // Pinpoint core stroke width in DP for all 6 buckets (0..5)
    private val baseCoreDp = floatArrayOf(3.2f, 2.5f, 2.0f, 1.6f, 1.2f, 1.0f)

    // Pinpoint core paints for central nucleus across all 6 buckets
    val corePaints = Array(numBuckets) {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
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

    // Zero-allocation broken-crosshair selection reticle & target text paints and buffers
    val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.SQUARE
    }

    val reticleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
    }

    // 4 line segments * 4 coordinates (x0, y0, x1, y1) = 16 floats
    private val reticleLinesBuffer = FloatArray(16)
    private val tempReticleVec = FloatArray(3)

    init {
        setupStarPaints(redVisionMode = false)
    }

    private fun setupStarPaints(redVisionMode: Boolean) {
        if (redVisionMode) {
            spritePaint.colorFilter = redVisionFilter
            for (b in 0 until numBuckets) {
                corePaints[b].color = AndroidColor.argb(255, 255, 82, 82) // Bright red pinpoint core
            }
        } else {
            spritePaint.colorFilter = null
            for (b in 0 until numBuckets) {
                val coreColor = if (b == 0) {
                    AndroidColor.argb(255, 255, 255, 255) // Brilliant white core
                } else if (b == 1) {
                    AndroidColor.argb(255, 255, 250, 240) // Warm celestial core
                } else {
                    AndroidColor.argb(255, 255, 255, 255) // Pure pinpoint white
                }
                corePaints[b].color = coreColor
            }
        }
    }

    fun applyTheme(redVisionMode: Boolean) {
        skyDomeRenderer.applyTheme(redVisionMode)
        setupStarPaints(redVisionMode)

        if (redVisionMode) {
            constellationPaint.color = AndroidColor.argb(160, 255, 23, 68)
            constellationLabelPaint.color = AndroidColor.argb(210, 255, 23, 68)
            reticlePaint.color = AndroidColor.rgb(255, 23, 68)
            reticleTextPaint.color = AndroidColor.rgb(255, 23, 68)
        } else {
            constellationPaint.color = AndroidColor.argb(170, 77, 208, 225)
            constellationLabelPaint.color = AndroidColor.argb(220, 77, 208, 225)
            reticlePaint.color = AndroidColor.WHITE
            reticleTextPaint.color = AndroidColor.WHITE
        }
    }

    /**
     * Updates optical stroke widths scaling by display density and zoom point-spread function.
     * Zero allocations.
     */
    fun updateOpticalPaints(
        zoomFactor: Float = 1.0f,
        density: Float = 1.0f,
        redVisionMode: Boolean = false,
        constellationPx: Float = 2.0f,
    ) {
        val opticScale = StarOptics.computeOpticScale(zoomFactor)
        val densityScale = density.coerceAtLeast(0.5f)

        // Compute scaled sprite diameters for bright buckets 0, 1, 2
        for (b in 0..2) {
            spriteDiameterPx[b] = baseSpriteDiameterDp[b] * densityScale * opticScale
        }

        // Compute pinpoint core stroke widths for all 6 buckets
        for (b in 0 until numBuckets) {
            corePaints[b].strokeWidth = baseCoreDp[b] * densityScale * opticScale
        }
        constellationPaint.strokeWidth = constellationPx
        reticlePaint.strokeWidth = 2.2f * densityScale
        reticleTextPaint.textSize = 15f * densityScale
    }

    /**
     * Backward compatibility helper for legacy stroke width updates.
     */
    fun updateStrokeWidths(tier1Px: Float, tier2Px: Float, tier3Px: Float, constellationPx: Float = 2.0f) {
        updateOpticalPaints(zoomFactor = 1.0f, density = tier1Px / 7.5f, constellationPx = constellationPx)
    }

    /**
     * Binds the Milky Way equirectangular WebP texture to the AGSL RuntimeShader.
     */
    fun setMilkyWayBitmap(bitmap: Bitmap) {
        if (runtimeShader != null) {
            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            runtimeShader.setInputShader("milkyWayTexture", bitmapShader)
            textureWidth = bitmap.width.toFloat()
            textureHeight = bitmap.height.toFloat()
            hasTexture = true
        }
    }

    /**
     * Binds the Photorealistic Landscape equirectangular WebP texture to the AGSL RuntimeShader.
     */
    fun setLandscapeBitmap(bitmap: Bitmap) {
        if (landscapeShader != null) {
            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            landscapeShader.setInputShader("landscapeTexture", bitmapShader)
            landscapeTextureWidth = bitmap.width.toFloat()
            landscapeTextureHeight = bitmap.height.toFloat()
            hasLandscapeTexture = true
        }
    }

    /**
     * Renders background layers:
     * - Layer 0: Opaque Sky Background (Planetarium Mode #050810 / #080000)
     * - Layer 1: Hardware-Accelerated AGSL Milky Way Ray-Casting Shader
     * - Layer 1b: Hardware-Accelerated AGSL Atmosphere Glow Shader
     *
     * 100% Zero heap allocations per frame.
     */
    fun drawSkyBackground(
        drawScope: DrawScope,
        matrix: FloatArray,
        lstDegrees: Double,
        latitudeDegrees: Double,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
        w: Float,
        h: Float,
        isSkyBackgroundOpaque: Boolean,
        isMilkyWayVisible: Boolean,
        isAtmosphereVisible: Boolean,
        isRedVision: Boolean,
    ) {
        // Layer 0: Opaque Sky Background
        if (isSkyBackgroundOpaque) {
            drawScope.drawRect(color = if (isRedVision) opaqueColorRed else opaqueColorNormal)
        }

        // Layer 1: Hardware-Accelerated AGSL Milky Way Shader
        if (isMilkyWayVisible && hasTexture && runtimeShader != null && shaderBrush != null && matrix.size >= 16) {
            val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
            val fovFactor = 1.0f / tan(halfFovHRad)

            CelestialMatrixCalculator.computeViewMatrix(
                sensorMatrix16 = matrix,
                lstDegrees = lstDegrees,
                latitudeDegrees = latitudeDegrees,
                displayRotation = displayRotation,
                outMatrix9 = matrixBuffer,
            )

            runtimeShader.setFloatUniform("resolution", w, h)
            runtimeShader.setFloatUniform("textureSize", textureWidth, textureHeight)
            runtimeShader.setFloatUniform("viewMatrix", matrixBuffer)
            runtimeShader.setFloatUniform("fovFactor", fovFactor)
            runtimeShader.setFloatUniform("isRedVision", if (isRedVision) 1.0f else 0.0f)

            drawScope.drawRect(brush = shaderBrush)
        }

        // Layer 1b: Hardware-Accelerated AGSL Atmosphere Glow Shader
        if (isAtmosphereVisible && atmosphereShader != null && atmosphereBrush != null && matrix.size >= 16) {
            val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
            val fovFactor = 1.0f / tan(halfFovHRad)

            CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                sensorMatrix16 = matrix,
                displayRotation = displayRotation,
                outMatrix9 = atmosphereMatrixBuffer,
            )

            atmosphereShader.setFloatUniform("resolution", w, h)
            atmosphereShader.setFloatUniform("viewMatrix", atmosphereMatrixBuffer)
            atmosphereShader.setFloatUniform("fovFactor", fovFactor)
            atmosphereShader.setFloatUniform("isRedVision", if (isRedVision) 1.0f else 0.0f)

            drawScope.drawRect(brush = atmosphereBrush)
        }
    }

    /**
     * Renders the hardware-accelerated AGSL Photorealistic Landscape (Ground & Tree line) Layer.
     * Fixed to Earth ENU coordinates without celestial LST rotation.
     * Renders on top of stars below the horizon.
     *
     * 100% Zero heap allocations per frame.
     */
    fun drawLandscape(
        drawScope: DrawScope,
        matrix: FloatArray,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
        w: Float,
        h: Float,
        isLandscapeVisible: Boolean,
        isRedVision: Boolean,
    ) {
        if (isLandscapeVisible && hasLandscapeTexture && landscapeShader != null && landscapeBrush != null && matrix.size >= 16) {
            val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
            val fovFactor = 1.0f / tan(halfFovHRad)

            CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                sensorMatrix16 = matrix,
                displayRotation = displayRotation,
                outMatrix9 = landscapeMatrixBuffer,
            )

            landscapeShader.setFloatUniform("resolution", w, h)
            landscapeShader.setFloatUniform("textureSize", landscapeTextureWidth, landscapeTextureHeight)
            landscapeShader.setFloatUniform("viewMatrix", landscapeMatrixBuffer)
            landscapeShader.setFloatUniform("fovFactor", fovFactor)
            landscapeShader.setFloatUniform("isRedVision", if (isRedVision) 1.0f else 0.0f)

            drawScope.drawRect(brush = landscapeBrush)
        }
    }

    /**
     * Performs ultra-fast 2D screen-space hit testing to identify the star closest to the user's tap coordinates.
     * Zero heap allocations.
     *
     * @param tapX Touch X coordinate in screen pixels.
     * @param tapY Touch Y coordinate in screen pixels.
     * @param maxRadiusPx Maximum search radius in pixels.
     * @param totalStars Number of stars in the catalog.
     * @return Catalog star index [0, totalStars - 1], or -1 if no star was hit.
     */
    fun findClosestStarAt(
        tapX: Float,
        tapY: Float,
        maxRadiusPx: Float,
        totalStars: Int,
    ): Int {
        return StarHitTester.findClosestVisibleStar(
            tapX = tapX,
            tapY = tapY,
            starScreenX = starScreenX,
            starScreenY = starScreenY,
            starInFront = starInFront,
            totalStars = totalStars,
            maxRadiusPx = maxRadiusPx,
        )
    }

    /**
     * Projects and renders visible stars and constellation lines in batch using FlatBuffers memory mapping.
     * Dual-pass optical rendering: Pass 1 renders soft optical halos, Pass 2 renders pinpoint cores.
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
        zoomFactor: Float = 1.0f,
        showConstellations: Boolean = true,
        isAtmosphereVisible: Boolean = true,
    ): Int {
        if (catalog == null || matrix.size < 16) return 0
        val totalStars = minOf(catalog.starsLength(), maxStars)
        if (totalStars == 0) return 0

        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()

        // Reset bucket visible counts
        for (b in 0 until numBuckets) {
            bucketCounts[b] = 0
        }

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas

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

        // Dynamic limiting magnitude computed from zoom factor
        val faintestVisibleMag = StarOptics.computeFaintestVisibleMagnitude(zoomFactor)
        var totalVisibleStars = 0

        // 4. Batch process stars with ZERO heap allocations
        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val ra = bb.getFloat(offset).toDouble()
            val dec = bb.getFloat(offset + 4).toDouble()
            val rawMag = bb.getFloat(offset + 8)

            // Dynamic telescope ocular reveal: cull stars fainter than current zoom limit
            if (rawMag > faintestVisibleMag) {
                starInFront[i] = false
                continue
            }

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

            // Physical Atmospheric Star Extinction (dim stars near horizon 0° <= h < 15°)
            // Stars below horizon (h <= 0°) remain unextinguished (occluded naturally by landscape layer)
            var effectiveMag = rawMag
            if (isAtmosphereVisible) {
                val starElevationDeg = (Math.toDegrees(kotlin.math.asin(vz.coerceIn(-1.0f, 1.0f).toDouble()))).toFloat()
                if (starElevationDeg in 0.0f..StarOptics.EXTINCTION_ANGLE_DEG) {
                    effectiveMag = StarOptics.computeAirmassExtinction(rawMag, starElevationDeg)
                    if (effectiveMag > faintestVisibleMag) {
                        continue
                    }
                }
            }

            // Map apparent magnitude into one of the 6 fixed pre-allocated rendering buckets
            val bucketIndex = StarOptics.computeBucketIndex(effectiveMag)
            val count = bucketCounts[bucketIndex]
            if (count < maxStars) {
                val idx = count * 2
                bucketPoints[bucketIndex][idx] = screenX
                bucketPoints[bucketIndex][idx + 1] = screenY
                bucketCounts[bucketIndex] = count + 1
                totalVisibleStars++
            }
        }

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

        // 6. Hybrid Optical Star Rendering:
        // Pass 1: Continuous Radial Gradient Sprite Glow for Bright Stars (Buckets 0, 1, 2: mag < 3.0)
        val sprite = starSpriteBitmap
        if (sprite != null) {
            for (b in 2 downTo 0) {
                val count = bucketCounts[b]
                if (count > 0) {
                    val diameter = spriteDiameterPx[b]
                    val scale = diameter / starSpriteSize
                    val halfScaled = starSpriteHalfSize * scale
                    val points = bucketPoints[b]

                    for (k in 0 until count) {
                        val sx = points[k * 2]
                        val sy = points[k * 2 + 1]

                        spriteMatrix.reset()
                        spriteMatrix.postScale(scale, scale)
                        spriteMatrix.postTranslate(sx - halfScaled, sy - halfScaled)
                        nativeCanvas.drawBitmap(sprite, spriteMatrix, spritePaint)
                    }
                }
            }
        }

        // Pass 2: Hot Pinpoint Cores (Batched drawPoints for all populated buckets 0..5)
        for (b in numBuckets - 1 downTo 0) {
            val count = bucketCounts[b]
            if (count > 0) {
                nativeCanvas.drawPoints(bucketPoints[b], 0, count * 2, corePaints[b])
            }
        }

        return totalVisibleStars
    }

    /**
     * Renders the zero-allocation broken-crosshair selection reticle and name label for the selected celestial body.
     * Rendered on top of all celestial, atmospheric, landscape, and grid layers.
     *
     * Zero heap allocations per frame.
     */
    fun drawSelectionReticle(
        drawScope: DrawScope,
        selectedBody: CelestialBodyDetails?,
        matrix: FloatArray,
        lstDegrees: Double,
        latitudeDegrees: Double,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
        w: Float,
        h: Float,
        density: Float = 1.0f,
    ) {
        if (selectedBody == null || matrix.size < 16) return
        val ra = selectedBody.raDegrees ?: return
        val dec = selectedBody.decDegrees ?: return

        // Compute ENU world unit vector
        EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempReticleVec, 0)
        val vx = tempReticleVec[0]
        val vy = tempReticleVec[1]
        val vz = tempReticleVec[2]

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
        if (zs <= 0.001f) return

        // Perspective projection
        val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
        val focalLengthPixels = (w * 0.5f) / tan(halfFovHRad)
        val screenX = w * 0.5f + focalLengthPixels * (xs / zs)
        val screenY = h * 0.5f - focalLengthPixels * (ys / zs)

        val margin = 50.0f
        if (screenX !in -margin..(w + margin) || screenY !in -margin..(h + margin)) {
            return
        }

        val r1 = 16f * density
        val r2 = 30f * density

        // Left arm
        reticleLinesBuffer[0] = screenX - r2
        reticleLinesBuffer[1] = screenY
        reticleLinesBuffer[2] = screenX - r1
        reticleLinesBuffer[3] = screenY

        // Right arm
        reticleLinesBuffer[4] = screenX + r1
        reticleLinesBuffer[5] = screenY
        reticleLinesBuffer[6] = screenX + r2
        reticleLinesBuffer[7] = screenY

        // Top arm
        reticleLinesBuffer[8] = screenX
        reticleLinesBuffer[9] = screenY - r2
        reticleLinesBuffer[10] = screenX
        reticleLinesBuffer[11] = screenY - r1

        // Bottom arm
        reticleLinesBuffer[12] = screenX
        reticleLinesBuffer[13] = screenY + r1
        reticleLinesBuffer[14] = screenX
        reticleLinesBuffer[15] = screenY + r2

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas
        nativeCanvas.drawLines(reticleLinesBuffer, 0, 16, reticlePaint)

        val textY = screenY - r2 - (8f * density)
        nativeCanvas.drawText(selectedBody.name, screenX, textY, reticleTextPaint)
    }
}

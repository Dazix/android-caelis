package com.dazix.intervalometer.ui.nightsky

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
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

    private val maxStars = 10240
    val numBuckets = StarOptics.NUM_BUCKETS
    val bucketPoints = Array(numBuckets) { FloatArray(maxStars * 2) }
    val bucketCounts = IntArray(numBuckets)

    // Cached screen projection coordinates for constellation line resolution
    private val starScreenX = FloatArray(maxStars)
    private val starScreenY = FloatArray(maxStars)
    private val starInFront = BooleanArray(maxStars)

    // Fast O(1) zero-allocation lookup table to force render constituent stars of visible constellations
    val forceRenderStar = BooleanArray(maxStars)

    private val maxConstellations = 256
    val constellationProgress = FloatArray(maxConstellations) { 0f }
    val constellationCentroidScreenX = FloatArray(maxConstellations)
    val constellationCentroidScreenY = FloatArray(maxConstellations)
    val constellationCentroidInFront = BooleanArray(maxConstellations)
    private val tempCentroidWorldVec = FloatArray(3)
    private var lastFrameTimeMs: Long = 0L

    var selectedConstellationIndex: Int = -1
    var selectedStarIndex: Int = -1

    private val maxConstellationSegments = 2048
    // 2 line segments per pair (A to midpoint, B to midpoint) = 8 floats per segment
    private val constellationLinesBuffer = FloatArray(maxConstellationSegments * 8)

    private val tempWorldVec = FloatArray(3)
    private val reusableConstellation = Constellation()

    companion object {
        data class IndicatorTransform(
            val arrowX: Float,
            val arrowY: Float,
            val angleRad: Float,
            val angleDeg: Float,
        )

        /**
         * Pure mathematical calculation of bearing angle and screen edge bounding box intersection
         * for off-screen target tracking. Zero heap allocation when inlined / evaluated.
         */
        fun computeOffscreenIndicator(
            xs: Float,
            ys: Float,
            zs: Float,
            screenX: Float,
            screenY: Float,
            w: Float,
            h: Float,
            edgePadding: Float,
        ): IndicatorTransform {
            val centerX = w * 0.5f
            val centerY = h * 0.5f
            val halfW = centerX - edgePadding
            val halfH = centerY - edgePadding

            var dx = if (zs > 0.001f) (screenX - centerX) else xs
            var dy = if (zs > 0.001f) (screenY - centerY) else -ys

            if (kotlin.math.abs(dx) < 0.0001f && kotlin.math.abs(dy) < 0.0001f) {
                dy = 1f
            }

            val angleRad = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
            val tanAngle = kotlin.math.tan(angleRad.toDouble()).toFloat()

            val intersectX: Float
            val intersectY: Float

            if (kotlin.math.abs(halfW * tanAngle) < halfH) {
                intersectX = if (dx > 0) halfW else -halfW
                intersectY = intersectX * tanAngle
            } else {
                intersectY = if (dy > 0) halfH else -halfH
                intersectX = intersectY / tanAngle
            }

            val arrowX = centerX + intersectX
            val arrowY = centerY + intersectY
            val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

            return IndicatorTransform(arrowX, arrowY, angleRad, angleDeg)
        }

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
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 1.2f
    }

    val constellationHaloPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 5.4f
    }

    val constellationCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 2.16f
    }

    val constellationLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val constellationHighlightLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(10f, 0f, 0f, AndroidColor.BLACK)
    }

    // Pre-allocated paints for Asterisms (fainter line styles, italic typography)
    val asterismPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 1.0f
    }

    val asterismHaloPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 4.2f
    }

    val asterismCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 1.7f
    }

    val asterismLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }

    val asterismHighlightLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
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

    // Zero-allocation directional arrow indicator for off-screen selected celestial objects
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD700.toInt() // Golden highlight color to match selection
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
    }

    // Centered chevron arrow shape pointing to the right (0 degrees)
    val arrowPath = Path().apply {
        moveTo(20f, 0f)    // Tip
        lineTo(-20f, -15f) // Top wing
        lineTo(-10f, 0f)   // Inner indent
        lineTo(-20f, 15f)  // Bottom wing
        close()
    }

    // 3 line segments * 4 coordinates (x0, y0, x1, y1) = 12 floats (Left, Right, Bottom arms)
    private val reticleLinesBuffer = FloatArray(12)
    private val tempReticleVec = FloatArray(3)

    // Pre-allocated artwork transformation matrix, paints, filters, and projection buffers
    val artworkCache = LruCache<String, Bitmap>(16)
    val artMatrix = Matrix()
    val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val redVisionArtFilter = PorterDuffColorFilter(AndroidColor.RED, PorterDuff.Mode.MULTIPLY)
    private val tempArtCenterWorldVec = FloatArray(3)
    private val tempArtTopWorldVec = FloatArray(3)

    fun getOrLoadArtwork(context: Context, resourceName: String): Bitmap? {
        var bitmap = artworkCache.get(resourceName)
        if (bitmap == null) {
            val resId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
            if (resId != 0) {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                bitmap = BitmapFactory.decodeResource(context.resources, resId, options)
                if (bitmap != null) {
                    artworkCache.put(resourceName, bitmap)
                }
            }
        }
        return bitmap
    }

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
            artPaint.colorFilter = redVisionArtFilter

            constellationPaint.color = AndroidColor.argb(160, 255, 23, 68)
            constellationHaloPaint.color = AndroidColor.argb(50, 255, 23, 68)
            constellationCorePaint.color = AndroidColor.argb(255, 255, 82, 82)
            constellationLabelPaint.color = AndroidColor.argb(210, 255, 23, 68)
            constellationHighlightLabelPaint.color = AndroidColor.rgb(255, 23, 68)

            asterismPaint.color = AndroidColor.argb(120, 255, 23, 68)
            asterismHaloPaint.color = AndroidColor.argb(40, 255, 23, 68)
            asterismCorePaint.color = AndroidColor.argb(230, 255, 82, 82)
            asterismLabelPaint.color = AndroidColor.argb(170, 255, 82, 82)
            asterismHighlightLabelPaint.color = AndroidColor.rgb(255, 82, 82)

            reticlePaint.color = AndroidColor.rgb(255, 23, 68)
            reticleTextPaint.color = AndroidColor.rgb(255, 23, 68)
            arrowPaint.color = AndroidColor.rgb(255, 23, 68)
        } else {
            artPaint.colorFilter = null

            constellationPaint.color = AndroidColor.argb(170, 77, 208, 225)
            constellationHaloPaint.color = AndroidColor.argb(45, 128, 222, 234)
            constellationCorePaint.color = AndroidColor.argb(255, 255, 255, 255)
            constellationLabelPaint.color = AndroidColor.argb(220, 77, 208, 225)
            constellationHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)

            asterismPaint.color = AndroidColor.argb(130, 144, 202, 249)
            asterismHaloPaint.color = AndroidColor.argb(35, 144, 202, 249)
            asterismCorePaint.color = AndroidColor.argb(240, 240, 248, 255)
            asterismLabelPaint.color = AndroidColor.argb(180, 176, 190, 197)
            asterismHighlightLabelPaint.color = AndroidColor.rgb(129, 212, 250)

            reticlePaint.color = AndroidColor.WHITE
            reticleTextPaint.color = AndroidColor.WHITE
            arrowPaint.color = 0xFFFFD700.toInt()
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
        constellationPaint.strokeWidth = 1.45f * densityScale
        constellationHaloPaint.strokeWidth = 5.4f * densityScale
        constellationCorePaint.strokeWidth = 2.16f * densityScale
        constellationLabelPaint.textSize = 13f * densityScale
        constellationHighlightLabelPaint.textSize = 15f * densityScale

        asterismPaint.strokeWidth = 1.15f * densityScale
        asterismHaloPaint.strokeWidth = 4.2f * densityScale
        asterismCorePaint.strokeWidth = 1.7f * densityScale
        asterismLabelPaint.textSize = 10.5f * densityScale
        asterismHighlightLabelPaint.textSize = 12.5f * densityScale

        reticlePaint.strokeWidth = 2.2f * densityScale
        reticleTextPaint.textSize = 15f * densityScale

        skyDomeRenderer.horizonTextPaint.textSize = 10.5f * densityScale
        skyDomeRenderer.gridTextPaint.textSize = 9.0f * densityScale
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

    sealed interface HitTarget {
        data class ConstellationHit(val index: Int) : HitTarget
        data class StarHit(val index: Int) : HitTarget
        data object Miss : HitTarget
    }

    /**
     * Performs prioritized 2D screen-space hit testing:
     * 1. In-focus constellation name text (progress > 0.3f)
     * 2. Visible stars within maxRadiusPx touch radius
     * 3. Miss
     * Zero heap allocations on the hit sweep.
     */
    fun findHitAt(
        tapX: Float,
        tapY: Float,
        maxStarRadiusPx: Float,
        density: Float,
        catalog: NightSkyCatalog?,
    ): HitTarget {
        if (catalog == null) return HitTarget.Miss

        // Priority 1: Constellations
        val constCount = minOf(catalog.constellationsLength(), maxConstellations)
        val constHit = StarHitTester.findConstellationTextHit(
            tapX = tapX,
            tapY = tapY,
            centroidScreenX = constellationCentroidScreenX,
            centroidScreenY = constellationCentroidScreenY,
            centroidInFront = constellationCentroidInFront,
            constellationProgress = constellationProgress,
            totalConstellations = constCount,
            textOffsetYPx = 40f * density,
            halfWidthPx = 75f * density,
            halfHeightPx = 30f * density,
            minProgress = 0.3f,
        )
        if (constHit >= 0) {
            return HitTarget.ConstellationHit(constHit)
        }

        // Priority 2: Stars
        val starHit = StarHitTester.findClosestVisibleStar(
            tapX = tapX,
            tapY = tapY,
            starScreenX = starScreenX,
            starScreenY = starScreenY,
            starInFront = starInFront,
            totalStars = catalog.starsLength(),
            maxRadiusPx = maxStarRadiusPx,
        )
        if (starHit >= 0) {
            return HitTarget.StarHit(starHit)
        }

        return HitTarget.Miss
    }

    /**
     * Performs ultra-fast 2D screen-space hit testing to identify the star closest to the user's tap coordinates.
     * Zero heap allocations.
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
        showConstellationArt: Boolean = true,
        isAtmosphereVisible: Boolean = true,
        context: Context? = null,
        debugOffsetX: Float = 0f,
        debugOffsetY: Float = 0f,
        debugScaleMultiplier: Float = 1.0f,
        debugRotationOffset: Float = 0f,
    ): Int {
        if (catalog == null || matrix.size < 16) return 0
        val totalStars = minOf(catalog.starsLength(), maxStars)
        if (totalStars == 0) return 0

        // Delta-time calculation for smooth 60 FPS animation progress
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (lastFrameTimeMs == 0L) 0.016f else ((now - lastFrameTimeMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameTimeMs = now

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

        // 4. Constellation Centroid Focus & Animation Progress Update
        val constCount = minOf(catalog.constellationsLength(), maxConstellations)
        val focusRadius = w * 0.60f
        val focusRadiusSq = focusRadius * focusRadius

        for (c in 0 until constCount) {
            catalog.constellations(reusableConstellation, c)
            val cRa = reusableConstellation.centroidRa().toDouble()
            val cDec = reusableConstellation.centroidDec().toDouble()

            EquatorialCoordinates.computeWorldVector(cRa, cDec, lstDegrees, latitudeDegrees, tempCentroidWorldVec, 0)
            val cVx = tempCentroidWorldVec[0]
            val cVy = tempCentroidWorldVec[1]
            val cVz = tempCentroidWorldVec[2]

            val cxDev = matrix[0] * cVx + matrix[4] * cVy + matrix[8] * cVz
            val cyDev = matrix[1] * cVx + matrix[5] * cVy + matrix[9] * cVz
            val czDev = matrix[2] * cVx + matrix[6] * cVy + matrix[10] * cVz

            val cxs: Float
            val cys: Float
            when (displayRotation) {
                Surface.ROTATION_0 -> { cxs = cxDev; cys = cyDev }
                Surface.ROTATION_90 -> { cxs = cyDev; cys = -cxDev }
                Surface.ROTATION_180 -> { cxs = -cxDev; cys = -cyDev }
                Surface.ROTATION_270 -> { cxs = -cyDev; cys = cxDev }
                else -> { cxs = cxDev; cys = cyDev }
            }
            val czs = -czDev

            if (czs > 0.001f) {
                val cxScreen = halfW + focalLengthPixels * (cxs / czs)
                val cyScreen = halfH - focalLengthPixels * (cys / czs)
                constellationCentroidScreenX[c] = cxScreen
                constellationCentroidScreenY[c] = cyScreen
                constellationCentroidInFront[c] = true

                val cdx = cxScreen - halfW
                val cdy = cyScreen - halfH
                val inFocus = showConstellations && ((cdx * cdx + cdy * cdy) <= focusRadiusSq)
                if (inFocus) {
                    constellationProgress[c] = minOf(1.0f, constellationProgress[c] + dt * 2.0f)
                } else {
                    constellationProgress[c] = maxOf(0.0f, constellationProgress[c] - dt * 2.0f)
                }
            } else {
                constellationCentroidInFront[c] = false
                constellationProgress[c] = maxOf(0.0f, constellationProgress[c] - dt * 2.0f)
            }
        }

        // 5. Fast Dynamic Override Table: force render stars of visible constellations
        forceRenderStar.fill(false)
        if (showConstellations) {
            for (c in 0 until constCount) {
                val progress = constellationProgress[c]
                if (progress > 0.001f) {
                    catalog.constellations(reusableConstellation, c)
                    val linesLen = reusableConstellation.linesLength()
                    for (j in 0 until linesLen) {
                        val starIndex = reusableConstellation.lines(j)
                        if (starIndex in 0 until totalStars) {
                            forceRenderStar[starIndex] = true
                        }
                    }
                }
            }
        }

        // 6. Batch process stars with ZERO heap allocations
        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val ra = bb.getFloat(offset).toDouble()
            val dec = bb.getFloat(offset + 4).toDouble()
            val rawMag = bb.getFloat(offset + 8)

            val isForced = forceRenderStar[i]
            val isBrightEnough = rawMag <= faintestVisibleMag

            // Dynamic telescope ocular reveal: cull stars fainter than current zoom limit unless forced by visible constellation
            if (!isBrightEnough && !isForced) {
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
            var effectiveMag = rawMag
            if (isAtmosphereVisible) {
                val starElevationDeg = (Math.toDegrees(kotlin.math.asin(vz.coerceIn(-1.0f, 1.0f).toDouble()))).toFloat()
                if (starElevationDeg in 0.0f..StarOptics.EXTINCTION_ANGLE_DEG) {
                    effectiveMag = StarOptics.computeAirmassExtinction(rawMag, starElevationDeg)
                    if (effectiveMag > faintestVisibleMag && !isForced) {
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

        // 6b. Batch Render Artistic Constellation Overlays (Behind constellation lines and stars)
        if (showConstellations && showConstellationArt && context != null) {
            for (c in 0 until constCount) {
                val p = constellationProgress[c]
                if (p <= 0.001f) continue

                catalog.constellations(reusableConstellation, c)
                val artName = reusableConstellation.artResourceName()
                if (artName.isNullOrEmpty()) continue

                val bitmap = getOrLoadArtwork(context, artName) ?: continue

                val artCenterRa = reusableConstellation.artCenterRa().toDouble()
                val artCenterDec = reusableConstellation.artCenterDec().toDouble()
                val artWidthDeg = reusableConstellation.artWidthDeg()
                val artRotDeg = reusableConstellation.artRotationDeg()

                // Project Center
                EquatorialCoordinates.computeWorldVector(artCenterRa, artCenterDec, lstDegrees, latitudeDegrees, tempArtCenterWorldVec, 0)
                val acVx = tempArtCenterWorldVec[0]
                val acVy = tempArtCenterWorldVec[1]
                val acVz = tempArtCenterWorldVec[2]

                val acxDev = matrix[0] * acVx + matrix[4] * acVy + matrix[8] * acVz
                val acyDev = matrix[1] * acVx + matrix[5] * acVy + matrix[9] * acVz
                val aczDev = matrix[2] * acVx + matrix[6] * acVy + matrix[10] * acVz

                val acxs: Float
                val acys: Float
                when (displayRotation) {
                    Surface.ROTATION_0 -> { acxs = acxDev; acys = acyDev }
                    Surface.ROTATION_90 -> { acxs = acyDev; acys = -acxDev }
                    Surface.ROTATION_180 -> { acxs = -acxDev; acys = -acyDev }
                    Surface.ROTATION_270 -> { acxs = -acyDev; acys = acxDev }
                    else -> { acxs = acxDev; acys = acyDev }
                }
                val aczs = -aczDev
                if (aczs <= 0.001f) continue

                val acScreenX = halfW + focalLengthPixels * (acxs / aczs)
                val acScreenY = halfH - focalLengthPixels * (acys / aczs)

                // Project Top orientation vector for rotation alignment
                val rRa = Math.toRadians(artCenterRa)
                val rDec = Math.toRadians(artCenterDec)
                val rRot = Math.toRadians(artRotDeg.toDouble())
                val cosRot = kotlin.math.cos(rRot).toFloat()
                val sinRot = kotlin.math.sin(rRot).toFloat()

                val uEastX = -kotlin.math.sin(rRa).toFloat()
                val uEastY = kotlin.math.cos(rRa).toFloat()
                val uEastZ = 0f

                val uNorthX = (-kotlin.math.sin(rDec) * kotlin.math.cos(rRa)).toFloat()
                val uNorthY = (-kotlin.math.sin(rDec) * kotlin.math.sin(rRa)).toFloat()
                val uNorthZ = kotlin.math.cos(rDec).toFloat()

                val vTopDirX = cosRot * uNorthX + sinRot * uEastX
                val vTopDirY = cosRot * uNorthY + sinRot * uEastY
                val vTopDirZ = cosRot * uNorthZ + sinRot * uEastZ

                val eps = 0.01745329f
                val atVx = acVx + eps * vTopDirX
                val atVy = acVy + eps * vTopDirY
                val atVz = acVz + eps * vTopDirZ

                val atxDev = matrix[0] * atVx + matrix[4] * atVy + matrix[8] * atVz
                val atyDev = matrix[1] * atVx + matrix[5] * atVy + matrix[9] * atVz
                val atzDev = matrix[2] * atVx + matrix[6] * atVy + matrix[10] * atVz

                val atxs: Float
                val atys: Float
                when (displayRotation) {
                    Surface.ROTATION_0 -> { atxs = atxDev; atys = atyDev }
                    Surface.ROTATION_90 -> { atxs = atyDev; atys = -atxDev }
                    Surface.ROTATION_180 -> { atxs = -atxDev; atys = -atyDev }
                    Surface.ROTATION_270 -> { atxs = -atyDev; atys = atxDev }
                    else -> { atxs = atxDev; atys = atyDev }
                }
                val atzs = -atzDev
                if (atzs <= 0.001f) continue

                val atScreenX = halfW + focalLengthPixels * (atxs / atzs)
                val atScreenY = halfH - focalLengthPixels * (atys / atzs)

                val deltaX = atScreenX - acScreenX
                val deltaY = atScreenY - acScreenY
                val screenRotDeg = Math.toDegrees(kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat() + 90f

                val widthInPixels = Math.toRadians(artWidthDeg.toDouble()).toFloat() * focalLengthPixels
                val baseScale = widthInPixels / bitmap.width.toFloat()
                val finalScale = baseScale * debugScaleMultiplier

                artMatrix.reset()
                artMatrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                artMatrix.postScale(finalScale, finalScale)
                artMatrix.postRotate(screenRotDeg + debugRotationOffset)
                artMatrix.postTranslate(acScreenX + debugOffsetX, acScreenY + debugOffsetY)

                artPaint.alpha = (128f * p).toInt().coerceIn(0, 255)
                nativeCanvas.drawBitmap(bitmap, artMatrix, artPaint)
            }
        }

        // 7. Batch Render Context-Aware Constellations ("Meet-in-the-Middle" & Single Line at 100%)
        if (showConstellations) {
            val density = drawScope.density
            val gapPx = 7f * density

            for (c in 0 until constCount) {
                val p = constellationProgress[c]
                if (p <= 0.001f) continue

                catalog.constellations(reusableConstellation, c)
                val linesLen = reusableConstellation.linesLength()
                var lineCoordsCount = 0
                val isSelected = (c == selectedConstellationIndex)

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
                                
                                val dx = x2 - x1
                                val dy = y2 - y1
                                val length = kotlin.math.sqrt(dx * dx + dy * dy)

                                if (length > gapPx * 2.1f) {
                                    val ux = dx / length
                                    val uy = dy / length
                                    val startX = x1 + ux * gapPx
                                    val startY = y1 + uy * gapPx
                                    val endX = x2 - ux * gapPx
                                    val endY = y2 - uy * gapPx

                                    if (p >= 0.99f) {
                                        // ANIMATION FINISHED: Draw exactly ONE continuous segment
                                        // Completely eliminates center overlapping dot artifact!
                                        if (lineCoordsCount + 4 <= constellationLinesBuffer.size) {
                                            constellationLinesBuffer[lineCoordsCount] = startX
                                            constellationLinesBuffer[lineCoordsCount + 1] = startY
                                            constellationLinesBuffer[lineCoordsCount + 2] = endX
                                            constellationLinesBuffer[lineCoordsCount + 3] = endY
                                            lineCoordsCount += 4
                                        }
                                    } else {
                                        // ANIMATING: Draw two segments meeting in the middle
                                        if (lineCoordsCount + 8 <= constellationLinesBuffer.size) {
                                            val adjDx = endX - startX
                                            val adjDy = endY - startY
                                            val pHalf = p * 0.5f

                                            constellationLinesBuffer[lineCoordsCount] = startX
                                            constellationLinesBuffer[lineCoordsCount + 1] = startY
                                            constellationLinesBuffer[lineCoordsCount + 2] = startX + (adjDx * pHalf)
                                            constellationLinesBuffer[lineCoordsCount + 3] = startY + (adjDy * pHalf)

                                            constellationLinesBuffer[lineCoordsCount + 4] = endX
                                            constellationLinesBuffer[lineCoordsCount + 5] = endY
                                            constellationLinesBuffer[lineCoordsCount + 6] = endX - (adjDx * pHalf)
                                            constellationLinesBuffer[lineCoordsCount + 7] = endY - (adjDy * pHalf)
                                            lineCoordsCount += 8
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val isAsterism = reusableConstellation.isAsterism()

                if (lineCoordsCount > 0) {
                    if (isSelected) {
                        val haloPaint = if (isAsterism) asterismHaloPaint else constellationHaloPaint
                        val corePaint = if (isAsterism) asterismCorePaint else constellationCorePaint
                        haloPaint.alpha = (42f * p).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, haloPaint)
                        corePaint.alpha = (240f * p).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, corePaint)
                    } else {
                        val linePaint = if (isAsterism) asterismPaint else constellationPaint
                        val baseAlpha = if (isAsterism) 32f else 48f
                        linePaint.alpha = (baseAlpha * p).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, linePaint)
                    }
                }

                // Render Animated Constellation / Asterism Name Label with vertical slide
                if (constellationCentroidInFront[c] && p > 0.05f) {
                    val cx = constellationCentroidScreenX[c]
                    val cy = constellationCentroidScreenY[c]
                    val textY = cy - (40f * (1f - p))
                    val label = reusableConstellation.localizedName() ?: reusableConstellation.name() ?: ""
                    if (label.isNotEmpty() && cx in -margin..(w + margin) && textY in -margin..(h + margin)) {
                        if (isSelected) {
                            val highlightLabelPaint = if (isAsterism) asterismHighlightLabelPaint else constellationHighlightLabelPaint
                            highlightLabelPaint.alpha = (255f * p).toInt().coerceIn(0, 255)
                            nativeCanvas.drawText(label, cx, textY, highlightLabelPaint)
                        } else {
                            val labelPaint = if (isAsterism) asterismLabelPaint else constellationLabelPaint
                            val baseLabelAlpha = if (isAsterism) 160f else 200f
                            labelPaint.alpha = (baseLabelAlpha * p).toInt().coerceIn(0, 255)
                            nativeCanvas.drawText(label, cx, textY, labelPaint)
                        }
                    }
                }
            }
        }

        // 8. Hybrid Optical Star Rendering:
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
        val isInFront = zs > 0.001f
        val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
        val focalLengthPixels = (w * 0.5f) / tan(halfFovHRad)
        val screenX = if (isInFront) w * 0.5f + focalLengthPixels * (xs / zs) else Float.NaN
        val screenY = if (isInFront) h * 0.5f - focalLengthPixels * (ys / zs) else Float.NaN

        val isVisible = isInFront && (screenX in 0f..w) && (screenY in 0f..h)

        if (!isVisible) {
            drawOffscreenIndicator(
                drawScope = drawScope,
                xs = xs,
                ys = ys,
                zs = zs,
                screenX = screenX,
                screenY = screenY,
                w = w,
                h = h,
                density = density,
            )
            return
        }

        // Only draw broken crosshair if a Star is selected (not a Constellation)
        if (selectedStarIndex >= 0) {
            // Scaled to 75% of original radii (r1: 13dp -> 9.75dp, r2: 24dp -> 18dp):
            val r1 = 9.75f * density
            val r2 = 18.0f * density

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

            // Bottom arm
            reticleLinesBuffer[8] = screenX
            reticleLinesBuffer[9] = screenY + r1
            reticleLinesBuffer[10] = screenX
            reticleLinesBuffer[11] = screenY + r2

            val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas
            nativeCanvas.drawLines(reticleLinesBuffer, 0, 12, reticlePaint)

            // Position star name text centered in the upper opening (approx. 1/3 of the removed top arm):
            // Removed top arm was at r1..r2; 1/3 position is r1 + (r2 - r1)/3 = 9.75dp + 2.75dp = 12.5dp
            val textY = screenY - (12.5f * density)
            nativeCanvas.drawText(selectedBody.name, screenX, textY, reticleTextPaint)
        }
    }

    /**
     * Renders a zero-allocation directional chevron arrow at the edge of the screen pointing
     * towards the selected celestial body when it is outside the camera viewport or behind the observer.
     *
     * 100% Zero heap allocations per frame.
     */
    fun drawOffscreenIndicator(
        drawScope: DrawScope,
        xs: Float,
        ys: Float,
        zs: Float,
        screenX: Float,
        screenY: Float,
        w: Float,
        h: Float,
        density: Float = 1.0f,
    ) {
        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val edgePadding = 22f
        val halfW = centerX - edgePadding
        val halfH = centerY - edgePadding

        if (halfW <= 0f || halfH <= 0f) return

        var dx = if (zs > 0.001f) (screenX - centerX) else xs
        var dy = if (zs > 0.001f) (screenY - centerY) else -ys

        if (kotlin.math.abs(dx) < 0.0001f && kotlin.math.abs(dy) < 0.0001f) {
            dy = 1f
        }

        val angleRad = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val tanAngle = kotlin.math.tan(angleRad.toDouble()).toFloat()

        val intersectX: Float
        val intersectY: Float

        if (kotlin.math.abs(halfW * tanAngle) < halfH) {
            // Intersects Left or Right edge
            intersectX = if (dx > 0) halfW else -halfW
            intersectY = intersectX * tanAngle
        } else {
            // Intersects Top or Bottom edge
            intersectY = if (dy > 0) halfH else -halfH
            intersectX = intersectY / tanAngle
        }

        val arrowX = centerX + intersectX
        val arrowY = centerY + intersectY
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas
        nativeCanvas.save()
        nativeCanvas.translate(arrowX, arrowY)
        nativeCanvas.rotate(angleDeg)
        nativeCanvas.drawPath(arrowPath, arrowPaint)
        nativeCanvas.restore()
    }
}

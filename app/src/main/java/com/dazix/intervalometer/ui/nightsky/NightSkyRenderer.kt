package com.dazix.intervalometer.ui.nightsky

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
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
import com.dazix.intervalometer.ar.paletteForTheme
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
    private val amberVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(255, 179, 0), PorterDuff.Mode.SRC_IN)
    private val greenVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(0, 230, 118), PorterDuff.Mode.SRC_IN)

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
    private val amberVisionArtFilter = PorterDuffColorFilter(AndroidColor.rgb(255, 179, 0), PorterDuff.Mode.MULTIPLY)
    private val greenVisionArtFilter = PorterDuffColorFilter(AndroidColor.rgb(0, 230, 118), PorterDuff.Mode.MULTIPLY)
    val artSrcPoints = FloatArray(8) // [x0, y0, x1, y1, x2, y2, x3, y3] (TL, TR, BR, BL)
    val artDstPoints = FloatArray(8) // [s0X, s0Y, s1X, s1Y, s2X, s2Y, s3X, s3Y] (TL, TR, BR, BL)
    val artCornerProjected = FloatArray(12) // [s0X, s0Y, z0, s1X, s1Y, z1, s2X, s2Y, z2, s3X, s3Y, z3]

    // Pre-allocated debug focus region visualization paints & colors
    val debugFocusCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
        color = AndroidColor.argb(190, 0, 230, 118)
    }

    val debugFocusFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.argb(20, 0, 230, 118)
    }

    val debugFocusLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
        color = AndroidColor.argb(230, 0, 230, 118)
    }

    val debugCentroidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var debugCentroidInFocusColor = AndroidColor.argb(220, 0, 230, 118)
        private set
    var debugCentroidOutOfFocusColor = AndroidColor.argb(120, 255, 82, 82)
        private set

    /**
     * Projects a 3D World (ENU) vector directly into screen pixel coordinates and camera Z.
     * Writes results into [outBuffer] starting at [offset]:
     * - `outBuffer[offset + 0]` = screenX in pixels
     * - `outBuffer[offset + 1]` = screenY in pixels
     * - `outBuffer[offset + 2]` = cameraZs (> 0 in front of camera, <= 0 behind)
     *
     * Zero heap allocations.
     * @return True if point is in front of the camera plane (cameraZs > 0.001f).
     */
    fun projectWorldToScreen(
        wx: Float,
        wy: Float,
        wz: Float,
        matrix: FloatArray,
        displayRotation: Int,
        halfW: Float,
        halfH: Float,
        focalLengthPixels: Float,
        outBuffer: FloatArray,
        offset: Int = 0,
    ): Boolean {
        val xDev = matrix[0] * wx + matrix[4] * wy + matrix[8] * wz
        val yDev = matrix[1] * wx + matrix[5] * wy + matrix[9] * wz
        val zDev = matrix[2] * wx + matrix[6] * wy + matrix[10] * wz

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

        if (isInFront) {
            outBuffer[offset] = halfW + focalLengthPixels * (xs / zs)
            outBuffer[offset + 1] = halfH - focalLengthPixels * (ys / zs)
        } else {
            outBuffer[offset] = halfW - focalLengthPixels * (xs / -zs)
            outBuffer[offset + 1] = halfH + focalLengthPixels * (ys / -zs)
        }
        outBuffer[offset + 2] = zs
        return isInFront
    }

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
        setupStarPaints(isNightVision = false)
    }

    private fun setupStarPaints(
        theme: com.dazix.intervalometer.domain.nightsky.NightVisionTheme = com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        if (isNightVision) {
            spritePaint.colorFilter = when (theme) {
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.RED -> redVisionFilter
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER -> amberVisionFilter
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.GREEN -> greenVisionFilter
            }
            val palette = paletteForTheme(theme)
            val coreColor = AndroidColor.rgb(
                (palette.secondary.red * 255).toInt(),
                (palette.secondary.green * 255).toInt(),
                (palette.secondary.blue * 255).toInt(),
            )
            for (b in 0 until numBuckets) {
                corePaints[b].color = coreColor
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

    fun applyTheme(state: com.dazix.intervalometer.domain.nightsky.NightVisionState) {
        applyTheme(theme = state.theme, isNightVision = state.isActive)
    }

    fun applyTheme(
        theme: com.dazix.intervalometer.domain.nightsky.NightVisionTheme = com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        skyDomeRenderer.applyTheme(theme = theme, isNightVision = isNightVision)
        setupStarPaints(theme = theme, isNightVision = isNightVision)

        if (isNightVision) {
            val palette = paletteForTheme(theme)
            val pr = (palette.primary.red * 255).toInt()
            val pg = (palette.primary.green * 255).toInt()
            val pb = (palette.primary.blue * 255).toInt()
            val sr = (palette.secondary.red * 255).toInt()
            val sg = (palette.secondary.green * 255).toInt()
            val sb = (palette.secondary.blue * 255).toInt()

            val primaryInt = AndroidColor.rgb(pr, pg, pb)
            val secondaryInt = AndroidColor.rgb(sr, sg, sb)

            artPaint.colorFilter = when (theme) {
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.RED -> redVisionArtFilter
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER -> amberVisionArtFilter
                com.dazix.intervalometer.domain.nightsky.NightVisionTheme.GREEN -> greenVisionArtFilter
            }

            constellationPaint.color = AndroidColor.argb(160, pr, pg, pb)
            constellationHaloPaint.color = AndroidColor.argb(50, pr, pg, pb)
            constellationCorePaint.color = AndroidColor.argb(255, sr, sg, sb)
            constellationLabelPaint.color = AndroidColor.argb(210, pr, pg, pb)
            constellationHighlightLabelPaint.color = primaryInt

            asterismPaint.color = AndroidColor.argb(120, pr, pg, pb)
            asterismHaloPaint.color = AndroidColor.argb(40, pr, pg, pb)
            asterismCorePaint.color = AndroidColor.argb(230, sr, sg, sb)
            asterismLabelPaint.color = AndroidColor.argb(170, sr, sg, sb)
            asterismHighlightLabelPaint.color = secondaryInt

            reticlePaint.color = primaryInt
            reticleTextPaint.color = primaryInt
            arrowPaint.color = primaryInt

            debugFocusCirclePaint.color = AndroidColor.argb(190, pr, pg, pb)
            debugFocusFillPaint.color = AndroidColor.argb(25, pr, pg, pb)
            debugFocusLabelPaint.color = AndroidColor.argb(230, pr, pg, pb)
            debugCentroidInFocusColor = AndroidColor.argb(230, pr, pg, pb)
            debugCentroidOutOfFocusColor = AndroidColor.argb(100, sr, sg, sb)
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

            debugFocusCirclePaint.color = AndroidColor.argb(190, 0, 230, 118)
            debugFocusFillPaint.color = AndroidColor.argb(20, 0, 230, 118)
            debugFocusLabelPaint.color = AndroidColor.argb(230, 0, 230, 118)
            debugCentroidInFocusColor = AndroidColor.argb(220, 0, 230, 118)
            debugCentroidOutOfFocusColor = AndroidColor.argb(120, 255, 82, 82)
        }
    }

    fun applyTheme(redVisionMode: Boolean) {
        applyTheme(theme = com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER, isNightVision = redVisionMode)
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
        boostStarAndLines: Boolean = false,
    ) {
        val opticScale = StarOptics.computeOpticScale(zoomFactor)
        val densityScale = density.coerceAtLeast(0.5f)
        val starArtScale = if (boostStarAndLines) 1.5f else 1.0f
        val lineArtScale = if (boostStarAndLines) 1.25f else 1.0f

        // Compute scaled sprite diameters for bright buckets 0, 1, 2
        for (b in 0..2) {
            spriteDiameterPx[b] = baseSpriteDiameterDp[b] * densityScale * opticScale * starArtScale
        }

        // Compute pinpoint core stroke widths for all 6 buckets
        for (b in 0 until numBuckets) {
            corePaints[b].strokeWidth = baseCoreDp[b] * densityScale * opticScale * starArtScale
        }
        constellationPaint.strokeWidth = 1.45f * densityScale * lineArtScale
        constellationHaloPaint.strokeWidth = 5.4f * densityScale * lineArtScale
        constellationCorePaint.strokeWidth = 2.16f * densityScale * lineArtScale
        constellationLabelPaint.textSize = 13f * densityScale
        constellationHighlightLabelPaint.textSize = 15f * densityScale

        asterismPaint.strokeWidth = 1.15f * densityScale * lineArtScale
        asterismHaloPaint.strokeWidth = 4.2f * densityScale * lineArtScale
        asterismCorePaint.strokeWidth = 1.7f * densityScale * lineArtScale
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

    private val shaderTintBuffer = FloatArray(3)

    /**
     * Renders background layers:
     * - Layer 0: Opaque Sky Background (Planetarium Mode #050810 / #000000)
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
        theme: com.dazix.intervalometer.domain.nightsky.NightVisionTheme = com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER,
    ) {
        val palette = paletteForTheme(theme)
        shaderTintBuffer[0] = palette.shaderTintR
        shaderTintBuffer[1] = palette.shaderTintG
        shaderTintBuffer[2] = palette.shaderTintB

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
            runtimeShader.setFloatUniform("nightVisionTint", shaderTintBuffer)

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
            atmosphereShader.setFloatUniform("nightVisionTint", shaderTintBuffer)

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
        theme: com.dazix.intervalometer.domain.nightsky.NightVisionTheme = com.dazix.intervalometer.domain.nightsky.NightVisionTheme.AMBER,
    ) {
        if (isLandscapeVisible && hasLandscapeTexture && landscapeShader != null && landscapeBrush != null && matrix.size >= 16) {
            val halfFovHRad = (fovHorizontalDegrees * 0.5f * (Math.PI / 180.0)).toFloat()
            val fovFactor = 1.0f / tan(halfFovHRad)

            CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                sensorMatrix16 = matrix,
                displayRotation = displayRotation,
                outMatrix9 = landscapeMatrixBuffer,
            )

            val palette = paletteForTheme(theme)
            shaderTintBuffer[0] = palette.shaderTintR
            shaderTintBuffer[1] = palette.shaderTintG
            shaderTintBuffer[2] = palette.shaderTintB

            landscapeShader.setFloatUniform("resolution", w, h)
            landscapeShader.setFloatUniform("textureSize", landscapeTextureWidth, landscapeTextureHeight)
            landscapeShader.setFloatUniform("viewMatrix", landscapeMatrixBuffer)
            landscapeShader.setFloatUniform("fovFactor", fovFactor)
            landscapeShader.setFloatUniform("isRedVision", if (isRedVision) 1.0f else 0.0f)
            landscapeShader.setFloatUniform("nightVisionTint", shaderTintBuffer)

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

        // Priority 1: Constellations (Only IAU constellations 0..87 render text labels)
        val constCount = minOf(catalog.constellationsLength(), 88)
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
        isDebugFocusRegionVisible: Boolean = false,
        debugFocusRadiusMultiplier: Float = 0.48f,
        debugFocusDisplayThreshold: Float = 0.0f,
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
        val focusRadius = w * debugFocusRadiusMultiplier
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
                val isSelectedConstellation = (c == selectedConstellationIndex)
                val inFocus = showConstellations && (((cdx * cdx + cdy * cdy) <= focusRadiusSq) || isSelectedConstellation)
                if (inFocus) {
                    constellationProgress[c] = minOf(1.0f, constellationProgress[c] + dt * 2.0f)
                } else {
                    constellationProgress[c] = maxOf(0.0f, constellationProgress[c] - dt * 2.0f)
                }
            } else {
                constellationCentroidInFront[c] = false
                if (c == selectedConstellationIndex) {
                    constellationProgress[c] = 1.0f
                } else {
                    constellationProgress[c] = maxOf(0.0f, constellationProgress[c] - dt * 2.0f)
                }
            }
        }

        // 5. Fast Dynamic Override Table: force render stars of visible constellations
        forceRenderStar.fill(false)
        if (showConstellations) {
            for (c in 0 until constCount) {
                val progress = constellationProgress[c]
                if (progress > 0.001f || c == selectedConstellationIndex) {
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

            val isSelectedStar = (i == selectedStarIndex)
            val isForced = forceRenderStar[i] || isSelectedStar
            val isBrightEnough = rawMag <= faintestVisibleMag

            // Dynamic telescope ocular reveal: cull stars fainter than current zoom limit unless forced by visible constellation or selection
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
        val minDisplayThreshold = maxOf(0.001f, debugFocusDisplayThreshold)
        if (showConstellations && showConstellationArt && context != null) {
            for (c in 0 until constCount) {
                val p = constellationProgress[c]
                if (p <= minDisplayThreshold) continue
                val effectiveP = if (debugFocusDisplayThreshold > 0f) {
                    ((p - debugFocusDisplayThreshold) / (1f - debugFocusDisplayThreshold)).coerceIn(0f, 1f)
                } else {
                    p
                }

                catalog.constellations(reusableConstellation, c)
                if (reusableConstellation.artCornerRaLength() != 4) continue

                val artName = reusableConstellation.artResourceName()
                if (artName.isNullOrEmpty()) continue

                val bitmap = getOrLoadArtwork(context, artName) ?: continue

                // 1. Project the 4 Image Corners (TL, TR, BR, BL) from catalog celestial coordinates to Screen Space
                var allInFront = true
                for (i in 0 until 4) {
                    val aRa = reusableConstellation.artCornerRa(i).toDouble()
                    val aDec = reusableConstellation.artCornerDec(i).toDouble()
                    EquatorialCoordinates.computeWorldVector(aRa, aDec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
                    val inFront = projectWorldToScreen(
                        tempWorldVec[0], tempWorldVec[1], tempWorldVec[2],
                        matrix, displayRotation, halfW, halfH, focalLengthPixels,
                        artCornerProjected, i * 3
                    )
                    if (!inFront) {
                        allInFront = false
                    }
                }

                val z0 = artCornerProjected[2]
                val z1 = artCornerProjected[5]
                val z2 = artCornerProjected[8]
                val z3 = artCornerProjected[11]

                // 2. Culling: Only draw if all 4 corners are in front of the camera plane (Z > 0)
                if (allInFront && z0 > 0.001f && z1 > 0.001f && z2 > 0.001f && z3 > 0.001f) {
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()

                    // 3. Setup Source Points (Flat 2D Bitmap Corners: TL, TR, BR, BL)
                    artSrcPoints[0] = 0f;  artSrcPoints[1] = 0f  // Top-Left
                    artSrcPoints[2] = bw;  artSrcPoints[3] = 0f  // Top-Right
                    artSrcPoints[4] = bw;  artSrcPoints[5] = bh  // Bottom-Right
                    artSrcPoints[6] = 0f;  artSrcPoints[7] = bh  // Bottom-Left

                    // 4. Setup Destination Points (Projected 3D Perspective Corners + User Debug Offset)
                    artDstPoints[0] = artCornerProjected[0] + debugOffsetX  // TL
                    artDstPoints[1] = artCornerProjected[1] + debugOffsetY
                    artDstPoints[2] = artCornerProjected[3] + debugOffsetX  // TR
                    artDstPoints[3] = artCornerProjected[4] + debugOffsetY
                    artDstPoints[4] = artCornerProjected[6] + debugOffsetX  // BR
                    artDstPoints[5] = artCornerProjected[7] + debugOffsetY
                    artDstPoints[6] = artCornerProjected[9] + debugOffsetX  // BL
                    artDstPoints[7] = artCornerProjected[10] + debugOffsetY

                    // 5. Assemble Homography Matrix
                    artMatrix.reset()
                    // setPolyToPoly with 4 points computes the complete 3D projective perspective homography
                    artMatrix.setPolyToPoly(artSrcPoints, 0, artDstPoints, 0, 4)

                    // 6. Apply Debug Scale & Rotation (Optional, centered on the projected quad centroid)
                    if (debugScaleMultiplier != 1.0f || debugRotationOffset != 0f) {
                        val cx = (artDstPoints[0] + artDstPoints[2] + artDstPoints[4] + artDstPoints[6]) * 0.25f
                        val cy = (artDstPoints[1] + artDstPoints[3] + artDstPoints[5] + artDstPoints[7]) * 0.25f
                        artMatrix.postScale(debugScaleMultiplier, debugScaleMultiplier, cx, cy)
                        artMatrix.postRotate(debugRotationOffset, cx, cy)
                    }

                    // 7. Paint Setup & Draw
                    artPaint.alpha = (150f * effectiveP).toInt().coerceIn(0, 255)
                    nativeCanvas.drawBitmap(bitmap, artMatrix, artPaint)
                }
            }
        }

        // 7. Batch Render Context-Aware Constellations ("Meet-in-the-Middle" & Single Line at 100%)
        if (showConstellations) {
            val density = drawScope.density
            val gapPx = 7f * density
            val artLineBoost = if (showConstellationArt) 1.25f else 1.0f

            for (c in 0 until constCount) {
                val p = constellationProgress[c]
                if (p <= minDisplayThreshold) continue
                val effectiveP = if (debugFocusDisplayThreshold > 0f) {
                    ((p - debugFocusDisplayThreshold) / (1f - debugFocusDisplayThreshold)).coerceIn(0f, 1f)
                } else {
                    p
                }

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

                                    if (effectiveP >= 0.99f) {
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
                                            val pHalf = effectiveP * 0.5f

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
                        haloPaint.alpha = (42f * artLineBoost * effectiveP).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, haloPaint)
                        corePaint.alpha = (240f * artLineBoost * effectiveP).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, corePaint)
                    } else {
                        val linePaint = if (isAsterism) asterismPaint else constellationPaint
                        val baseAlpha = if (isAsterism) 32f else 48f
                        linePaint.alpha = (baseAlpha * artLineBoost * effectiveP).toInt().coerceIn(0, 255)
                        nativeCanvas.drawLines(constellationLinesBuffer, 0, lineCoordsCount, linePaint)
                    }
                }

                // Render Animated Constellation Name Label with vertical slide (Skip asterisms)
                val labelMinThreshold = maxOf(0.05f, debugFocusDisplayThreshold)
                if (!isAsterism && constellationCentroidInFront[c] && p > labelMinThreshold) {
                    val cx = constellationCentroidScreenX[c]
                    val cy = constellationCentroidScreenY[c]
                    val textY = cy - (40f * (1f - effectiveP))
                    val label = reusableConstellation.localizedName() ?: reusableConstellation.name() ?: ""
                    if (label.isNotEmpty() && cx in -margin..(w + margin) && textY in -margin..(h + margin)) {
                        if (isSelected) {
                            val highlightLabelPaint = constellationHighlightLabelPaint
                            highlightLabelPaint.alpha = (255f * effectiveP).toInt().coerceIn(0, 255)
                            nativeCanvas.drawText(label, cx, textY, highlightLabelPaint)
                        } else {
                            val labelPaint = constellationLabelPaint
                            val baseLabelAlpha = 200f
                            labelPaint.alpha = (baseLabelAlpha * effectiveP).toInt().coerceIn(0, 255)
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

        // 9. Debug Focus Area Overlay: Circle & Centroid Indicators
        if (isDebugFocusRegionVisible) {
            nativeCanvas.drawCircle(halfW, halfH, focusRadius, debugFocusFillPaint)
            nativeCanvas.drawCircle(halfW, halfH, focusRadius, debugFocusCirclePaint)
            val pct = (debugFocusRadiusMultiplier * 100f).toInt()
            val threshPct = (debugFocusDisplayThreshold * 100f).toInt()
            nativeCanvas.drawText("FOCUS AREA (r = $pct% W, thresh = $threshPct%)", halfW, halfH - focusRadius - 16f, debugFocusLabelPaint)

            // Draw centroid indicators for constellations in front of the camera
            for (c in 0 until constCount) {
                if (!constellationCentroidInFront[c]) continue
                val cxScreen = constellationCentroidScreenX[c]
                val cyScreen = constellationCentroidScreenY[c]
                if (cxScreen < -margin || cxScreen > w + margin || cyScreen < -margin || cyScreen > h + margin) continue

                val cdx = cxScreen - halfW
                val cdy = cyScreen - halfH
                val isSelectedConstellation = (c == selectedConstellationIndex)
                val inFocus = ((cdx * cdx + cdy * cdy) <= focusRadiusSq) || isSelectedConstellation
                debugCentroidPaint.color = if (inFocus) debugCentroidInFocusColor else debugCentroidOutOfFocusColor
                nativeCanvas.drawCircle(cxScreen, cyScreen, 6f, debugCentroidPaint)
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

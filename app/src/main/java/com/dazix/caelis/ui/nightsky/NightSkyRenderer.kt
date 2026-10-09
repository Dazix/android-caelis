package com.dazix.caelis.ui.nightsky

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
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.view.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.dazix.caelis.ar.SkyDomeRenderer
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.DsoType
import com.dazix.caelis.data.nightsky.MeteorShower
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.data.nightsky.StarMetadata
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import com.dazix.caelis.domain.nightsky.EquatorialCoordinates
import com.dazix.caelis.domain.nightsky.StarFrustumCulling
import com.dazix.caelis.domain.nightsky.StarHitTester
import com.dazix.caelis.domain.nightsky.StarOptics
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
    /** Labels and markers of the GPU grid; the GPU path themes and sizes these, not [skyDomeRenderer]. */
    val gridMarkers = com.dazix.caelis.ar.scene.SkyGridMarkers()

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

    /**
     * The real terrain skyline, packed into a 1440x1 strip the shader reads per pixel.
     *
     * Re-created only when the profile itself changes — once per location, not per frame — and kept
     * here so the render loop stays allocation-free. Null until a profile exists, which is also how
     * the shader is told to fall back to the stylised panorama.
     */
    private var horizonProfileBitmap: Bitmap? = null
    private var hasHorizonProfile = false
    private var horizonProfileStamp: Long = Long.MIN_VALUE
    private val terrainColorBuffer = FloatArray(4)

    val numBuckets = StarOptics.NUM_BUCKETS
    val bucketPoints = Array(numBuckets) { FloatArray(MAX_STARS * 2) }
    val bucketCounts = IntArray(numBuckets)

    // Precalculated static ENU World Unit Vectors (East, North, Up) for all catalog stars
    // Eliminates all trigonometry (sin/cos/computeWorldVector) from the 60 FPS hot render loop
    val starWorldX = FloatArray(MAX_STARS)
    val starWorldY = FloatArray(MAX_STARS)
    val starWorldZ = FloatArray(MAX_STARS)
    val starVectorComputed = BooleanArray(MAX_STARS)
    @Volatile
    var isStarCacheReady = false
        private set
    @Volatile
    var lastVisibleStarCount: Int = 0
    private var cachedLstDegrees: Double = Double.NaN
    private var cachedLatitudeDegrees: Double = Double.NaN
    private var cachedStarCount: Int = 0

    // Static Cartesian Celestial coordinates in J2000.0 (V_cel = [cos(Dec)*cos(RA), cos(Dec)*sin(RA), sin(Dec)])
    // Eliminates all vector re-calculation on LST time shifts and provides continuous 60/120 FPS rotation.
    val starCelX = FloatArray(MAX_STARS)
    val starCelY = FloatArray(MAX_STARS)
    val starCelZ = FloatArray(MAX_STARS)
    val starCelComputed = BooleanArray(MAX_STARS)
    @Volatile
    var isStarCelCacheReady = false
        private set

    val dsoCelX = FloatArray(MAX_DSOS)
    val dsoCelY = FloatArray(MAX_DSOS)
    val dsoCelZ = FloatArray(MAX_DSOS)

    val meteorCelX = FloatArray(MAX_METEOR_SHOWERS)
    val meteorCelY = FloatArray(MAX_METEOR_SHOWERS)
    val meteorCelZ = FloatArray(MAX_METEOR_SHOWERS)

    private val maxConstellations = 256
    val constellationCentroidCelX = FloatArray(maxConstellations)
    val constellationCentroidCelY = FloatArray(maxConstellations)
    val constellationCentroidCelZ = FloatArray(maxConstellations)

    // Pre-allocated 3x3 matrix mapping Celestial J2000.0 directly into Device coordinates
    private val cel2devMatrix = FloatArray(9)

    // Cached screen projection coordinates for constellation line resolution
    private val starScreenX = FloatArray(MAX_STARS)
    private val starScreenY = FloatArray(MAX_STARS)
    private val starInFront = BooleanArray(MAX_STARS)

    // Fast O(1) zero-allocation lookup table to force render constituent stars of visible constellations
    val forceRenderStar = BooleanArray(MAX_STARS)

    val constellationProgress = FloatArray(maxConstellations) { 0f }
    val constellationCentroidScreenX = FloatArray(maxConstellations)
    val constellationCentroidScreenY = FloatArray(maxConstellations)
    val constellationCentroidInFront = BooleanArray(maxConstellations)
    val constellationCentroidWorldX = FloatArray(maxConstellations)
    val constellationCentroidWorldY = FloatArray(maxConstellations)
    val constellationCentroidWorldZ = FloatArray(maxConstellations)
    val constellationIsFocused = BooleanArray(maxConstellations)
    private val tempCentroidWorldVec = FloatArray(3)
    var currentHorizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null
    private var lastFrameTimeMs: Long = 0L

    var selectedConstellationIndex: Int = -1
    var selectedStarIndex: Int = -1

    private val maxConstellationSegments = 2048
    // 2 line segments per pair (A to midpoint, B to midpoint) = 8 floats per segment
    private val constellationLinesBuffer = FloatArray(maxConstellationSegments * 8)

    private val tempWorldVec = FloatArray(3)
    private val reusableConstellation = Constellation()
    private val reusableMeta = StarMetadata()
    private val topStarIndices = IntArray(5)
    private val topStarScores = FloatArray(5)
    private val topPlanetIndices = IntArray(5)
    private val topPlanetScores = FloatArray(5)
    private val topDsoIndices = IntArray(5)
    private val topDsoScores = FloatArray(5)

    // Pre-allocated buffers for Deep Sky Objects (Messier Catalog)
    val dsoWorldX = FloatArray(MAX_DSOS)
    val dsoWorldY = FloatArray(MAX_DSOS)
    val dsoWorldZ = FloatArray(MAX_DSOS)
    val dsoScreenX = FloatArray(MAX_DSOS)
    val dsoScreenY = FloatArray(MAX_DSOS)
    val dsoRadiusPx = FloatArray(MAX_DSOS)
    val dsoInFront = BooleanArray(MAX_DSOS)
    val dsoMagnitudes = FloatArray(MAX_DSOS)
    val dsoPolySrc = FloatArray(8)
    val dsoPolyDst = FloatArray(8)
    val dsoCornerProjected = FloatArray(12)
    val dsoMatrix = Matrix()

    var selectedDsoIndex: Int = -1

    private val reusableDso = DeepSkyObject()
    private val dsoRectF = RectF()

    val dsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    val dsoDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    val dsoHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }
    val dsoCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val dsoLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val dsoHighlightLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(10f, 0f, 0f, AndroidColor.BLACK)
    }

    // Pre-allocated buffers for Meteor Shower Radiants
    val meteorWorldX = FloatArray(MAX_METEOR_SHOWERS)
    val meteorWorldY = FloatArray(MAX_METEOR_SHOWERS)
    val meteorWorldZ = FloatArray(MAX_METEOR_SHOWERS)
    val meteorScreenX = FloatArray(MAX_METEOR_SHOWERS)
    val meteorScreenY = FloatArray(MAX_METEOR_SHOWERS)
    val meteorInFront = BooleanArray(MAX_METEOR_SHOWERS)

    var selectedMeteorIndex: Int = -1

    private val reusableMeteor = MeteorShower()
    private val topMeteorIndices = IntArray(5)
    private val topMeteorScores = FloatArray(5)

    // Pre-allocate the vector glyph path (a radiant starburst)
    val radiantPath = Path().apply {
        // Center dot
        addCircle(0f, 0f, 4f, Path.Direction.CW)
        // Diverging lines simulating meteors flying outward
        moveTo(8f, 8f); lineTo(24f, 24f)
        moveTo(-8f, 8f); lineTo(-24f, 24f)
        moveTo(8f, -8f); lineTo(24f, -24f)
        moveTo(-8f, -8f); lineTo(-24f, -24f)
    }
    val radiantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF00E676.toInt() // Default: Green/Cyan for visibility
    }
    val radiantLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF00E676.toInt()
    }
    val radiantHighlightLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = AndroidColor.rgb(255, 215, 64)
        setShadowLayer(10f, 0f, 0f, AndroidColor.BLACK)
    }

    companion object {
        /** Fill below the real skyline. Near-black, never pure black — see [drawLandscape]. */
        private const val TERRAIN_FILL_R = 0.024f
        private const val TERRAIN_FILL_G = 0.031f
        private const val TERRAIN_FILL_B = 0.043f

        const val MAX_STARS = 100_000
        const val MAX_DSOS = 128
        const val MAX_METEOR_SHOWERS = 32

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
    private val baseCoreDp = floatArrayOf(3.2f, 2.5f, 2.0f, 1.6f, 1.2f, 0.75f)

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
    val artworkCache = LruCache<String, Bitmap>(64)
    private val resIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
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

    // Pre-allocated buffers for Dynamic Solar System Ephemeris Rendering
    val maxPlanets = 16
    val planetScreenX = FloatArray(maxPlanets)
    val planetScreenY = FloatArray(maxPlanets)
    val planetRadiusPx = FloatArray(maxPlanets)
    val planetInFront = BooleanArray(maxPlanets)
    val planetIds = Array(maxPlanets) { "" }
    val planetWorldX = FloatArray(maxPlanets)
    val planetWorldY = FloatArray(maxPlanets)
    val planetWorldZ = FloatArray(maxPlanets)
    var activePlanetsCount = 0

    var selectedPlanetId: String? = null

    val planetWorldVec = FloatArray(3)
    private val tempNorthWorldVec = FloatArray(3)
    private val tempNorthScreenVec = FloatArray(3)
    val planetMatrix = Matrix()
    val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    val planetCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val planetHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val planetGlowMatrix = Matrix()
    val planetGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.FILL
    }

    private fun createUnitGlowShader(hexColor: String): RadialGradient {
        val color = AndroidColor.parseColor(hexColor)
        return RadialGradient(
            0f, 0f, 1f,
            intArrayOf(color, AndroidColor.TRANSPARENT),
            floatArrayOf(0.0f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    private val planetGlowShaders: Map<String, RadialGradient> = mapOf(
        "Sun" to createUnitGlowShader("#FFF5D7"),
        "Moon" to createUnitGlowShader("#E6E8EA"),
        "Mercury" to createUnitGlowShader("#B0AEAD"),
        "Venus" to createUnitGlowShader("#FFF8E7"),
        "Mars" to createUnitGlowShader("#FF6B36"),
        "Jupiter" to createUnitGlowShader("#E0C4A8"),
        "Saturn" to createUnitGlowShader("#F4D596"),
        "Uranus" to createUnitGlowShader("#A8E5F0"),
        "Neptune" to createUnitGlowShader("#4B70DD"),
    )
    private val defaultGlowShader: RadialGradient = createUnitGlowShader("#FFFFFF")

    /**
     * Retrieves the pre-allocated unit RadialGradient glow shader for a given celestial body.
     */
    fun getPlanetGlowShader(planetId: String): RadialGradient {
        return planetGlowShaders[planetId]
            ?: planetGlowShaders.entries.firstOrNull { it.key.equals(planetId, ignoreCase = true) }?.value
            ?: defaultGlowShader
    }

    /**
     * Maps a planet/body ID to its characteristic optical glow color.
     */
    fun getPlanetGlowColor(planetId: String): Int = when {
        planetId.equals("Sun", ignoreCase = true) -> AndroidColor.parseColor("#FFF5D7")
        planetId.equals("Moon", ignoreCase = true) -> AndroidColor.parseColor("#E6E8EA")
        planetId.equals("Mercury", ignoreCase = true) -> AndroidColor.parseColor("#B0AEAD")
        planetId.equals("Venus", ignoreCase = true) -> AndroidColor.parseColor("#FFF8E7")
        planetId.equals("Mars", ignoreCase = true) -> AndroidColor.parseColor("#FF6B36")
        planetId.equals("Jupiter", ignoreCase = true) -> AndroidColor.parseColor("#E0C4A8")
        planetId.equals("Saturn", ignoreCase = true) -> AndroidColor.parseColor("#F4D596")
        planetId.equals("Uranus", ignoreCase = true) -> AndroidColor.parseColor("#A8E5F0")
        planetId.equals("Neptune", ignoreCase = true) -> AndroidColor.parseColor("#4B70DD")
        else -> AndroidColor.WHITE
    }
    val planetLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
    }
    val planetHighlightLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(10f, 0f, 0f, AndroidColor.BLACK)
    }

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

    // Pre-allocated buffers and paints for Debug Tap Radius Indicator
    private var tapIndicatorX = 0f
    private var tapIndicatorY = 0f
    private var tapIndicatorRadiusPx = 0f
    private var tapIndicatorRadiusDp = 0
    private var tapIndicatorTimestamp = 0L

    val debugTapCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    val debugTapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val debugTapCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val debugTapLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(6f, 0f, 0f, AndroidColor.BLACK)
    }

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

    /**
     * Calculates the screen orientation angle (in degrees clockwise, where 0° points up towards -Y)
     * pointing towards the North Celestial Pole at the projected position ([pX], [pY]) for celestial
     * coordinates ([ra], [dec]).
     *
     * Zero heap allocations.
     */
    fun computeCelestialNorthAngle(
        pX: Float,
        pY: Float,
        ra: Double,
        dec: Double,
        lstDegrees: Double,
        latitudeDegrees: Double,
        matrix: FloatArray,
        displayRotation: Int,
        halfW: Float,
        halfH: Float,
        focalLengthPixels: Float,
    ): Float {
        val targetDec = if (dec < 89.0) dec + 0.1 else dec - 0.1
        EquatorialCoordinates.computeWorldVector(ra, targetDec, lstDegrees, latitudeDegrees, tempNorthWorldVec, 0)
        val inFront = projectWorldToScreen(
            tempNorthWorldVec[0], tempNorthWorldVec[1], tempNorthWorldVec[2],
            matrix, displayRotation, halfW, halfH, focalLengthPixels,
            tempNorthScreenVec, 0
        )
        if (!inFront) return 0f

        val dx = if (dec < 89.0) (tempNorthScreenVec[0] - pX) else (pX - tempNorthScreenVec[0])
        val dy = if (dec < 89.0) (tempNorthScreenVec[1] - pY) else (pY - tempNorthScreenVec[1])
        if (kotlin.math.abs(dx) < 1e-4f && kotlin.math.abs(dy) < 1e-4f) return 0f

        val angleRad = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
        return (Math.toDegrees(angleRad.toDouble()).toFloat() + 90f) % 360f
    }

    fun getOrLoadArtwork(context: Context?, resourceName: String): Bitmap? {
        if (context == null) return null
        var bitmap = artworkCache.get(resourceName)
        if (bitmap == null) {
            val resId = resIdCache.computeIfAbsent(resourceName) { name ->
                context.resources.getIdentifier(name, "drawable", context.packageName)
            }
            if (resId != 0) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
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
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        if (isNightVision) {
            spritePaint.colorFilter = when (theme) {
                com.dazix.caelis.domain.nightsky.NightVisionTheme.RED -> redVisionFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER -> amberVisionFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN -> greenVisionFilter
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

    fun applyTheme(state: com.dazix.caelis.domain.nightsky.NightVisionState) {
        applyTheme(theme = state.theme, isNightVision = state.isActive)
    }

    fun applyTheme(
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        skyDomeRenderer.applyTheme(theme = theme, isNightVision = isNightVision)
        gridMarkers.applyTheme(nightVision = isNightVision, theme = theme)
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
                com.dazix.caelis.domain.nightsky.NightVisionTheme.RED -> redVisionArtFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER -> amberVisionArtFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN -> greenVisionArtFilter
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

            dsoPaint.color = AndroidColor.argb(200, pr, pg, pb)
            dsoDashedPaint.color = AndroidColor.argb(200, pr, pg, pb)
            dsoHaloPaint.color = AndroidColor.argb(40, pr, pg, pb)
            dsoCorePaint.color = AndroidColor.argb(255, sr, sg, sb)
            dsoLabelPaint.color = AndroidColor.argb(210, pr, pg, pb)
            dsoHighlightLabelPaint.color = primaryInt

            reticlePaint.color = primaryInt
            reticleTextPaint.color = primaryInt
            arrowPaint.color = primaryInt

            planetPaint.colorFilter = when (theme) {
                com.dazix.caelis.domain.nightsky.NightVisionTheme.RED -> redVisionArtFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER -> amberVisionArtFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN -> greenVisionArtFilter
            }
            planetGlowPaint.colorFilter = planetPaint.colorFilter
            planetLabelPaint.color = primaryInt
            planetHighlightLabelPaint.color = primaryInt

            radiantPaint.color = primaryInt
            radiantPaint.colorFilter = when (theme) {
                com.dazix.caelis.domain.nightsky.NightVisionTheme.RED -> redVisionFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER -> amberVisionFilter
                com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN -> greenVisionFilter
            }
            radiantLabelPaint.color = primaryInt
            radiantHighlightLabelPaint.color = primaryInt

            debugFocusCirclePaint.color = AndroidColor.argb(190, pr, pg, pb)
            debugFocusFillPaint.color = AndroidColor.argb(25, pr, pg, pb)
            debugFocusLabelPaint.color = AndroidColor.argb(230, pr, pg, pb)
            debugCentroidInFocusColor = AndroidColor.argb(230, pr, pg, pb)
            debugCentroidOutOfFocusColor = AndroidColor.argb(100, sr, sg, sb)
        } else {
            artPaint.colorFilter = null
            planetPaint.colorFilter = null
            planetGlowPaint.colorFilter = null
            planetLabelPaint.color = AndroidColor.WHITE
            planetHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)

            radiantPaint.color = 0xFF00E676.toInt()
            radiantPaint.colorFilter = null
            radiantLabelPaint.color = 0xFF00E676.toInt()
            radiantHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)

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

            dsoPaint.color = AndroidColor.argb(220, 255, 183, 77)
            dsoDashedPaint.color = AndroidColor.argb(220, 255, 183, 77)
            dsoHaloPaint.color = AndroidColor.argb(45, 255, 183, 77)
            dsoCorePaint.color = AndroidColor.WHITE
            dsoLabelPaint.color = AndroidColor.argb(220, 255, 204, 128)
            dsoHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)

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
        applyTheme(theme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER, isNightVision = redVisionMode)
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

        planetLabelPaint.textSize = 13f * densityScale
        planetHighlightLabelPaint.textSize = 15f * densityScale

        radiantPaint.strokeWidth = 2.0f * densityScale
        radiantLabelPaint.textSize = 12f * densityScale
        radiantHighlightLabelPaint.textSize = 14f * densityScale

        asterismPaint.strokeWidth = 1.15f * densityScale * lineArtScale
        asterismHaloPaint.strokeWidth = 4.2f * densityScale * lineArtScale
        asterismCorePaint.strokeWidth = 1.7f * densityScale * lineArtScale
        asterismLabelPaint.textSize = 10.5f * densityScale
        asterismHighlightLabelPaint.textSize = 12.5f * densityScale

        reticlePaint.strokeWidth = 2.2f * densityScale
        reticleTextPaint.textSize = 15f * densityScale

        skyDomeRenderer.horizonTextPaint.textSize = 10.5f * densityScale
        skyDomeRenderer.gridTextPaint.textSize = 9.0f * densityScale
        gridMarkers.horizonTextPaint.textSize = 10.5f * densityScale
        gridMarkers.gridTextPaint.textSize = 9.0f * densityScale

        debugTapCirclePaint.strokeWidth = 2.2f * densityScale
        debugTapLabelPaint.textSize = 12.0f * densityScale
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
     * Packs a terrain profile into the 1440x1 strip the landscape shader reads, and binds it.
     *
     * Elevation is stored as a 16-bit value split across R and G, over a fixed [-128, +128] degree
     * window — 0.0039 degrees per step. A single 8-bit channel was the obvious first try and is not
     * enough: over any usable range it quantises the skyline into visible terraces.
     *
     * Sampling is NEAREST on purpose. Hardware filtering would blend the packed bytes, and an
     * interpolated R channel across a carry boundary decodes to a wild number rather than to a
     * halfway elevation — so the blend is done in the shader, between two decoded samples.
     *
     * Does nothing when [profile] has not changed, so this is safe to call from a recomposition.
     */
    fun setHorizonProfile(profile: com.dazix.caelis.domain.horizon.HorizonProfile?) {
        currentHorizonProfile = profile
        val shader = landscapeShader ?: return

        if (profile == null) {
            hasHorizonProfile = false
            horizonProfileStamp = Long.MIN_VALUE
            return
        }
        if (hasHorizonProfile && profile.generatedAtEpochMillis == horizonProfileStamp) return

        val width = LandscapeShader.PROFILE_WIDTH
        val bitmap = horizonProfileBitmap
            ?: Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888).also { horizonProfileBitmap = it }

        val pixels = IntArray(width)
        for (i in 0 until width) {
            val degrees = profile.elevationsDegrees[i].toDouble()
            val packed = (((degrees + 128.0) / 256.0) * 65535.0)
                .toInt()
                .coerceIn(0, 65535)
            val r = (packed shr 8) and 0xFF
            val g = packed and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, 1)

        val bitmapShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        shader.setInputShader("horizonProfile", bitmapShader)
        hasHorizonProfile = true
        horizonProfileStamp = profile.generatedAtEpochMillis
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
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
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
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        useRealHorizon: Boolean = false,
        azimuthOffsetRad: Float = 0f,
        elevationOffsetRad: Float = 0f,
    ) {
        // The real skyline needs no panorama texture, but the shader still declares both inputs, so
        // an unbound sampler would be undefined behaviour. Requiring the texture either way keeps
        // the fallback honest: no profile, no bitmap, nothing drawn.
        val realHorizon = useRealHorizon && hasHorizonProfile

        if ((isLandscapeVisible || realHorizon) && hasLandscapeTexture && landscapeShader != null && landscapeBrush != null && matrix.size >= 16) {
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
            landscapeShader.setFloatUniform("useRealHorizon", if (realHorizon) 1.0f else 0.0f)
            landscapeShader.setFloatUniform("u_azimuthOffset", azimuthOffsetRad)
            landscapeShader.setFloatUniform("u_elevationOffset", elevationOffsetRad)

            // Near-black rather than black: on an OLED panel a pure-black fill against a dark sky
            // reads as a hole in the render, not as ground.
            terrainColorBuffer[0] = TERRAIN_FILL_R
            terrainColorBuffer[1] = TERRAIN_FILL_G
            terrainColorBuffer[2] = TERRAIN_FILL_B
            terrainColorBuffer[3] = 1.0f
            landscapeShader.setFloatUniform("terrainColor", terrainColorBuffer)

            drawScope.drawRect(brush = landscapeBrush)
        }
    }

    sealed interface CelestialCandidate {
        val name: String
        val subtitle: String?
        val magnitude: Float
        val score: Float
        val target: HitTarget

        data class Planet(
            val planetId: String,
            override val name: String,
            override val magnitude: Float,
            override val score: Float,
            override val subtitle: String? = null,
        ) : CelestialCandidate {
            override val target: HitTarget get() = HitTarget.PlanetHit(planetId)
        }

        data class Star(
            val starIndex: Int,
            override val name: String,
            override val magnitude: Float,
            override val score: Float,
            override val subtitle: String? = null,
        ) : CelestialCandidate {
            override val target: HitTarget get() = HitTarget.StarHit(starIndex)
        }

        data class Dso(
            val dsoIndex: Int,
            val messierId: String,
            override val name: String,
            override val magnitude: Float,
            override val score: Float,
            override val subtitle: String? = null,
        ) : CelestialCandidate {
            override val target: HitTarget get() = HitTarget.DsoHit(dsoIndex)
        }

        data class MeteorShower(
            val showerIndex: Int,
            override val name: String,
            override val score: Float,
            override val magnitude: Float = 0f,
            override val subtitle: String? = null,
        ) : CelestialCandidate {
            override val target: HitTarget get() = HitTarget.MeteorRadiantHit(showerIndex)
        }

        data class Constellation(
            val constellationIndex: Int,
            override val name: String,
            override val score: Float,
            override val magnitude: Float = 0f,
            override val subtitle: String? = null,
        ) : CelestialCandidate {
            override val target: HitTarget get() = HitTarget.ConstellationHit(constellationIndex)
        }
    }

    sealed interface HitTarget {
        data class ConstellationHit(val index: Int) : HitTarget
        data class StarHit(val index: Int) : HitTarget
        data class DsoHit(val index: Int) : HitTarget
        data class MeteorRadiantHit(val index: Int) : HitTarget
        data class PlanetHit(val planetId: String) : HitTarget
        data class MultipleHits(
            val tapX: Float,
            val tapY: Float,
            val candidates: List<CelestialCandidate>,
        ) : HitTarget
        data object Miss : HitTarget
    }

    private fun getStarDisplayName(catalog: NightSkyCatalog, starIndex: Int): Pair<String, String?> {
        val metaLen = catalog.metadataLength()
        for (m in 0 until metaLen) {
            catalog.metadata(reusableMeta, m)
            if (reusableMeta.starIndex() == starIndex) {
                val name = reusableMeta.name() ?: "HIP ${starIndex + 1}"
                val subtitle = reusableMeta.bodyType() ?: reusableMeta.constellation()
                return Pair(name, subtitle)
            }
        }
        return Pair("HIP ${starIndex + 1}", null)
    }

    private fun getConstellationDisplayName(catalog: NightSkyCatalog, constellationIndex: Int): Pair<String, String?> {
        catalog.constellations(reusableConstellation, constellationIndex)
        val name = reusableConstellation.localizedName() ?: reusableConstellation.name() ?: "Constellation"
        val subtitle = if (reusableConstellation.isAsterism()) "Asterism" else "Constellation"
        return Pair(name, subtitle)
    }

    /**
     * Performs adaptive 2D screen-space hit testing with brightness-distance weighting
     * and disambiguation for close celestial objects.
     */
    fun findHitAt(
        tapX: Float,
        tapY: Float,
        maxStarRadiusPx: Float,
        density: Float,
        catalog: NightSkyCatalog?,
        planets: List<com.dazix.caelis.domain.nightsky.PlanetState> = emptyList(),
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = currentHorizonProfile,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): HitTarget {
        val bodyCandidates = ArrayList<CelestialCandidate>(8)
        val activeProfile = horizonProfile ?: currentHorizonProfile

        // 1. Candidate Planets & Moon
        val totalPlanetsToCheck = minOf(planets.size, maxPlanets)
        val minPlanetRadiusPx = maxOf(120f, maxStarRadiusPx)
        val planetCount = StarHitTester.findTopPlanets(
            tapX = tapX,
            tapY = tapY,
            planetScreenX = planetScreenX,
            planetScreenY = planetScreenY,
            planetRadiusPx = planetRadiusPx,
            planetInFront = planetInFront,
            totalPlanets = totalPlanetsToCheck,
            planets = planets,
            minTouchRadiusPx = minPlanetRadiusPx,
            outIndices = topPlanetIndices,
            outScores = topPlanetScores,
            maxCandidates = 5,
            planetWorldX = planetWorldX,
            planetWorldY = planetWorldY,
            planetWorldZ = planetWorldZ,
            isLandscapeVisible = isLandscapeVisible,
            useRealHorizon = useRealHorizon,
            horizonProfile = activeProfile,
            alignAzimuthOffsetDeg = alignAzimuthOffsetDeg,
            alignElevationOffsetDeg = alignElevationOffsetDeg,
        )
        val isCzech = java.util.Locale.getDefault().language.equals("cs", ignoreCase = true)
        for (i in 0 until planetCount) {
            val p = planets[topPlanetIndices[i]]
            val sub = if (p.isMoon || p.id.equals("Moon", ignoreCase = true)) "Natural Satellite" else "Planet"
            bodyCandidates.add(
                CelestialCandidate.Planet(
                    planetId = p.id,
                    name = p.localizedName(isCzech),
                    magnitude = p.magnitude,
                    score = topPlanetScores[i],
                    subtitle = sub,
                )
            )
        }

        // 2. Candidate DSOs (Messier Objects)
        if (catalog != null) {
            val dsoCount = minOf(catalog.dsosLength(), MAX_DSOS)
            val dsoTopCount = StarHitTester.findTopDsos(
                tapX = tapX,
                tapY = tapY,
                dsoScreenX = dsoScreenX,
                dsoScreenY = dsoScreenY,
                dsoRadiusPx = dsoRadiusPx,
                dsoInFront = dsoInFront,
                dsoMagnitudes = dsoMagnitudes,
                totalDsos = dsoCount,
                minTouchRadiusPx = maxStarRadiusPx,
                outIndices = topDsoIndices,
                outScores = topDsoScores,
                maxCandidates = 5,
                dsoWorldX = dsoWorldX,
                dsoWorldY = dsoWorldY,
                dsoWorldZ = dsoWorldZ,
                isLandscapeVisible = isLandscapeVisible,
                useRealHorizon = useRealHorizon,
                horizonProfile = activeProfile,
                alignAzimuthOffsetDeg = alignAzimuthOffsetDeg,
                alignElevationOffsetDeg = alignElevationOffsetDeg,
            )
            for (i in 0 until dsoTopCount) {
                val dIdx = topDsoIndices[i]
                catalog.dsos(reusableDso, dIdx)
                val mNum = reusableDso.messierId()
                val mId = "M$mNum"
                val dName = reusableDso.englishName()
                val displayName = if (!dName.isNullOrEmpty()) "$mId ($dName)" else mId
                val sub = reusableDso.ngcId() ?: "Deep Sky Object"
                bodyCandidates.add(
                    CelestialCandidate.Dso(
                        dsoIndex = dIdx,
                        messierId = mId,
                        name = displayName,
                        magnitude = reusableDso.mag(),
                        score = topDsoScores[i],
                        subtitle = sub,
                    )
                )
            }
        }

        // 3. Candidate Meteor Showers
        if (catalog != null) {
            val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)
            val meteorTopCount = StarHitTester.findTopMeteorRadiants(
                tapX = tapX,
                tapY = tapY,
                meteorScreenX = meteorScreenX,
                meteorScreenY = meteorScreenY,
                meteorInFront = meteorInFront,
                totalMeteors = meteorCount,
                minTouchRadiusPx = maxStarRadiusPx * 1.5f,
                outIndices = topMeteorIndices,
                outScores = topMeteorScores,
                maxCandidates = 5,
                meteorWorldX = meteorWorldX,
                meteorWorldY = meteorWorldY,
                meteorWorldZ = meteorWorldZ,
                isLandscapeVisible = isLandscapeVisible,
                useRealHorizon = useRealHorizon,
                horizonProfile = activeProfile,
                alignAzimuthOffsetDeg = alignAzimuthOffsetDeg,
                alignElevationOffsetDeg = alignElevationOffsetDeg,
            )
            for (i in 0 until meteorTopCount) {
                val mIdx = topMeteorIndices[i]
                catalog.meteorShowers(reusableMeteor, mIdx)
                val mName = reusableMeteor.localizedName() ?: "Meteor Shower"
                val zhr = reusableMeteor.zhr()
                val sub = "Peak: ${reusableMeteor.peakMonth()}/${reusableMeteor.peakDay()} • ZHR: $zhr"
                bodyCandidates.add(
                    CelestialCandidate.MeteorShower(
                        showerIndex = mIdx,
                        name = mName,
                        score = topMeteorScores[i],
                        subtitle = sub,
                    )
                )
            }
        }

        // 4. Candidate Stars
        if (catalog != null) {
            val starCount = StarHitTester.findTopStarsFromCatalog(
                tapX = tapX,
                tapY = tapY,
                starScreenX = starScreenX,
                starScreenY = starScreenY,
                starInFront = starInFront,
                catalog = catalog,
                totalStars = catalog.starsLength(),
                maxRadiusPx = maxStarRadiusPx,
                outIndices = topStarIndices,
                outScores = topStarScores,
                maxCandidates = 5,
                starWorldX = starWorldX,
                starWorldY = starWorldY,
                starWorldZ = starWorldZ,
                isLandscapeVisible = isLandscapeVisible,
                useRealHorizon = useRealHorizon,
                horizonProfile = activeProfile,
                alignAzimuthOffsetDeg = alignAzimuthOffsetDeg,
                alignElevationOffsetDeg = alignElevationOffsetDeg,
            )
            for (i in 0 until starCount) {
                val sIdx = topStarIndices[i]
                val sMag = catalog.byteBuffer.getFloat(catalog.starsVectorOffset() + sIdx * 12 + 8)
                val (starName, starSubtitle) = getStarDisplayName(catalog, sIdx)
                bodyCandidates.add(
                    CelestialCandidate.Star(
                        starIndex = sIdx,
                        name = starName,
                        magnitude = sMag,
                        score = topStarScores[i],
                        subtitle = starSubtitle,
                    )
                )
            }
        }

        // 3. Constellation text labels (priority when in focus, unless planet is hit)
        val constCount = if (catalog != null) minOf(catalog.constellationsLength(), maxConstellations) else 0
        val constHit = if (catalog != null && constCount > 0) {
            StarHitTester.findConstellationTextHit(
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
                centroidWorldX = constellationCentroidWorldX,
                centroidWorldY = constellationCentroidWorldY,
                centroidWorldZ = constellationCentroidWorldZ,
                isLandscapeVisible = isLandscapeVisible,
                useRealHorizon = useRealHorizon,
                horizonProfile = activeProfile,
                alignAzimuthOffsetDeg = alignAzimuthOffsetDeg,
                alignElevationOffsetDeg = alignElevationOffsetDeg,
            )
        } else -1

        if (constHit >= 0 && bodyCandidates.none { it is CelestialCandidate.Planet }) {
            return HitTarget.ConstellationHit(constHit)
        }

        if (bodyCandidates.isEmpty()) {
            if (constHit >= 0) return HitTarget.ConstellationHit(constHit)
            return HitTarget.Miss
        }

        // Sort all body candidates by score ascending (lowest/best score first)
        bodyCandidates.sortBy { it.score }

        if (bodyCandidates.size == 1) {
            return bodyCandidates[0].target
        }

        val bestScore = bodyCandidates[0].score
        val competitiveCandidates = bodyCandidates.filter { it.score <= maxOf(bestScore * 4.0f, bestScore + 50f) }

        return if (competitiveCandidates.size == 1) {
            competitiveCandidates[0].target
        } else {
            HitTarget.MultipleHits(
                tapX = tapX,
                tapY = tapY,
                candidates = competitiveCandidates.take(5),
            )
        }
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
     * Ensures static J2000.0 Cartesian unit vectors are precomputed for all stars, DSOs,
     * meteor showers, and constellations. Computed once per catalog load.
     */
    fun ensureStaticCelestialVectors(catalog: NightSkyCatalog) {
        if (isStarCelCacheReady) return
        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()
        val totalStars = minOf(catalog.starsLength(), MAX_STARS)
        val deg2rad = Math.PI / 180.0

        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val raRad = bb.getFloat(offset).toDouble() * deg2rad
            val decRad = bb.getFloat(offset + 4).toDouble() * deg2rad

            val cosDec = kotlin.math.cos(decRad)
            val sinDec = kotlin.math.sin(decRad)
            val cosRa = kotlin.math.cos(raRad)
            val sinRa = kotlin.math.sin(raRad)

            starCelX[i] = (cosDec * cosRa).toFloat()
            starCelY[i] = sinDec.toFloat()
            starCelZ[i] = (cosDec * sinRa).toFloat()
            starCelComputed[i] = true
        }

        val localDso = DeepSkyObject()
        val dsoCount = minOf(catalog.dsosLength(), MAX_DSOS)
        for (d in 0 until dsoCount) {
            catalog.dsos(localDso, d)
            val raRad = localDso.ra().toDouble() * deg2rad
            val decRad = localDso.dec().toDouble() * deg2rad
            val cosDec = kotlin.math.cos(decRad)
            dsoCelX[d] = (cosDec * kotlin.math.cos(raRad)).toFloat()
            dsoCelY[d] = kotlin.math.sin(decRad).toFloat()
            dsoCelZ[d] = (cosDec * kotlin.math.sin(raRad)).toFloat()
        }

        val localMeteor = MeteorShower()
        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)
        for (m in 0 until meteorCount) {
            catalog.meteorShowers(localMeteor, m)
            val raRad = localMeteor.ra().toDouble() * deg2rad
            val decRad = localMeteor.dec().toDouble() * deg2rad
            val cosDec = kotlin.math.cos(decRad)
            meteorCelX[m] = (cosDec * kotlin.math.cos(raRad)).toFloat()
            meteorCelY[m] = kotlin.math.sin(decRad).toFloat()
            meteorCelZ[m] = (cosDec * kotlin.math.sin(raRad)).toFloat()
        }

        val localConst = Constellation()
        val constCount = minOf(catalog.constellationsLength(), maxConstellations)
        for (c in 0 until constCount) {
            catalog.constellations(localConst, c)
            val cRaRad = localConst.centroidRa().toDouble() * deg2rad
            val cDecRad = localConst.centroidDec().toDouble() * deg2rad
            val cosDec = kotlin.math.cos(cDecRad)
            constellationCentroidCelX[c] = (cosDec * kotlin.math.cos(cRaRad)).toFloat()
            constellationCentroidCelY[c] = kotlin.math.sin(cDecRad).toFloat()
            constellationCentroidCelZ[c] = (cosDec * kotlin.math.sin(cRaRad)).toFloat()
        }

        isStarCelCacheReady = true
    }

    /**
     * Tier 1: High Priority (Naked Eye Stars: mag <= 6.0f).
     * Computes vectors for the ~5,000 visible stars immediately in < 1ms, unlocking the renderer.
     */
    fun updateStarWorldVectorsTier1(
        catalog: NightSkyCatalog,
        lstDegrees: Double,
        latitudeDegrees: Double,
        totalStars: Int,
    ) {
        ensureStaticCelestialVectors(catalog)
        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()

        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val rawMag = bb.getFloat(offset + 8)

            if (rawMag <= 6.0f) {
                val ra = bb.getFloat(offset).toDouble()
                val dec = bb.getFloat(offset + 4).toDouble()

                EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
                starWorldX[i] = tempWorldVec[0]
                starWorldY[i] = tempWorldVec[1]
                starWorldZ[i] = tempWorldVec[2]
                starVectorComputed[i] = true
            } else {
                starVectorComputed[i] = false
            }
        }

        // Precompute world vectors for Deep Sky Objects (Messier Catalog)
        val dsoCount = minOf(catalog.dsosLength(), MAX_DSOS)
        for (d in 0 until dsoCount) {
            catalog.dsos(reusableDso, d)
            val ra = reusableDso.ra().toDouble()
            val dec = reusableDso.dec().toDouble()
            EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
            dsoWorldX[d] = tempWorldVec[0]
            dsoWorldY[d] = tempWorldVec[1]
            dsoWorldZ[d] = tempWorldVec[2]
            dsoMagnitudes[d] = reusableDso.mag()
        }

        // Precompute world vectors for Meteor Shower Radiants
        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)
        for (m in 0 until meteorCount) {
            catalog.meteorShowers(reusableMeteor, m)
            val ra = reusableMeteor.ra().toDouble()
            val dec = reusableMeteor.dec().toDouble()
            EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
            meteorWorldX[m] = tempWorldVec[0]
            meteorWorldY[m] = tempWorldVec[1]
            meteorWorldZ[m] = tempWorldVec[2]
        }

        cachedLstDegrees = lstDegrees
        cachedLatitudeDegrees = latitudeDegrees
        cachedStarCount = totalStars
        isStarCacheReady = true
    }

    /**
     * Tier 2: Lazy / Background (Telescopic Stars: mag > 6.0f).
     * Populates remaining ~78,000 faint stars quietly in the background without UI frame drops.
     */
    fun updateStarWorldVectorsTier2(
        catalog: NightSkyCatalog,
        lstDegrees: Double,
        latitudeDegrees: Double,
        totalStars: Int,
        isCancelled: () -> Boolean = { false },
    ) {
        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()
        val tempVec = FloatArray(3) // Thread-safe local buffer

        for (i in 0 until totalStars) {
            if (isCancelled()) return
            if (!starVectorComputed[i]) {
                val offset = starsVectorOffset + i * 12
                val ra = bb.getFloat(offset).toDouble()
                val dec = bb.getFloat(offset + 4).toDouble()

                EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, tempVec, 0)
                starWorldX[i] = tempVec[0]
                starWorldY[i] = tempVec[1]
                starWorldZ[i] = tempVec[2]
                starVectorComputed[i] = true
            }
        }
    }

    /**
     * Combined synchronous vector update for testing and batch processing.
     */
    fun updateStarWorldVectors(
        catalog: NightSkyCatalog,
        lstDegrees: Double,
        latitudeDegrees: Double,
        totalStars: Int,
    ) {
        updateStarWorldVectorsTier1(catalog, lstDegrees, latitudeDegrees, totalStars)
        updateStarWorldVectorsTier2(catalog, lstDegrees, latitudeDegrees, totalStars)
    }

    /**
     * Updates world unit vectors for stars and celestial bodies from Equatorial coordinates.
     * Enforces strict 64-bit Double precision for [lstDegrees].
     */
    fun updateEquatorialToEnuVectors(
        catalog: NightSkyCatalog,
        lstDegrees: Double,
        latitudeDegrees: Double,
        totalStars: Int = minOf(catalog.starsLength(), MAX_STARS),
    ) {
        updateStarWorldVectors(catalog, lstDegrees, latitudeDegrees, totalStars)
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
        showPlanets: Boolean = true,
        showDsos: Boolean = true,
        showMeteorShowers: Boolean = true,
        isAtmosphereVisible: Boolean = true,
        context: Context? = null,
        debugOffsetX: Float = 0f,
        debugOffsetY: Float = 0f,
        debugScaleMultiplier: Float = 1.0f,
        debugRotationOffset: Float = 0f,
        isDebugFocusRegionVisible: Boolean = false,
        debugFocusRadiusMultiplier: Float = 0.48f,
        debugFocusDisplayThreshold: Float = 0.0f,
        planets: List<com.dazix.caelis.domain.nightsky.PlanetState> = emptyList(),
    ): Int {
        if (catalog == null || matrix.size < 16) return 0
        val totalStars = minOf(catalog.starsLength(), MAX_STARS)
        if (totalStars == 0) return 0

        // Delta-time calculation for smooth 60 FPS animation progress
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (lastFrameTimeMs == 0L) 0.016f else ((now - lastFrameTimeMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameTimeMs = now

        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()

        // Precompute static celestial J2000.0 vectors once
        ensureStaticCelestialVectors(catalog)

        // Compute 3x3 Celestial -> Device transformation matrix for this exact frame's LST (Zero allocations)
        CelestialMatrixCalculator.computeCelestialToDeviceMatrix(
            sensorMatrix16 = matrix,
            lstDegrees = lstDegrees,
            latitudeDegrees = latitudeDegrees,
            outMatrix9 = cel2devMatrix,
        )

        val m00 = cel2devMatrix[0]
        val m01 = cel2devMatrix[1]
        val m02 = cel2devMatrix[2]
        val m10 = cel2devMatrix[3]
        val m11 = cel2devMatrix[4]
        val m12 = cel2devMatrix[5]
        val m20 = cel2devMatrix[6]
        val m21 = cel2devMatrix[7]
        val m22 = cel2devMatrix[8]

        // Reset bucket visible counts
        for (b in 0 until numBuckets) {
            bucketCounts[b] = 0
        }

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas

        // 1. Camera optical forward axis in Celestial J2000 space (maps device [0, 0, -1] into celestial)
        val camFwdCelX = -m20
        val camFwdCelY = -m21
        val camFwdCelZ = -m22

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

        // 4. Constellation Centroid Focus & Animation Progress Update (Spatial Hysteresis)
        val constCount = minOf(catalog.constellationsLength(), maxConstellations)
        val focusRadiusIn = w * (debugFocusRadiusMultiplier * (45f / 48f))
        val focusRadiusOut = w * (debugFocusRadiusMultiplier * (55f / 48f))
        val focusRadiusInSq = focusRadiusIn * focusRadiusIn
        val focusRadiusOutSq = focusRadiusOut * focusRadiusOut

        for (c in 0 until constCount) {
            val cvx = constellationCentroidCelX[c]
            val cvy = constellationCentroidCelY[c]
            val cvz = constellationCentroidCelZ[c]

            val cxDev = m00 * cvx + m01 * cvy + m02 * cvz
            val cyDev = m10 * cvx + m11 * cvy + m12 * cvz
            val czDev = m20 * cvx + m21 * cvy + m22 * cvz

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

            val isSelectedConstellation = (c == selectedConstellationIndex)

            if (czs > 0.001f) {
                val cScreenX = halfW + focalLengthPixels * (cxs / czs)
                val cScreenY = halfH - focalLengthPixels * (cys / czs)
                constellationCentroidScreenX[c] = cScreenX
                constellationCentroidScreenY[c] = cScreenY
                constellationCentroidInFront[c] = true

                if (isSelectedConstellation) {
                    constellationIsFocused[c] = true
                } else {
                    val cdx = cScreenX - halfW
                    val cdy = cScreenY - halfH
                    val distSq = cdx * cdx + cdy * cdy

                    val wasFocused = constellationIsFocused[c]

                    // Spatial Hysteresis State Machine: absorbs micro-tremor sensor jitter
                    if (!wasFocused && distSq <= focusRadiusInSq) {
                        constellationIsFocused[c] = true // Cross inner boundary -> Lock ON
                    } else if (wasFocused && distSq > focusRadiusOutSq) {
                        constellationIsFocused[c] = false // Cross outer boundary -> Lock OFF
                    }
                    // If between focusRadiusInSq and focusRadiusOutSq, state remains unchanged!
                }
            } else {
                constellationCentroidInFront[c] = false
                constellationIsFocused[c] = isSelectedConstellation
            }

            // Smooth Delta-Time Integration with Asymmetric Dissolve
            var p = constellationProgress[c]
            val targetSpeed = dt * 2.0f

            if (constellationIsFocused[c]) {
                p += targetSpeed
            } else {
                p -= targetSpeed * 1.5f // Dissolve slightly faster than build-up
            }
            constellationProgress[c] = p.coerceIn(0f, 1f)
        }

        // 5. Fast Dynamic Override Table: force render stars of visible constellations
        forceRenderStar.fill(false)
        if (showConstellations || selectedConstellationIndex in 0 until constCount) {
            for (c in 0 until constCount) {
                val isSelected = (c == selectedConstellationIndex)
                if (!showConstellations && !isSelected) continue
                val progress = constellationProgress[c]
                if (progress > 0.001f || isSelected) {
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

        // 6. Batch process stars with ZERO heap allocations and ZERO trigonometry in hot loop
        for (i in 0 until totalStars) {
            val offset = starsVectorOffset + i * 12
            val rawMag = bb.getFloat(offset + 8)

            val isSelectedStar = (i == selectedStarIndex)
            val isForced = forceRenderStar[i] || isSelectedStar
            val isBrightEnough = rawMag <= faintestVisibleMag

            // Dynamic telescope ocular reveal: cull stars fainter than current zoom limit unless forced by visible constellation or selection
            if (!isBrightEnough && !isForced) {
                starInFront[i] = false
                continue
            }

            // 1. Read precomputed static Cartesian Celestial coordinates (J2000.0)
            val vx = starCelX[i]
            val vy = starCelY[i]
            val vz = starCelZ[i]

            // 2. Early Vector Dot-Product Frustum Culling (Rejects ~85% of out-of-view stars before matrix math)
            val inFrustum = StarFrustumCulling.isStarInFrustum(camFwdCelX, camFwdCelY, camFwdCelZ, vx, vy, vz, dotThreshold)
            if (!inFrustum && !isForced) {
                starInFront[i] = false
                continue
            }

            // 3. Simple Matrix Multiplication: Transform Celestial -> Device coordinates (V_dev = M_cel2dev * V_cel)
            val xDev = m00 * vx + m01 * vy + m02 * vz
            val yDev = m10 * vx + m11 * vy + m12 * vz
            val zDev = m20 * vx + m21 * vy + m22 * vz

            // 4. Map physical device axes to camera screen coordinate system
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

            // 5. Perspective screen coordinates
            val screenX = halfW + focalLengthPixels * (xs / zs)
            val screenY = halfH - focalLengthPixels * (ys / zs)

            starScreenX[i] = screenX
            starScreenY[i] = screenY
            starInFront[i] = true

            if (!inFrustum) {
                continue
            }

            if (screenX !in -margin..(w + margin) || screenY !in -margin..(h + margin)) {
                continue
            }

            // 6. Physical Atmospheric Star Extinction (dim stars near horizon 0° <= h < 15°)
            var effectiveMag = rawMag
            if (isAtmosphereVisible) {
                val vzEnu = if (matrix.size >= 16) {
                    matrix[8] * xDev + matrix[9] * yDev + matrix[10] * zDev
                } else if (matrix.size >= 9) {
                    matrix[6] * xDev + matrix[7] * yDev + matrix[8] * zDev
                } else 0f
                val starElevationDeg = (Math.toDegrees(kotlin.math.asin(vzEnu.coerceIn(-1.0f, 1.0f).toDouble()))).toFloat()
                if (starElevationDeg in 0.0f..StarOptics.EXTINCTION_ANGLE_DEG) {
                    effectiveMag = StarOptics.computeAirmassExtinction(rawMag, starElevationDeg)
                    if (effectiveMag > faintestVisibleMag && !isForced) {
                        continue
                    }
                }
            }

            // 7. Map apparent magnitude into one of the 6 fixed pre-allocated rendering buckets
            val bucketIndex = StarOptics.computeBucketIndex(effectiveMag)
            val count = bucketCounts[bucketIndex]
            if (count < MAX_STARS) {
                val idx = count * 2
                bucketPoints[bucketIndex][idx] = screenX
                bucketPoints[bucketIndex][idx + 1] = screenY
                bucketCounts[bucketIndex] = count + 1
                totalVisibleStars++
            }
        }

        // 6b. Batch Render Artistic Constellation Overlays (Behind constellation lines and stars)
        val minDisplayThreshold = maxOf(0.001f, debugFocusDisplayThreshold)
        val hasSelectedConstellation = selectedConstellationIndex in 0 until constCount
        if (((showConstellations && showConstellationArt) || hasSelectedConstellation) && context != null) {
            for (c in 0 until constCount) {
                val isSelected = (c == selectedConstellationIndex)
                if ((!showConstellations || !showConstellationArt) && !isSelected) continue
                if (!constellationCentroidInFront[c] && !isSelected) continue
                val p = constellationProgress[c]
                if (p <= minDisplayThreshold && !isSelected) continue
                val effectiveP = if (isSelected) {
                    1.0f
                } else if (debugFocusDisplayThreshold > 0f) {
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
                    artMatrix.setPolyToPoly(artSrcPoints, 0, artDstPoints, 0, 4)

                    // 6. Apply Debug Scale & Rotation
                    if (debugScaleMultiplier != 1.0f || debugRotationOffset != 0f) {
                        val cx = (artDstPoints[0] + artDstPoints[2] + artDstPoints[4] + artDstPoints[6]) * 0.25f
                        val cy = (artDstPoints[1] + artDstPoints[3] + artDstPoints[5] + artDstPoints[7]) * 0.25f
                        artMatrix.postScale(debugScaleMultiplier, debugScaleMultiplier, cx, cy)
                        artMatrix.postRotate(debugRotationOffset, cx, cy)
                    }

                    // 7. Paint Setup & Draw
                    artPaint.alpha = (90f * effectiveP).toInt().coerceIn(0, 255)
                    nativeCanvas.drawBitmap(bitmap, artMatrix, artPaint)
                }
            }
        }

        // 7. Batch Render Context-Aware Constellations ("Meet-in-the-Middle" & Single Line at 100%)
        if (showConstellations || hasSelectedConstellation) {
            val density = drawScope.density
            val gapPx = 7f * density
            val artLineBoost = if (showConstellationArt) 1.25f else 1.0f

            for (c in 0 until constCount) {
                val isSelected = (c == selectedConstellationIndex)
                if (!showConstellations && !isSelected) continue
                if (!constellationCentroidInFront[c] && !isSelected) continue
                val p = constellationProgress[c]
                if (p <= minDisplayThreshold && !isSelected) continue
                val effectiveP = if (isSelected) {
                    1.0f
                } else if (debugFocusDisplayThreshold > 0f) {
                    ((p - debugFocusDisplayThreshold) / (1f - debugFocusDisplayThreshold)).coerceIn(0f, 1f)
                } else {
                    p
                }

                catalog.constellations(reusableConstellation, c)
                val linesLen = reusableConstellation.linesLength()
                var lineCoordsCount = 0

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
                                        if (lineCoordsCount + 4 <= constellationLinesBuffer.size) {
                                            constellationLinesBuffer[lineCoordsCount] = startX
                                            constellationLinesBuffer[lineCoordsCount + 1] = startY
                                            constellationLinesBuffer[lineCoordsCount + 2] = endX
                                            constellationLinesBuffer[lineCoordsCount + 3] = endY
                                            lineCoordsCount += 4
                                        }
                                    } else {
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
                if (!isAsterism && constellationCentroidInFront[c] && (p > labelMinThreshold || isSelected)) {
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

        // 7b. Dynamic Solar System Planetary Rendering (Dual-State: Point Light <-> High-Res Texture)
        activePlanetsCount = 0
        val totalPlanets = minOf(planets.size, maxPlanets)
        val selectedPlanet = selectedPlanetId
        val hasSelectedPlanet = selectedPlanet != null && planets.any { it.id.equals(selectedPlanet, ignoreCase = true) }
        if ((showPlanets || hasSelectedPlanet) && planets.isNotEmpty()) {
            val density = drawScope.density

            for (pIdx in 0 until totalPlanets) {
                val planet = planets[pIdx]
                val isSelected = selectedPlanet != null && planet.id.equals(selectedPlanet, ignoreCase = true)
                if (!showPlanets && !isSelected) {
                    planetInFront[pIdx] = false
                    continue
                }
                val ra = planet.raDegrees
                val dec = planet.decDegrees

                // Compute ENU world unit vector
                EquatorialCoordinates.computeWorldVector(ra, dec, lstDegrees, latitudeDegrees, planetWorldVec, 0)
                val vx = planetWorldVec[0]
                val vy = planetWorldVec[1]
                val vz = planetWorldVec[2]
                planetWorldX[pIdx] = vx
                planetWorldY[pIdx] = vy
                planetWorldZ[pIdx] = vz

                // Transform World -> Device coordinates: V_dev = R^T * V_world
                val xDev = matrix[0] * vx + matrix[4] * vy + matrix[8] * vz
                val yDev = matrix[1] * vx + matrix[5] * vy + matrix[9] * vz
                val zDev = matrix[2] * vx + matrix[6] * vy + matrix[10] * vz

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
                    planetInFront[pIdx] = false
                    continue
                }

                val pX = halfW + focalLengthPixels * (xs / zs)
                val pY = halfH - focalLengthPixels * (ys / zs)

                planetScreenX[pIdx] = pX
                planetScreenY[pIdx] = pY
                planetInFront[pIdx] = true
                planetIds[pIdx] = planet.id
                activePlanetsCount++

                // Viewport culling with safety margin
                val marginPlanet = 150.0f
                if (pX !in -marginPlanet..(w + marginPlanet) || pY !in -marginPlanet..(h + marginPlanet)) {
                    continue
                }

                // 1. Safe angular diameter fallback (physical body diameter in degrees)
                val safeAngularDiameterDeg = when {
                    planet.isMoon || planet.id.equals("moon", ignoreCase = true) -> {
                        if (planet.angularDiameterDeg in 0.3f..1.5f) planet.angularDiameterDeg else 0.518f
                    }
                    planet.angularDiameterDeg > 0f -> planet.angularDiameterDeg
                    else -> 0.01f
                }

                // 2. Base physical screen size (Pinhole perspective projection)
                var diameterInPixels = 2f * focalLengthPixels * kotlin.math.tan(Math.toRadians(safeAngularDiameterDeg.toDouble() * 0.5)).toFloat()

                // 3. The Planetarium Trick: Artificial boosting for distant small celestial bodies
                // The smaller the angular size, the larger the boost (e.g. 1x for Jupiter ~0.013°, up to 10x for Neptune/Mercury)
                // Moon is excluded as it is already large enough (~0.52°)
                val isMoon = planet.isMoon || planet.id.equals("moon", ignoreCase = true)
                if (!isMoon) {
                    val boostFactor = (0.013f / safeAngularDiameterDeg).coerceIn(1.0f, 10.0f)
                    diameterInPixels *= boostFactor
                }

                // 4. Smooth Transition Logic (15px threshold)
                val minTextureRenderSizePx = 15f

                val baseColor = getPlanetGlowColor(planet.id)
                planetGlowPaint.color = baseColor

                // Calculate glow size and opacity inversely to magnitude (brighter objects have lower/negative magnitudes)
                // Clamp magnitude between -5.0 (Venus/Max) and 8.0 (Neptune/Min)
                val clampedMag = planet.magnitude.coerceIn(-5.0f, 8.0f)
                // Normalize magnitude to a 0.0 to 1.0 intensity scale (1.0 = brightest)
                val intensity = 1.0f - ((clampedMag + 5.0f) / 13.0f)

                val glowShader = getPlanetGlowShader(planet.id)

                if (diameterInPixels < minTextureRenderSizePx) {
                    // --- STATE A: ZOOMED OUT (Optical Star / Point Light View with Magnitude Halo) ---
                    val opticScale = StarOptics.computeOpticScale(zoomFactor)
                    val coreRadius = 2.5f * density * opticScale
                    val haloRadius = (10.0f + 25.0f * intensity) * (2f / 3f) * density * opticScale
                    planetRadiusPx[pIdx] = maxOf(10f * density, haloRadius)

                    // Solid core
                    planetCorePaint.color = baseColor
                    planetCorePaint.alpha = 255
                    nativeCanvas.drawCircle(pX, pY, coreRadius, planetCorePaint)

                    // Dynamic magnitude-based optical halo (Zero-Alloc RadialGradient, scaled to 2/3)
                    planetGlowMatrix.reset()
                    planetGlowMatrix.postScale(haloRadius, haloRadius)
                    planetGlowMatrix.postTranslate(pX, pY)
                    glowShader.setLocalMatrix(planetGlowMatrix)
                    planetGlowPaint.shader = glowShader
                    planetGlowPaint.alpha = (140 * intensity).toInt().coerceIn(25, 200)
                    nativeCanvas.drawCircle(pX, pY, haloRadius, planetGlowPaint)
                } else {
                    // --- STATE B: ZOOMED IN (Realistic High-Resolution Texture with Atmospheric Rim Glow) ---
                    val resName = "planet_${planet.id.lowercase()}"
                    val bitmap = context?.let { getOrLoadArtwork(it, resName) }
                    if (bitmap != null) {
                        var bodyToImageRatio = 1.0f
                        if (planet.id.equals("Saturn", ignoreCase = true)) {
                            bodyToImageRatio = 2.33f
                        } else if (planet.id.equals("Uranus", ignoreCase = true)) {
                            bodyToImageRatio = 1.0f
                        }

                        val finalDiameterPx = diameterInPixels.coerceAtLeast(minTextureRenderSizePx)
                        val expectedBodyWidthInPixels = bitmap.width.toFloat() / bodyToImageRatio
                        val scale = finalDiameterPx / expectedBodyWidthInPixels

                        val assetDisplayRadius = (bitmap.width * scale) * 0.5f
                        planetRadiusPx[pIdx] = maxOf(12f * density, assetDisplayRadius)

                        // 1. Draw atmospheric rim glow BEFORE drawing the planet bitmap
                        // Account for rings when calculating the background glow radius (scaled to 2/3 extent)
                        val ringMultiplier = when {
                            planet.id.equals("Saturn", ignoreCase = true) -> 2.38f
                            planet.id.equals("Uranus", ignoreCase = true) -> 1.4f
                            else -> 1.2f
                        }
                        val rimGlowRadius = (finalDiameterPx * 0.5f) * ringMultiplier
                        val zoomFade = (30f / finalDiameterPx).coerceIn(0.1f, 1.0f)

                        planetGlowMatrix.reset()
                        planetGlowMatrix.postScale(rimGlowRadius, rimGlowRadius)
                        planetGlowMatrix.postTranslate(pX, pY)
                        glowShader.setLocalMatrix(planetGlowMatrix)
                        planetGlowPaint.shader = glowShader
                        planetGlowPaint.alpha = (140 * intensity * zoomFade).toInt().coerceIn(15, 200)
                        nativeCanvas.drawCircle(pX, pY, rimGlowRadius, planetGlowPaint)

                        // 2. Draw planet bitmap over the glow
                        planetMatrix.reset()
                        planetMatrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                        planetMatrix.postScale(scale, scale)
                        val finalRotation = computeCelestialNorthAngle(
                            pX = pX,
                            pY = pY,
                            ra = ra,
                            dec = dec,
                            lstDegrees = lstDegrees,
                            latitudeDegrees = latitudeDegrees,
                            matrix = matrix,
                            displayRotation = displayRotation,
                            halfW = halfW,
                            halfH = halfH,
                            focalLengthPixels = focalLengthPixels,
                        )
                        planetMatrix.postRotate(finalRotation)
                        planetMatrix.postTranslate(pX, pY)

                        planetPaint.shader = null
                        planetPaint.alpha = 255
                        nativeCanvas.drawBitmap(bitmap, planetMatrix, planetPaint)
                    } else {
                        // Fallback if texture asset is loading
                        val finalDiameterPx = diameterInPixels.coerceAtLeast(minTextureRenderSizePx)
                        val radius = maxOf(10f * density, finalDiameterPx * 0.5f)
                        planetRadiusPx[pIdx] = radius
                        planetCorePaint.color = baseColor
                        planetCorePaint.alpha = 255
                        nativeCanvas.drawCircle(pX, pY, radius, planetCorePaint)
                    }
                }
            }
        } else {
            for (pIdx in 0 until maxPlanets) {
                planetInFront[pIdx] = false
            }
        }

        // 7c. Deep Sky Objects (Messier Catalog: Galaxies, Clusters, Nebulae)
        val dsoCount = minOf(catalog.dsosLength(), MAX_DSOS)
        val hasSelectedDso = selectedDsoIndex in 0 until dsoCount
        if (showDsos || hasSelectedDso) {
            val density = drawScope.density
            val opticScale = StarOptics.computeOpticScale(zoomFactor)

            for (d in 0 until dsoCount) {
                val isSelected = (d == selectedDsoIndex)
                if (!showDsos && !isSelected) {
                    dsoInFront[d] = false
                    continue
                }
                catalog.dsos(reusableDso, d)
                val vx = dsoCelX[d]
                val vy = dsoCelY[d]
                val vz = dsoCelZ[d]

                // Transform Celestial -> Device coordinates: V_dev = M_cel2dev * V_cel
                val xDev = m00 * vx + m01 * vy + m02 * vz
                val yDev = m10 * vx + m11 * vy + m12 * vz
                val zDev = m20 * vx + m21 * vy + m22 * vz

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
                    dsoInFront[d] = false
                    continue
                }

                val pX = halfW + focalLengthPixels * (xs / zs)
                val pY = halfH - focalLengthPixels * (ys / zs)

                dsoScreenX[d] = pX
                dsoScreenY[d] = pY
                dsoInFront[d] = true

                // Viewport culling
                val marginDso = 100.0f
                if (pX !in -marginDso..(w + marginDso) || pY !in -marginDso..(h + marginDso)) {
                    continue
                }

                // Compute dynamic screen radius based on angular size or default
                val sizeArcmin = reusableDso.sizeArcmin()
                val baseSizeRad = if (sizeArcmin > 0f) Math.toRadians((sizeArcmin / 60.0)).toFloat() else 0.004f
                val physicalRadiusPx = (focalLengthPixels * kotlin.math.tan(baseSizeRad * 0.5f))
                val renderedRadiusPx = physicalRadiusPx.coerceIn(8f * density * opticScale, 70f * density * opticScale)
                dsoRadiusPx[d] = renderedRadiusPx

                val dsoType = reusableDso.type()
                val mNum = reusableDso.messierId()
                val messierId = "M$mNum"

                // Check for photorealistic artwork texture (Hybrid Rendering)
                val artRes = reusableDso.artResourceName()
                var drewTexture = false
                if (!artRes.isNullOrEmpty()) {
                    val bitmap = getOrLoadArtwork(context, artRes)
                    if (bitmap != null) {
                        val artWidthDeg = reusableDso.artWidthDeg()
                        val wDeg = if (artWidthDeg > 0f) artWidthDeg.toDouble() else (sizeArcmin.toDouble() / 60.0).coerceAtLeast(0.1)
                        val bw = bitmap.width.toFloat()
                        val bh = bitmap.height.toFloat()
                        val aspect = bh / bw
                        val halfWDeg = wDeg * 0.5
                        val halfHDeg = halfWDeg * aspect

                        val posAngleDeg = reusableDso.positionAngleDeg().toDouble() + reusableDso.artRotationDeg().toDouble()
                        val phiRad = Math.toRadians(posAngleDeg)
                        val cosP = kotlin.math.cos(phiRad)
                        val sinP = kotlin.math.sin(phiRad)

                        val dsoRa = reusableDso.ra().toDouble()
                        val dsoDec = reusableDso.dec().toDouble()
                        val decRad = Math.toRadians(dsoDec)
                        val cosDec = kotlin.math.cos(decRad).coerceAtLeast(0.01)

                        var allInFront = true
                        for (i in 0 until 4) {
                            val u = when (i) {
                                0, 3 -> -halfWDeg
                                else -> halfWDeg
                            }
                            val v = when (i) {
                                0, 1 -> halfHDeg
                                else -> -halfHDeg
                            }
                            val uRot = u * cosP - v * sinP
                            val vRot = u * sinP + v * cosP
                            val cRa = dsoRa - (uRot / cosDec)
                            val cDec = (dsoDec + vRot).coerceIn(-90.0, 90.0)

                            EquatorialCoordinates.computeWorldVector(cRa, cDec, lstDegrees, latitudeDegrees, tempWorldVec, 0)
                            val inFront = projectWorldToScreen(
                                tempWorldVec[0], tempWorldVec[1], tempWorldVec[2],
                                matrix, displayRotation, halfW, halfH, focalLengthPixels,
                                dsoCornerProjected, i * 3
                            )
                            if (!inFront) {
                                allInFront = false
                            }
                        }

                        val z0 = dsoCornerProjected[2]
                        val z1 = dsoCornerProjected[5]
                        val z2 = dsoCornerProjected[8]
                        val z3 = dsoCornerProjected[11]

                        if (allInFront && z0 > 0.001f && z1 > 0.001f && z2 > 0.001f && z3 > 0.001f) {
                            dsoPolySrc[0] = 0f; dsoPolySrc[1] = 0f
                            dsoPolySrc[2] = bw; dsoPolySrc[3] = 0f
                            dsoPolySrc[4] = bw; dsoPolySrc[5] = bh
                            dsoPolySrc[6] = 0f; dsoPolySrc[7] = bh

                            dsoPolyDst[0] = dsoCornerProjected[0]
                            dsoPolyDst[1] = dsoCornerProjected[1]
                            dsoPolyDst[2] = dsoCornerProjected[3]
                            dsoPolyDst[3] = dsoCornerProjected[4]
                            dsoPolyDst[4] = dsoCornerProjected[6]
                            dsoPolyDst[5] = dsoCornerProjected[7]
                            dsoPolyDst[6] = dsoCornerProjected[9]
                            dsoPolyDst[7] = dsoCornerProjected[10]

                            dsoMatrix.reset()
                            dsoMatrix.setPolyToPoly(dsoPolySrc, 0, dsoPolyDst, 0, 4)

                            artPaint.alpha = 230
                            nativeCanvas.drawBitmap(bitmap, dsoMatrix, artPaint)
                            drewTexture = true
                        }
                    }
                }

                // If no texture was drawn, draw the type-specific vector glyph
                if (!drewTexture) {
                    val dsoType = reusableDso.type()
                    when (dsoType) {
                        DsoType.GALAXY -> {
                            val posAngle = reusableDso.positionAngleDeg()
                            val axisRatio = reusableDso.axisRatio().coerceIn(0.15f, 1.0f)
                            nativeCanvas.save()
                            nativeCanvas.translate(pX, pY)
                            nativeCanvas.rotate(posAngle)
                            dsoRectF.set(-renderedRadiusPx, -renderedRadiusPx * axisRatio, renderedRadiusPx, renderedRadiusPx * axisRatio)
                            nativeCanvas.drawOval(dsoRectF, dsoHaloPaint)
                            nativeCanvas.drawOval(dsoRectF, dsoPaint)
                            nativeCanvas.drawCircle(0f, 0f, 1.8f * density, dsoCorePaint)
                            nativeCanvas.restore()
                        }
                        DsoType.GLOBULAR_CLUSTER -> {
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoHaloPaint)
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoPaint)
                            nativeCanvas.drawLine(pX - renderedRadiusPx, pY, pX + renderedRadiusPx, pY, dsoPaint)
                            nativeCanvas.drawLine(pX, pY - renderedRadiusPx, pX, pY + renderedRadiusPx, dsoPaint)
                            nativeCanvas.drawCircle(pX, pY, 1.8f * density, dsoCorePaint)
                        }
                        DsoType.OPEN_CLUSTER -> {
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoHaloPaint)
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoDashedPaint)
                            nativeCanvas.drawCircle(pX, pY, 1.8f * density, dsoCorePaint)
                        }
                        DsoType.NEBULA -> {
                            val r = renderedRadiusPx * 0.85f
                            dsoRectF.set(pX - r, pY - r, pX + r, pY + r)
                            nativeCanvas.drawRoundRect(dsoRectF, 4f * density, 4f * density, dsoHaloPaint)
                            nativeCanvas.drawRoundRect(dsoRectF, 4f * density, 4f * density, dsoPaint)
                            nativeCanvas.drawCircle(pX, pY, 1.5f * density, dsoCorePaint)
                        }
                        DsoType.PLANETARY_NEBULA -> {
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoHaloPaint)
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoPaint)
                            val tick = 4f * density
                            nativeCanvas.drawLine(pX - renderedRadiusPx - tick, pY, pX - renderedRadiusPx, pY, dsoPaint)
                            nativeCanvas.drawLine(pX + renderedRadiusPx, pY, pX + renderedRadiusPx + tick, pY, dsoPaint)
                            nativeCanvas.drawLine(pX, pY - renderedRadiusPx - tick, pX, pY - renderedRadiusPx, dsoPaint)
                            nativeCanvas.drawLine(pX, pY + renderedRadiusPx, pX, pY + renderedRadiusPx + tick, dsoPaint)
                        }
                        else -> {
                            nativeCanvas.drawCircle(pX, pY, renderedRadiusPx, dsoPaint)
                        }
                    }
                }

                // Draw Messier ID Label below symbol
                val labelPaint = if (isSelected) dsoHighlightLabelPaint else dsoLabelPaint
                val labelY = pY + renderedRadiusPx + (14f * density)
                nativeCanvas.drawText(messierId, pX, labelY, labelPaint)
            }
        } else {
            for (d in 0 until MAX_DSOS) {
                dsoInFront[d] = false
            }
        }

        // 7d. Meteor Shower Radiants
        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)
        val hasSelectedMeteor = selectedMeteorIndex in 0 until meteorCount
        if (showMeteorShowers || hasSelectedMeteor) {
            val density = drawScope.density

            for (m in 0 until meteorCount) {
                val isSelected = (m == selectedMeteorIndex)
                if (!showMeteorShowers && !isSelected) {
                    meteorInFront[m] = false
                    continue
                }
                catalog.meteorShowers(reusableMeteor, m)
                val vx = meteorCelX[m]
                val vy = meteorCelY[m]
                val vz = meteorCelZ[m]

                // Transform Celestial -> Device coordinates: V_dev = M_cel2dev * V_cel
                val xDev = m00 * vx + m01 * vy + m02 * vz
                val yDev = m10 * vx + m11 * vy + m12 * vz
                val zDev = m20 * vx + m21 * vy + m22 * vz

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
                    meteorInFront[m] = false
                    continue
                }

                val pX = halfW + focalLengthPixels * (xs / zs)
                val pY = halfH - focalLengthPixels * (ys / zs)

                meteorScreenX[m] = pX
                meteorScreenY[m] = pY
                meteorInFront[m] = true

                // Viewport culling
                val marginMeteor = 80.0f
                if (pX !in -marginMeteor..(w + marginMeteor) || pY !in -marginMeteor..(h + marginMeteor)) {
                    continue
                }

                // Render radiant vector glyph
                nativeCanvas.save()
                nativeCanvas.translate(pX, pY)
                nativeCanvas.drawPath(radiantPath, radiantPaint)
                nativeCanvas.restore()

                // Render radiant label
                val mName = reusableMeteor.localizedName() ?: ""
                val labelPaint = if (isSelected) radiantHighlightLabelPaint else radiantLabelPaint
                val labelY = pY + (32f * density)
                nativeCanvas.drawText(mName, pX, labelY, labelPaint)
            }
        } else {
            for (m in 0 until MAX_METEOR_SHOWERS) {
                meteorInFront[m] = false
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
            nativeCanvas.drawCircle(halfW, halfH, focusRadiusOut, debugFocusFillPaint)
            nativeCanvas.drawCircle(halfW, halfH, focusRadiusIn, debugFocusCirclePaint)
            nativeCanvas.drawCircle(halfW, halfH, focusRadiusOut, debugFocusCirclePaint)
            val pctIn = ((focusRadiusIn / w) * 100f).toInt()
            val pctOut = ((focusRadiusOut / w) * 100f).toInt()
            val threshPct = (debugFocusDisplayThreshold * 100f).toInt()
            nativeCanvas.drawText("FOCUS AREA (In=$pctIn% W, Out=$pctOut% W, thresh=$threshPct%)", halfW, halfH - focusRadiusOut - 16f, debugFocusLabelPaint)

            // Draw centroid indicators for constellations in front of the camera
            for (c in 0 until constCount) {
                if (!constellationCentroidInFront[c]) continue
                val cxScreen = constellationCentroidScreenX[c]
                val cyScreen = constellationCentroidScreenY[c]
                if (cxScreen < -margin || cxScreen > w + margin || cyScreen < -margin || cyScreen > h + margin) continue

                val inFocus = constellationIsFocused[c]
                debugCentroidPaint.color = if (inFocus) debugCentroidInFocusColor else debugCentroidOutOfFocusColor
                nativeCanvas.drawCircle(cxScreen, cyScreen, 6f, debugCentroidPaint)
            }
        }

        lastVisibleStarCount = totalVisibleStars
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

        // Only draw broken crosshair if a Star, Planet, DSO, or Meteor Shower is selected (not a Constellation)
        if (selectedStarIndex >= 0 || selectedPlanetId != null || selectedDsoIndex >= 0 || selectedMeteorIndex >= 0) {
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

    /**
     * Triggers a temporary animated debug visual circle at the tap point showing the exact hit-test radius.
     * Zero heap allocations.
     */
    fun triggerTapIndicator(tapX: Float, tapY: Float, radiusPx: Float, radiusDp: Int) {
        tapIndicatorX = tapX
        tapIndicatorY = tapY
        tapIndicatorRadiusPx = radiusPx
        tapIndicatorRadiusDp = radiusDp
        tapIndicatorTimestamp = System.currentTimeMillis()
    }

    /**
     * Draws the animated debug tap search radius circle if recently triggered.
     * Zero heap allocations per frame.
     */
    fun drawTapRadiusIndicator(
        drawScope: DrawScope,
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        if (tapIndicatorTimestamp == 0L) return
        val elapsed = System.currentTimeMillis() - tapIndicatorTimestamp
        val durationMs = 1200L
        if (elapsed >= durationMs) return

        val fraction = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val alpha = 1.0f - fraction

        val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas

        val baseColor = if (isNightVision) {
            val palette = paletteForTheme(theme)
            AndroidColor.rgb(
                (palette.primary.red * 255).toInt(),
                (palette.primary.green * 255).toInt(),
                (palette.primary.blue * 255).toInt(),
            )
        } else {
            AndroidColor.rgb(0, 229, 255) // Electric Cyan in standard mode
        }

        val r = AndroidColor.red(baseColor)
        val g = AndroidColor.green(baseColor)
        val b = AndroidColor.blue(baseColor)

        val strokeAlpha = (alpha * 220).toInt().coerceIn(0, 255)
        val fillAlpha = (alpha * 32).toInt().coerceIn(0, 255)
        val textAlpha = (alpha * 240).toInt().coerceIn(0, 255)

        debugTapCirclePaint.color = AndroidColor.argb(strokeAlpha, r, g, b)
        debugTapFillPaint.color = AndroidColor.argb(fillAlpha, r, g, b)
        debugTapCenterPaint.color = AndroidColor.argb(strokeAlpha, r, g, b)
        debugTapLabelPaint.color = AndroidColor.argb(textAlpha, r, g, b)

        val x = tapIndicatorX
        val y = tapIndicatorY
        val radius = tapIndicatorRadiusPx

        // Outer Search Boundary Ring & Disc Fill
        nativeCanvas.drawCircle(x, y, radius, debugTapFillPaint)
        nativeCanvas.drawCircle(x, y, radius, debugTapCirclePaint)

        // Center Target Crosshair / Pinpoint Dot
        nativeCanvas.drawCircle(x, y, 4f, debugTapCenterPaint)

        // Radius Label
        val label = "SEARCH RADIUS: ${tapIndicatorRadiusDp} dp (${radius.toInt()} px)"
        nativeCanvas.drawText(label, x, y - radius - 12f, debugTapLabelPaint)
    }

    // ==== GPU sky: the screen-space half ====================================================
    //
    // With the Filament scene drawing stars, figures, art, planets and the ground, what is left
    // for the Canvas is what the GPU has no good primitive for: text, the DSO and meteor vector
    // glyphs, and the grid's markers. Every position comes from the scene's own [SkyProjector] and
    // celestial matrix, i.e. from the pose the GPU frame was just drawn with.

    private val overlayProjected = FloatArray(3)
    private val overlayEnu = FloatArray(3)
    private var dsoLabels: Array<String> = emptyArray()
    private var meteorLabels: Array<String> = emptyArray()
    private var overlayCatalog: NightSkyCatalog? = null

    /** Strings read once per catalogue instead of once per frame. */
    private fun ensureOverlayLabels(catalog: NightSkyCatalog) {
        if (overlayCatalog === catalog) return
        overlayCatalog = catalog
        dsoLabels = Array(minOf(catalog.dsosLength(), MAX_DSOS)) {
            catalog.dsos(reusableDso, it)
            "M${reusableDso.messierId()}"
        }
        meteorLabels = Array(minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)) {
            catalog.meteorShowers(reusableMeteor, it)
            reusableMeteor.localizedName() ?: ""
        }
    }

    /** Ground occlusion of the overlay, the same test the tap hit test uses. */
    private fun isBelowGround(frame: com.dazix.caelis.ar.scene.NightSkyFrame, x: Float, y: Float, z: Float): Boolean =
        StarHitTester.isOccludedByLandscape(
            x, y, z,
            // A wanted terrain skyline without its profile yet is drawn as a flat ring: ground below 0°.
            isLandscapeVisible = frame.showLandscape || frame.useRealHorizon,
            useRealHorizon = frame.useRealHorizon,
            horizonProfile = currentHorizonProfile,
            alignAzimuthOffsetDeg = frame.alignAzimuthOffsetDeg,
            alignElevationOffsetDeg = frame.alignElevationOffsetDeg,
        )

    /**
     * Draws everything of the night sky that is text or screen-space vector art, on top of the GPU
     * frame [scene] just rendered from [frame].
     *
     * The Canvas renderer drew constellation names, DSO glyphs and radiants before the landscape,
     * which then covered whatever was below ground; here they are on top of the GPU, so each one is
     * skipped when its direction is occluded instead.
     */
    fun drawGpuOverlay(
        drawScope: DrawScope,
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
        displayRotation: Int,
        showConstellations: Boolean,
        showMeteorShowers: Boolean,
        lstDegrees: Double,
        latitudeDegrees: Double,
        selectedBody: CelestialBodyDetails?,
        isDebugFocusRegionVisible: Boolean,
        debugFocusRadiusMultiplier: Float,
        debugFocusDisplayThreshold: Float,
        showTapRadiusIndicator: Boolean,
    ) {
        ensureOverlayLabels(catalog)
        val canvas = drawScope.drawContext.canvas.nativeCanvas
        val density = drawScope.density
        val w = drawScope.size.width
        val h = drawScope.size.height
        val projector = scene.projector
        val c2e = scene.celestialToEnu
        val focus = scene.focus
        val margin = 40f

        // Constellation names, sliding up as the figure grows (asterisms carry no name).
        val constCount = scene.constellationCount
        for (c in 0 until constCount) {
            val isSelected = c == frame.selectedConstellation
            if (!showConstellations && !isSelected) continue
            if (!focus.centroidInFront[c]) continue
            val p = focus.progress[c]
            if (!(p > maxOf(0.05f, debugFocusDisplayThreshold) || isSelected)) continue
            catalog.constellations(reusableConstellation, c)
            if (reusableConstellation.isAsterism()) continue
            SkyFrameMath.transformDirection(c2e, constellationCentroidCelX[c], constellationCentroidCelY[c], constellationCentroidCelZ[c], overlayEnu)
            if (isBelowGround(frame, overlayEnu[0], overlayEnu[1], overlayEnu[2])) continue
            val effectiveP = when {
                isSelected -> 1f
                debugFocusDisplayThreshold > 0f -> ((p - debugFocusDisplayThreshold) / (1f - debugFocusDisplayThreshold)).coerceIn(0f, 1f)
                else -> p
            }
            val cx = focus.centroidScreenX[c]
            val textY = focus.centroidScreenY[c] - (40f * (1f - effectiveP))
            if (cx !in -margin..(w + margin) || textY !in -margin..(h + margin)) continue
            val label = reusableConstellation.localizedName() ?: reusableConstellation.name() ?: continue
            val paint = if (isSelected) constellationHighlightLabelPaint else constellationLabelPaint
            paint.alpha = ((if (isSelected) 255f else 200f) * effectiveP).toInt().coerceIn(0, 255)
            canvas.drawText(label, cx, textY, paint)
        }

        // Deep-sky glyphs (only where no photograph is on screen) and Messier numbers.
        val opticScale = StarOptics.computeOpticScale(frame.zoomRatio)
        val focal = projector.focalLengthPixels
        for (d in 0 until minOf(scene.dsoCount, dsoLabels.size)) {
            val isSelected = d == frame.selectedDso
            if (!frame.showDsos && !isSelected) continue
            if (!projector.projectCelestial(c2e, scene.dsoCel[d * 3], scene.dsoCel[d * 3 + 1], scene.dsoCel[d * 3 + 2], overlayProjected)) continue
            val pX = overlayProjected[0]
            val pY = overlayProjected[1]
            if (pX !in -100f..(w + 100f) || pY !in -100f..(h + 100f)) continue
            SkyFrameMath.transformDirection(c2e, scene.dsoCel[d * 3], scene.dsoCel[d * 3 + 1], scene.dsoCel[d * 3 + 2], overlayEnu)
            if (isBelowGround(frame, overlayEnu[0], overlayEnu[1], overlayEnu[2])) continue
            catalog.dsos(reusableDso, d)
            val radius = dsoRenderedRadiusPx(reusableDso.sizeArcmin(), focal, density, opticScale)
            if (!scene.dsoArtShown[d]) drawDsoGlyph(canvas, reusableDso, pX, pY, radius, density)
            canvas.drawText(dsoLabels[d], pX, pY + radius + 14f * density, if (isSelected) dsoHighlightLabelPaint else dsoLabelPaint)
        }

        // Meteor shower radiants.
        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS, meteorLabels.size)
        if (meteorCount > 0) ensureStaticCelestialVectors(catalog)
        for (m in 0 until meteorCount) {
            val isSelected = m == selectedMeteorIndex
            if (!showMeteorShowers && !isSelected) continue
            if (!projector.projectCelestial(c2e, meteorCelX[m], meteorCelY[m], meteorCelZ[m], overlayProjected)) continue
            val pX = overlayProjected[0]
            val pY = overlayProjected[1]
            if (pX !in -80f..(w + 80f) || pY !in -80f..(h + 80f)) continue
            SkyFrameMath.transformDirection(c2e, meteorCelX[m], meteorCelY[m], meteorCelZ[m], overlayEnu)
            if (isBelowGround(frame, overlayEnu[0], overlayEnu[1], overlayEnu[2])) continue
            canvas.save()
            canvas.translate(pX, pY)
            canvas.drawPath(radiantPath, radiantPaint)
            canvas.restore()
            canvas.drawText(meteorLabels[m], pX, pY + 32f * density, if (isSelected) radiantHighlightLabelPaint else radiantLabelPaint)
        }

        if (frame.showGrid) gridMarkers.draw(canvas, projector, w, h)

        if (isDebugFocusRegionVisible) {
            val halfW = w * 0.5f
            val halfH = h * 0.5f
            val radiusIn = w * (debugFocusRadiusMultiplier * (45f / 48f))
            val radiusOut = w * (debugFocusRadiusMultiplier * (55f / 48f))
            canvas.drawCircle(halfW, halfH, radiusOut, debugFocusFillPaint)
            canvas.drawCircle(halfW, halfH, radiusIn, debugFocusCirclePaint)
            canvas.drawCircle(halfW, halfH, radiusOut, debugFocusCirclePaint)
            for (c in 0 until constCount) {
                if (!focus.centroidInFront[c]) continue
                val x = focus.centroidScreenX[c]
                val y = focus.centroidScreenY[c]
                if (x < -margin || x > w + margin || y < -margin || y > h + margin) continue
                debugCentroidPaint.color = if (focus.focused[c]) debugCentroidInFocusColor else debugCentroidOutOfFocusColor
                canvas.drawCircle(x, y, 6f, debugCentroidPaint)
            }
        }

        drawSelectionReticle(
            drawScope = drawScope,
            selectedBody = selectedBody,
            matrix = frame.deviceToEnu,
            lstDegrees = lstDegrees,
            latitudeDegrees = latitudeDegrees,
            displayRotation = displayRotation,
            fovHorizontalDegrees = frame.fovHorizontalDegrees,
            w = w,
            h = h,
            density = density,
        )
        if (showTapRadiusIndicator) {
            drawTapRadiusIndicator(drawScope, frame.nightVisionTheme, frame.nightVisionActive)
        }
    }

    /** The Canvas DSO size: the object's angular size at this focal length, within 8..70 dp. */
    private fun dsoRenderedRadiusPx(sizeArcmin: Float, focal: Float, density: Float, opticScale: Float): Float {
        val baseSizeRad = if (sizeArcmin > 0f) Math.toRadians(sizeArcmin / 60.0).toFloat() else 0.004f
        return (focal * tan(baseSizeRad * 0.5f)).coerceIn(8f * density * opticScale, 70f * density * opticScale)
    }

    private fun drawDsoGlyph(canvas: android.graphics.Canvas, dso: DeepSkyObject, pX: Float, pY: Float, radius: Float, density: Float) {
        when (dso.type()) {
            DsoType.GALAXY -> {
                val axisRatio = dso.axisRatio().coerceIn(0.15f, 1.0f)
                canvas.save()
                canvas.translate(pX, pY)
                canvas.rotate(dso.positionAngleDeg())
                dsoRectF.set(-radius, -radius * axisRatio, radius, radius * axisRatio)
                canvas.drawOval(dsoRectF, dsoHaloPaint)
                canvas.drawOval(dsoRectF, dsoPaint)
                canvas.drawCircle(0f, 0f, 1.8f * density, dsoCorePaint)
                canvas.restore()
            }
            DsoType.GLOBULAR_CLUSTER -> {
                canvas.drawCircle(pX, pY, radius, dsoHaloPaint)
                canvas.drawCircle(pX, pY, radius, dsoPaint)
                canvas.drawLine(pX - radius, pY, pX + radius, pY, dsoPaint)
                canvas.drawLine(pX, pY - radius, pX, pY + radius, dsoPaint)
                canvas.drawCircle(pX, pY, 1.8f * density, dsoCorePaint)
            }
            DsoType.OPEN_CLUSTER -> {
                canvas.drawCircle(pX, pY, radius, dsoHaloPaint)
                canvas.drawCircle(pX, pY, radius, dsoDashedPaint)
                canvas.drawCircle(pX, pY, 1.8f * density, dsoCorePaint)
            }
            DsoType.NEBULA -> {
                val r = radius * 0.85f
                dsoRectF.set(pX - r, pY - r, pX + r, pY + r)
                canvas.drawRoundRect(dsoRectF, 4f * density, 4f * density, dsoHaloPaint)
                canvas.drawRoundRect(dsoRectF, 4f * density, 4f * density, dsoPaint)
                canvas.drawCircle(pX, pY, 1.5f * density, dsoCorePaint)
            }
            DsoType.PLANETARY_NEBULA -> {
                canvas.drawCircle(pX, pY, radius, dsoHaloPaint)
                canvas.drawCircle(pX, pY, radius, dsoPaint)
                val tick = 4f * density
                canvas.drawLine(pX - radius - tick, pY, pX - radius, pY, dsoPaint)
                canvas.drawLine(pX + radius, pY, pX + radius + tick, pY, dsoPaint)
                canvas.drawLine(pX, pY - radius - tick, pX, pY - radius, dsoPaint)
                canvas.drawLine(pX, pY + radius, pX, pY + radius + tick, dsoPaint)
            }
            else -> canvas.drawCircle(pX, pY, radius, dsoPaint)
        }
    }
    /**
     * The HUD's visible-star count for the GPU path: stars within the telescope limit (or forced
     * by a figure or the selection) whose position is on screen. Writes [lastVisibleStarCount].
     */
    fun countVisibleStars(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
    ) {
        ensureStaticCelestialVectors(catalog)
        val projector = scene.projector
        val c2e = scene.celestialToEnu
        val limit = StarOptics.computeFaintestVisibleMagnitude(frame.zoomRatio)
        val w = projector.viewportWidth
        val h = projector.viewportHeight
        val bb = catalog.byteBuffer
        val base = catalog.starsVectorOffset()
        var count = 0
        for (i in 0 until minOf(scene.stars.starCount, MAX_STARS)) {
            if (bb.getFloat(base + i * 12 + 8) > limit && !scene.stars.isForced(i)) continue
            if (!projector.projectCelestial(c2e, starCelX[i], starCelY[i], starCelZ[i], overlayProjected)) continue
            if (overlayProjected[0] in 0f..w && overlayProjected[1] in 0f..h) count++
        }
        lastVisibleStarCount = count
    }

    /**
     * Fills the hit-test arrays [findHitAt] reads from the GPU scene's last frame. Called on a tap,
     * not per frame: the Canvas renderer kept them as a side effect of drawing every star, which the
     * GPU path no longer does on the CPU.
     *
     * World vectors are rotated with this frame's sidereal time. The Canvas path wrote star world
     * vectors once at load time (so the ground-occlusion test drifted with the sky over an evening)
     * and never wrote the DSO, meteor and centroid ones at all, which left their occlusion test
     * working on zeros.
     */
    fun prepareHitTest(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
        showConstellations: Boolean,
        showMeteorShowers: Boolean,
        density: Float,
    ) {
        ensureStaticCelestialVectors(catalog)
        val projector = scene.projector
        val c2e = scene.celestialToEnu
        val focus = scene.focus

        // Which stars are drawn regardless of the telescope limit, as the scene forces them.
        val constCount = minOf(scene.constellationCount, maxConstellations)
        java.util.Arrays.fill(forceRenderStar, false)
        val totalStars = minOf(catalog.starsLength(), MAX_STARS)
        for (c in 0 until constCount) {
            val isSelected = c == frame.selectedConstellation
            constellationProgress[c] = focus.progress[c]
            constellationCentroidInFront[c] = focus.centroidInFront[c]
            constellationCentroidScreenX[c] = focus.centroidScreenX[c]
            constellationCentroidScreenY[c] = focus.centroidScreenY[c]
            SkyFrameMath.transformDirection(c2e, constellationCentroidCelX[c], constellationCentroidCelY[c], constellationCentroidCelZ[c], overlayEnu)
            constellationCentroidWorldX[c] = overlayEnu[0]
            constellationCentroidWorldY[c] = overlayEnu[1]
            constellationCentroidWorldZ[c] = overlayEnu[2]
            if (!showConstellations && !isSelected) continue
            if (focus.progress[c] > 0.001f || isSelected) {
                catalog.constellations(reusableConstellation, c)
                for (j in 0 until reusableConstellation.linesLength()) {
                    val s = reusableConstellation.lines(j)
                    if (s in 0 until totalStars) forceRenderStar[s] = true
                }
            }
        }

        val limit = StarOptics.computeFaintestVisibleMagnitude(frame.zoomRatio)
        val bb = catalog.byteBuffer
        val base = catalog.starsVectorOffset()
        for (i in 0 until totalStars) {
            val forced = forceRenderStar[i] || i == frame.selectedStar
            if (bb.getFloat(base + i * 12 + 8) > limit && !forced) {
                starInFront[i] = false
                continue
            }
            SkyFrameMath.transformDirection(c2e, starCelX[i], starCelY[i], starCelZ[i], overlayEnu)
            starWorldX[i] = overlayEnu[0]
            starWorldY[i] = overlayEnu[1]
            starWorldZ[i] = overlayEnu[2]
            starInFront[i] = projector.projectEnu(overlayEnu[0], overlayEnu[1], overlayEnu[2], overlayProjected)
            starScreenX[i] = overlayProjected[0]
            starScreenY[i] = overlayProjected[1]
        }

        val opticScale = StarOptics.computeOpticScale(frame.zoomRatio)
        val focal = projector.focalLengthPixels
        val dsoCount = minOf(scene.dsoCount, MAX_DSOS)
        for (d in 0 until dsoCount) {
            if (!frame.showDsos && d != frame.selectedDso) {
                dsoInFront[d] = false
                continue
            }
            catalog.dsos(reusableDso, d)
            SkyFrameMath.transformDirection(c2e, scene.dsoCel[d * 3], scene.dsoCel[d * 3 + 1], scene.dsoCel[d * 3 + 2], overlayEnu)
            dsoWorldX[d] = overlayEnu[0]
            dsoWorldY[d] = overlayEnu[1]
            dsoWorldZ[d] = overlayEnu[2]
            dsoMagnitudes[d] = reusableDso.mag()
            dsoInFront[d] = projector.projectEnu(overlayEnu[0], overlayEnu[1], overlayEnu[2], overlayProjected)
            dsoScreenX[d] = overlayProjected[0]
            dsoScreenY[d] = overlayProjected[1]
            dsoRadiusPx[d] = dsoRenderedRadiusPx(reusableDso.sizeArcmin(), focal, density, opticScale)
        }

        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS)
        for (m in 0 until meteorCount) {
            if (!showMeteorShowers && m != selectedMeteorIndex) {
                meteorInFront[m] = false
                continue
            }
            SkyFrameMath.transformDirection(c2e, meteorCelX[m], meteorCelY[m], meteorCelZ[m], overlayEnu)
            meteorWorldX[m] = overlayEnu[0]
            meteorWorldY[m] = overlayEnu[1]
            meteorWorldZ[m] = overlayEnu[2]
            meteorInFront[m] = projector.projectEnu(overlayEnu[0], overlayEnu[1], overlayEnu[2], overlayProjected)
            meteorScreenX[m] = overlayProjected[0]
            meteorScreenY[m] = overlayProjected[1]
        }

        val planetCount = minOf(scene.planetCount, maxPlanets)
        for (p in 0 until maxPlanets) planetInFront[p] = false
        activePlanetsCount = 0
        for (p in 0 until planetCount) {
            val planet = frame.planets.getOrNull(p) ?: continue
            val isSelected = frame.selectedPlanetId != null && planet.id.equals(frame.selectedPlanetId, ignoreCase = true)
            if (!frame.showPlanets && !isSelected) continue
            SkyFrameMath.transformDirection(c2e, scene.planetCel[p * 3], scene.planetCel[p * 3 + 1], scene.planetCel[p * 3 + 2], overlayEnu)
            planetWorldX[p] = overlayEnu[0]
            planetWorldY[p] = overlayEnu[1]
            planetWorldZ[p] = overlayEnu[2]
            if (!projector.projectEnu(overlayEnu[0], overlayEnu[1], overlayEnu[2], overlayProjected)) continue
            planetScreenX[p] = overlayProjected[0]
            planetScreenY[p] = overlayProjected[1]
            planetRadiusPx[p] = scene.planetAppearance[p].hitRadiusPx
            planetInFront[p] = true
            planetIds[p] = planet.id
            activePlanetsCount++
        }
    }
}

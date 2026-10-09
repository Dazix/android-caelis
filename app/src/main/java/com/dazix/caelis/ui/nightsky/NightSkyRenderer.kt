package com.dazix.caelis.ui.nightsky

import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Surface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.DsoType
import com.dazix.caelis.data.nightsky.MeteorShower
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.StarMetadata
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.EquatorialCoordinates
import com.dazix.caelis.domain.nightsky.StarHitTester
import com.dazix.caelis.domain.nightsky.StarOptics
import kotlin.math.tan

/**
 * The CPU half of the Night Sky screen: the text and screen-space vector overlay drawn on top of the
 * Filament scene ([drawGpuOverlay]), tap hit testing against what that scene last drew
 * ([prepareHitTest] + [findHitAt]), and the selection state the screen and the scene share.
 *
 * The stars, figures, artwork, planets, Milky Way, atmosphere and ground are drawn by
 * `ar.scene.NightSkyScene`; the Canvas renderer that used to draw them here is gone. Zero
 * allocations per frame.
 */
class NightSkyRenderer {

    /** Labels and markers of the GPU grid. */
    val gridMarkers = com.dazix.caelis.ar.scene.SkyGridMarkers()


    // Precalculated static ENU World Unit Vectors (East, North, Up) for all catalog stars
    // Eliminates all trigonometry (sin/cos/computeWorldVector) from the 60 FPS hot render loop
    val starWorldX = FloatArray(MAX_STARS)
    val starWorldY = FloatArray(MAX_STARS)
    val starWorldZ = FloatArray(MAX_STARS)
    @Volatile
    var lastVisibleStarCount: Int = 0

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

    // Cached screen projection coordinates for constellation line resolution
    internal val starScreenX = FloatArray(MAX_STARS)
    internal val starScreenY = FloatArray(MAX_STARS)
    internal val starInFront = BooleanArray(MAX_STARS)

    // Fast O(1) zero-allocation lookup table to force render constituent stars of visible constellations
    val forceRenderStar = BooleanArray(MAX_STARS)

    val constellationProgress = FloatArray(maxConstellations) { 0f }
    val constellationCentroidScreenX = FloatArray(maxConstellations)
    val constellationCentroidScreenY = FloatArray(maxConstellations)
    val constellationCentroidInFront = BooleanArray(maxConstellations)
    val constellationCentroidWorldX = FloatArray(maxConstellations)
    val constellationCentroidWorldY = FloatArray(maxConstellations)
    val constellationCentroidWorldZ = FloatArray(maxConstellations)
    var currentHorizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null

    var selectedConstellationIndex: Int = -1
    var selectedStarIndex: Int = -1


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
        const val MAX_STARS = 100_000
        const val MAX_DSOS = 128
        const val MAX_METEOR_SHOWERS = 32

        data class IndicatorTransform(
            val arrowX: Float,
            val arrowY: Float,
            val angleRad: Float,
            val angleDeg: Float,
        )

        /** Edge distance of the off-screen chevron, in pixels. */
        const val OFFSCREEN_EDGE_PADDING = 22f

        /** [offscreenIndicatorInto] as a value, for tests. */
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
            val out = FloatArray(4)
            offscreenIndicatorInto(xs, ys, zs, screenX, screenY, w, h, edgePadding, out)
            return IndicatorTransform(out[0], out[1], out[2], out[3])
        }

        /**
         * Where the off-screen chevron goes and which way it points: writes (arrowX, arrowY,
         * angleRad, angleDeg) into [out]. A target in front uses its projected position; one
         * behind uses its camera-space bearing (xs right, ys up), and dead behind points down.
         * Returns false when the screen is too small to leave [edgePadding]. Zero allocations.
         */
        fun offscreenIndicatorInto(
            xs: Float,
            ys: Float,
            zs: Float,
            screenX: Float,
            screenY: Float,
            w: Float,
            h: Float,
            edgePadding: Float,
            out: FloatArray,
        ): Boolean {
            val centerX = w * 0.5f
            val centerY = h * 0.5f
            val halfW = centerX - edgePadding
            val halfH = centerY - edgePadding
            if (halfW <= 0f || halfH <= 0f) return false

            var dx = if (zs > 0.001f) (screenX - centerX) else xs
            var dy = if (zs > 0.001f) (screenY - centerY) else -ys
            if (kotlin.math.abs(dx) < 0.0001f && kotlin.math.abs(dy) < 0.0001f) dy = 1f

            val angleRad = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
            val tanAngle = kotlin.math.tan(angleRad.toDouble()).toFloat()
            val intersectX: Float
            val intersectY: Float
            if (kotlin.math.abs(halfW * tanAngle) < halfH) {
                // Left or right edge
                intersectX = if (dx > 0) halfW else -halfW
                intersectY = intersectX * tanAngle
            } else {
                // Top or bottom edge
                intersectY = if (dy > 0) halfH else -halfH
                intersectX = intersectY / tanAngle
            }
            out[0] = centerX + intersectX
            out[1] = centerY + intersectY
            out[2] = angleRad
            out[3] = Math.toDegrees(angleRad.toDouble()).toFloat()
            return true
        }
    }



    private val redVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(255, 23, 68), PorterDuff.Mode.SRC_IN)
    private val amberVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(255, 179, 0), PorterDuff.Mode.SRC_IN)
    private val greenVisionFilter = PorterDuffColorFilter(AndroidColor.rgb(0, 230, 118), PorterDuff.Mode.SRC_IN)


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
    private val indicatorBuffer = FloatArray(4)
    private val tempReticleVec = FloatArray(3)


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

    fun applyTheme(state: com.dazix.caelis.domain.nightsky.NightVisionState) {
        applyTheme(theme = state.theme, isNightVision = state.isActive)
    }

    fun applyTheme(
        theme: com.dazix.caelis.domain.nightsky.NightVisionTheme = com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
        isNightVision: Boolean,
    ) {
        gridMarkers.applyTheme(nightVision = isNightVision, theme = theme)

        if (isNightVision) {
            val palette = paletteForTheme(theme)
            val pr = (palette.primary.red * 255).toInt()
            val pg = (palette.primary.green * 255).toInt()
            val pb = (palette.primary.blue * 255).toInt()
            val sr = (palette.secondary.red * 255).toInt()
            val sg = (palette.secondary.green * 255).toInt()
            val sb = (palette.secondary.blue * 255).toInt()

            val primaryInt = AndroidColor.rgb(pr, pg, pb)
            constellationLabelPaint.color = AndroidColor.argb(210, pr, pg, pb)
            constellationHighlightLabelPaint.color = primaryInt
            dsoPaint.color = AndroidColor.argb(200, pr, pg, pb)
            dsoDashedPaint.color = AndroidColor.argb(200, pr, pg, pb)
            dsoHaloPaint.color = AndroidColor.argb(40, pr, pg, pb)
            dsoCorePaint.color = AndroidColor.argb(255, sr, sg, sb)
            dsoLabelPaint.color = AndroidColor.argb(210, pr, pg, pb)
            dsoHighlightLabelPaint.color = primaryInt

            reticlePaint.color = primaryInt
            reticleTextPaint.color = primaryInt
            arrowPaint.color = primaryInt

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
            radiantPaint.color = 0xFF00E676.toInt()
            radiantPaint.colorFilter = null
            radiantLabelPaint.color = 0xFF00E676.toInt()
            radiantHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)

            constellationLabelPaint.color = AndroidColor.argb(220, 77, 208, 225)
            constellationHighlightLabelPaint.color = AndroidColor.rgb(255, 215, 64)
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

    /** Text sizes and stroke widths of the overlay paints for this display density. */
    fun updateOpticalPaints(density: Float = 1.0f) {
        val densityScale = density.coerceAtLeast(0.5f)
        constellationLabelPaint.textSize = 13f * densityScale
        constellationHighlightLabelPaint.textSize = 15f * densityScale
        radiantPaint.strokeWidth = 2.0f * densityScale
        radiantLabelPaint.textSize = 12f * densityScale
        radiantHighlightLabelPaint.textSize = 14f * densityScale

        reticlePaint.strokeWidth = 2.2f * densityScale
        reticleTextPaint.textSize = 15f * densityScale

        gridMarkers.horizonTextPaint.textSize = 10.5f * densityScale
        gridMarkers.gridTextPaint.textSize = 9.0f * densityScale

        debugTapCirclePaint.strokeWidth = 2.2f * densityScale
        debugTapLabelPaint.textSize = 12.0f * densityScale
    }

    /** Keeps the profile for the overlay's and the hit test's ground occlusion; the GPU scene draws it. */
    fun setHorizonProfile(profile: com.dazix.caelis.domain.horizon.HorizonProfile?) {
        currentHorizonProfile = profile
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
        if (!offscreenIndicatorInto(xs, ys, zs, screenX, screenY, w, h, OFFSCREEN_EDGE_PADDING, indicatorBuffer)) return
        val arrowX = indicatorBuffer[0]
        val arrowY = indicatorBuffer[1]
        val angleDeg = indicatorBuffer[3]

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
            val pctIn = ((radiusIn / w) * 100f).toInt()
            val pctOut = ((radiusOut / w) * 100f).toInt()
            val threshPct = (debugFocusDisplayThreshold * 100f).toInt()
            canvas.drawText("FOCUS AREA (In=$pctIn% W, Out=$pctOut% W, thresh=$threshPct%)", halfW, halfH - radiusOut - 16f, debugFocusLabelPaint)
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
    private val hitAppearance = com.dazix.caelis.domain.nightsky.PlanetAppearance()

    fun prepareHitTest(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
        showConstellations: Boolean,
        showMeteorShowers: Boolean,
        density: Float,
    ) = prepareHitTest(
        scene.projector, scene.celestialToEnu, scene.focus, frame, catalog, showConstellations, showMeteorShowers, density,
    )

    /**
     * [prepareHitTest] from what the scene's frame was drawn with rather than from the scene, so it
     * runs without a GPU: [projector] and [celestialToEnu] as the scene last updated them, [focus]
     * as the scene drives it.
     */
    fun prepareHitTest(
        projector: com.dazix.caelis.domain.arframe.SkyProjector,
        celestialToEnu: FloatArray,
        focus: com.dazix.caelis.domain.nightsky.ConstellationFocusState,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
        showConstellations: Boolean,
        showMeteorShowers: Boolean,
        density: Float,
    ) {
        ensureStaticCelestialVectors(catalog)
        val c2e = celestialToEnu

        // Which stars are drawn regardless of the telescope limit, as the scene forces them.
        val constCount = minOf(catalog.constellationsLength(), maxConstellations, focus.capacity)
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
        val dsoCount = minOf(catalog.dsosLength(), MAX_DSOS)
        for (d in 0 until dsoCount) {
            if (!frame.showDsos && d != frame.selectedDso) {
                dsoInFront[d] = false
                continue
            }
            catalog.dsos(reusableDso, d)
            SkyFrameMath.transformDirection(c2e, dsoCelX[d], dsoCelY[d], dsoCelZ[d], overlayEnu)
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

        val planetCount = minOf(frame.planets.size, maxPlanets)
        for (p in 0 until maxPlanets) planetInFront[p] = false
        activePlanetsCount = 0
        for (p in 0 until planetCount) {
            val planet = frame.planets[p]
            val isSelected = frame.selectedPlanetId != null && planet.id.equals(frame.selectedPlanetId, ignoreCase = true)
            if (!frame.showPlanets && !isSelected) continue
            // The same direction and hit radius the scene drew the body with (NightSkyScene.updatePlanets).
            val ra = Math.toRadians(planet.raDegrees)
            val dec = Math.toRadians(planet.decDegrees)
            val cosDec = kotlin.math.cos(dec)
            SkyFrameMath.transformDirection(
                c2e,
                (cosDec * kotlin.math.cos(ra)).toFloat(), kotlin.math.sin(dec).toFloat(), (cosDec * kotlin.math.sin(ra)).toFloat(),
                overlayEnu,
            )
            planetWorldX[p] = overlayEnu[0]
            planetWorldY[p] = overlayEnu[1]
            planetWorldZ[p] = overlayEnu[2]
            if (!projector.projectEnu(overlayEnu[0], overlayEnu[1], overlayEnu[2], overlayProjected)) continue
            planetScreenX[p] = overlayProjected[0]
            planetScreenY[p] = overlayProjected[1]
            hitAppearance.compute(planet.id, planet.isMoon, planet.angularDiameterDeg, planet.magnitude, focal, frame.density, frame.zoomRatio)
            planetRadiusPx[p] = hitAppearance.hitRadiusPx
            planetInFront[p] = true
            planetIds[p] = planet.id
            activePlanetsCount++
        }
    }
}

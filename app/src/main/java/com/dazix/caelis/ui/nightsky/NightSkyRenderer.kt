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
import androidx.compose.ui.graphics.toArgb
import com.dazix.caelis.ar.scene.LandmarkMarks
import com.dazix.caelis.ar.scene.NormalTerrain
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.arframe.LabelDeclutter
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.dazix.caelis.domain.arframe.TextBackdrop
import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.DsoType
import com.dazix.caelis.data.nightsky.MeteorShower
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.StarMetadata
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.EquatorialCoordinates
import com.dazix.caelis.domain.nightsky.SkyNames
import com.dazix.caelis.domain.nightsky.StarHitTester
import com.dazix.caelis.domain.nightsky.StarOptics
import kotlin.math.tan

/**
 * The CPU half of the Night Sky screen: what goes into the Filament scene's marks (names, glyphs,
 * reticle, grid labels; [updateGpuMarks]), the little Compose still draws on top (the off-screen
 * chevron and debug layers; [drawScreenOverlay]), tap hit testing against what that scene last drew
 * ([prepareHitTest] + [findHitAt]), and the selection state the screen and the scene share.
 *
 * The stars, figures, artwork, planets, Milky Way, atmosphere and ground are drawn by
 * `ar.scene.NightSkyScene`; the Canvas renderer that used to draw them here is gone. Zero
 * allocations per frame.
 */
class NightSkyRenderer {

    /** Labels and markers of the GPU grid. */
    val gridMarkers = com.dazix.caelis.ar.scene.SkyGridMarkers()

    /**
     * Names in the app's language. A new set (the language changed while the view model lived on)
     * drops the cached labels, so the sky is relabelled on the next frame rather than at the next
     * catalogue.
     */
    var skyNames: SkyNames = SkyNames.NONE
        set(value) {
            if (field === value) return
            field = value
            overlayCatalog = null
        }


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
        private const val TAG = "NightSkyRenderer"
        private const val SQRT_2 = 1.4142135f

        /**
         * Whether a figure's name is printed. A selected figure always keeps its name, as it keeps
         * its lines: the name is what says what was tapped. Hiding asterism names leaves their
         * lines and every constellation name alone.
         */
        fun isConstellationNameShown(
            isAsterism: Boolean,
            isSelected: Boolean,
            showConstellations: Boolean,
            showAsterismNames: Boolean,
        ): Boolean = isSelected || (showConstellations && (!isAsterism || showAsterismNames))

        /**
         * How far below its final place a constellation name starts its slide, in dp. The drawn
         * offset and the tap hit test both read this: they once disagreed (40 px drawn, 40 dp
         * tested), so on a dense screen a tap on a half-grown name missed it. It was 40 dp, which
         * read as the name appearing and then jumping into place; a quarter of that is still a hint
         * of motion while the figure grows.
         */
        const val CONSTELLATION_LABEL_SLIDE_DP = 10f

        /** Backdrop behind constellation and asterism names: black at ~45 %, x the name's own alpha. */
        private const val NAME_BACKDROP_ARGB = TextBackdrop.NAME_ARGB

        /** The selected name's underlay: the selection colour, nearly opaque so dark letters read on it. */
        private const val SELECTED_NAME_BACKDROP_ALPHA = 230

        /** Letters on the selected name's underlay. */
        private const val SELECTED_NAME_TEXT_ARGB = 0xFF101014.toInt()

        /** Extra space between a name and the one it moved a line away from, dp. */
        private const val NAME_LINE_GAP_DP = 2f

        /** How clear a moved or hidden name's own spot must be before it goes back, dp. */
        private const val NAME_HYSTERESIS_DP = 4f

        /** The most stars of one figure a name steers around (the largest figures have ~30). */
        private const val MAX_FIGURE_STARS = 64

        /** A star's reach on screen when a name is kept off it, dp: a bright star's glow, not its core. */
        private const val FIGURE_STAR_RADIUS_DP = 4f

        /** Every constellation and asterism name on screen at once, with room to spare. */
        private const val MAX_NAME_RESERVATIONS = 256

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

    /**
     * An asterism's name: smaller, regular weight and in the asterism lines' blue, so the Great
     * Square reads as a named pattern inside Pegasus rather than as a figure of its own. Without a
     * name it looked like an unexplained square floating over Pisces.
     */
    val asterismLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }

    /**
     * The selection's underlay colour: a muted blue-grey, or the night vision primary dimmed to
     * 60 %; the gold of the other highlights was too loud as a fill. A selected constellation or
     * asterism name keeps its own size and weight and is only underlaid in this colour
     * ([selectedNamePaint]): a larger, bolder name jumped against the figure when it was tapped and
     * took the space the names around it had settled into.
     */
    val constellationHighlightLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    /** The selected name's letters: the size and typeface of its own paint, dark on the highlight. */
    private val selectedNamePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        color = SELECTED_NAME_TEXT_ARGB
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
            asterismLabelPaint.color = AndroidColor.argb(160, pr, pg, pb)
            constellationHighlightLabelPaint.color = AndroidColor.rgb(pr * 3 / 5, pg * 3 / 5, pb * 3 / 5)
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
            asterismLabelPaint.color = AndroidColor.argb(190, 144, 202, 249)
            constellationHighlightLabelPaint.color = AndroidColor.rgb(176, 190, 197)
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
        asterismLabelPaint.textSize = 11f * densityScale
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
                val subtitle = skyNames.starType(reusableMeta.bodyType())
                    ?: skyNames.constellationOfCode(reusableMeta.constellation())
                return Pair(name, subtitle)
            }
        }
        return Pair("HIP ${starIndex + 1}", null)
    }

    /**
     * Performs adaptive 2D screen-space hit testing with brightness-distance weighting
     * and disambiguation for close celestial objects.
     *
     * What is behind the ground is judged with the ground the last frame was drawn with
     * ([prepareHitTest] takes its offsets from the frame), never with the compass calibration:
     * that is already inside the pose. Passing it here a second time once raised the ground by the
     * stored -19° pitch correction, and every constellation lower than that above the skyline was
     * drawn but could not be tapped.
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
            alignAzimuthOffsetDeg = hitAlignAzimuthDeg,
            alignElevationOffsetDeg = hitAlignElevationDeg,
        )
        for (i in 0 until planetCount) {
            val p = planets[topPlanetIndices[i]]
            val sub = if (p.isMoon || p.id.equals("Moon", ignoreCase = true)) "Natural Satellite" else "Planet"
            bodyCandidates.add(
                CelestialCandidate.Planet(
                    planetId = p.id,
                    name = skyNames.planet(p.id, p.nameEn),
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
                alignAzimuthOffsetDeg = hitAlignAzimuthDeg,
                alignElevationOffsetDeg = hitAlignElevationDeg,
            )
            for (i in 0 until dsoTopCount) {
                val dIdx = topDsoIndices[i]
                catalog.dsos(reusableDso, dIdx)
                val mNum = reusableDso.messierId().toInt()
                val mId = "M$mNum"
                val dName = skyNames.messier(mNum, reusableDso.englishName())
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
                alignAzimuthOffsetDeg = hitAlignAzimuthDeg,
                alignElevationOffsetDeg = hitAlignElevationDeg,
            )
            for (i in 0 until meteorTopCount) {
                val mIdx = topMeteorIndices[i]
                catalog.meteorShowers(reusableMeteor, mIdx)
                val mName = skyNames.meteorShower(reusableMeteor.id().toInt(), reusableMeteor.name()) ?: "Meteor Shower"
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
                alignAzimuthOffsetDeg = hitAlignAzimuthDeg,
                alignElevationOffsetDeg = hitAlignElevationDeg,
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
                textOffsetYPx = CONSTELLATION_LABEL_SLIDE_DP * density,
                halfWidthPx = 75f * density,
                halfHeightPx = 30f * density,
                minProgress = 0.3f,
                centroidWorldX = constellationCentroidWorldX,
                centroidWorldY = constellationCentroidWorldY,
                centroidWorldZ = constellationCentroidWorldZ,
                isLandscapeVisible = isLandscapeVisible,
                useRealHorizon = useRealHorizon,
                horizonProfile = activeProfile,
                alignAzimuthOffsetDeg = hitAlignAzimuthDeg,
                alignElevationOffsetDeg = hitAlignElevationDeg,
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
     * The chevron on the screen edge pointing to the selected body while it is off screen or behind
     * the observer. The reticle itself, on the sky, is a GPU mark ([updateGpuMarks]).
     *
     * Zero heap allocations per frame.
     */
    fun drawSelectionPointer(
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

        if (isVisible) return
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

    // ==== GPU sky: the marks ================================================================
    //
    // Names, DSO and meteor glyphs, the selection reticle and the grid's labels are drawn by the
    // Filament frame itself (the mark batches of NightSkyScene); [updateGpuMarks] lists them. They
    // were a Compose overlay once. Measured on a Pixel 7 Pro, the TextureView showed a GPU frame about
    // 20 ms (2-3 vsyncs) after it was rendered, while the overlay drew every vsync with the newest
    // pose, so held in the hand the names jumped against the stars. Only what sits on the screen
    // edge rather than on the sky (the off-screen chevron) and the debug layers stay in Compose
    // ([drawScreenOverlay]).

    private val overlayProjected = FloatArray(3)
    private val overlayEnu = FloatArray(3)
    private val reticleCel = FloatArray(3)
    private var dsoLabels: Array<String> = emptyArray()
    private var meteorLabels: Array<String> = emptyArray()

    /** Constellation and asterism names by index; null where the catalogue has none. */
    private var constellationLabels: Array<String?> = emptyArray()
    private var labelIsAsterism = BooleanArray(0)
    private var overlayCatalog: NightSkyCatalog? = null

    // The mark atlas sprite of each name, -1 for none.
    private var constellationSprites = IntArray(0)
    private var dsoSprites = IntArray(0)
    private var meteorSprites = IntArray(0)
    private var markAtlasScene: com.dazix.caelis.ar.scene.NightSkyScene? = null
    private val markStyle = IntArray(12)
    private val builtMarkStyle = IntArray(12)
    private val peakMarks = LandmarkMarks()

    // Clearance between constellation and asterism names, and the spot each name got last frame.
    private val nameDeclutter = LabelDeclutter(MAX_NAME_RESERVATIONS)
    private var previousNameSlot = IntArray(0)

    // Star indices of each figure, figure c's at [figureStarStart[c], figureStarStart[c + 1]), and
    // their screen positions in this frame for the name being placed.
    private var figureStarStart = IntArray(1)
    private var figureStars = IntArray(0)
    private val figureStarX = FloatArray(MAX_FIGURE_STARS)
    private val figureStarY = FloatArray(MAX_FIGURE_STARS)


    // The highlight atlas: the selected constellation's, DSO's or shower's name and the reticle's caption.
    private var highlightScene: com.dazix.caelis.ar.scene.NightSkyScene? = null
    private val highlightStyle = IntArray(8)
    private val builtHighlightStyle = IntArray(8)
    private var builtHighlightConstellation = Int.MIN_VALUE
    private var builtHighlightDso = Int.MIN_VALUE
    private var builtHighlightMeteor = Int.MIN_VALUE
    private var builtReticleName: String? = null
    private var highlightConstellationSprite = -1
    private var highlightDsoSprite = -1
    private var highlightMeteorSprite = -1
    private var reticleNameSprite = -1

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
            skyNames.meteorShower(reusableMeteor.id().toInt(), reusableMeteor.name()) ?: ""
        }
        val count = minOf(catalog.constellationsLength(), maxConstellations)
        labelIsAsterism = BooleanArray(count)
        constellationLabels = Array(count) {
            catalog.constellations(reusableConstellation, it)
            labelIsAsterism[it] = reusableConstellation.isAsterism()
            skyNames.constellation(reusableConstellation.id(), labelIsAsterism[it], reusableConstellation.name())
        }
        // Each figure's own stars (those its lines join, once each), for the names to steer around.
        figureStarStart = IntArray(count + 1)
        val stars = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (c in 0 until count) {
            figureStarStart[c] = stars.size
            catalog.constellations(reusableConstellation, c)
            seen.clear()
            for (k in 0 until reusableConstellation.linesLength()) {
                val s = reusableConstellation.lines(k)
                if (s in 0 until MAX_STARS && seen.add(s) && seen.size <= MAX_FIGURE_STARS) stars.add(s)
            }
        }
        figureStarStart[count] = stars.size
        figureStars = stars.toIntArray()
        markAtlasScene = null
    }

    /** The name drawn for constellation or asterism [index] of [catalog], and whether it is an asterism's. */
    internal fun constellationLabelOf(catalog: NightSkyCatalog, index: Int): Pair<String?, Boolean> {
        ensureOverlayLabels(catalog)
        return constellationLabels.getOrNull(index) to labelIsAsterism.getOrElse(index) { false }
    }

    /** Ground occlusion of the marks, the same test the tap hit test uses. */
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

    private fun celestialBelowGround(
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        c2e: FloatArray,
        x: Float, y: Float, z: Float,
    ): Boolean {
        SkyFrameMath.transformDirection(c2e, x, y, z, overlayEnu)
        return isBelowGround(frame, overlayEnu[0], overlayEnu[1], overlayEnu[2])
    }

    /**
     * Draws a constellation name with the paint's colour at full alpha: the Canvas set the alpha per
     * draw (200 or 255 x progress), which is now the sprite's alpha in [LabelBatch].
     *
     * On a light dark backdrop: a name centred on its figure often lands on one of its bright stars
     * ("Orion's Belt" sits right on the belt), and the thin italic letters were lost in the glare.
     */
    private fun addOpaqueText(atlas: com.dazix.caelis.ar.scene.SpriteAtlas, text: String, paint: Paint): Int {
        val alpha = paint.alpha
        paint.alpha = 255
        val sprite = atlas.addText(text, paint, NAME_BACKDROP_ARGB)
        paint.alpha = alpha
        return sprite
    }

    private fun styleOf(paint: Paint, out: IntArray, at: Int) {
        out[at] = paint.color
        out[at + 1] = paint.textSize.toRawBits()
    }

    /** Redraws the catalogue's names and the grid's labels when their text, colour or size changed. */
    private fun ensureMarkAtlas(scene: com.dazix.caelis.ar.scene.NightSkyScene) {
        styleOf(constellationLabelPaint, markStyle, 0)
        styleOf(dsoLabelPaint, markStyle, 2)
        styleOf(radiantLabelPaint, markStyle, 4)
        markStyle[6] = gridMarkers.horizonTextPaint.textSize.toRawBits()
        markStyle[7] = gridMarkers.gridTextPaint.textSize.toRawBits()
        markStyle[8] = gridMarkers.contentKey
        styleOf(asterismLabelPaint, markStyle, 9)
        markStyle[11] = peakMarks.revision
        if (markAtlasScene === scene && markStyle.contentEquals(builtMarkStyle)) return
        markStyle.copyInto(builtMarkStyle)
        markAtlasScene = scene
        val atlas = scene.markAtlas
        atlas.begin()
        constellationSprites = IntArray(constellationLabels.size) { c ->
            val name = constellationLabels[c]
            when {
                name == null -> -1
                labelIsAsterism[c] -> addOpaqueText(atlas, name, asterismLabelPaint)
                else -> addOpaqueText(atlas, name, constellationLabelPaint)
            }
        }
        dsoSprites = IntArray(dsoLabels.size) { atlas.addText(dsoLabels[it], dsoLabelPaint) }
        meteorSprites = IntArray(meteorLabels.size) { atlas.addText(meteorLabels[it], radiantLabelPaint) }
        gridMarkers.addSprites(atlas)
        peakMarks.addSprites(atlas)
        atlas.end()
        val peakSprites = peakMarks.atlasSpriteCount
        if (atlas.spriteCount < constellationSprites.size + dsoSprites.size + meteorSprites.size + com.dazix.caelis.ar.scene.SkyGridMarkers.SPRITE_COUNT + peakSprites) {
            android.util.Log.e(TAG, "mark atlas full: ${atlas.spriteCount} sprites fit, some names are not drawn")
        }
    }

    /** Redraws the highlighted name and the reticle's caption when the selection or style changed. */
    private fun ensureHighlightAtlas(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        reticleName: String?,
    ) {
        styleOf(constellationHighlightLabelPaint, highlightStyle, 0)
        styleOf(dsoHighlightLabelPaint, highlightStyle, 2)
        styleOf(radiantHighlightLabelPaint, highlightStyle, 4)
        styleOf(reticleTextPaint, highlightStyle, 6)
        val c = frame.selectedConstellation
        val d = frame.selectedDso
        val m = selectedMeteorIndex
        if (highlightScene === scene && highlightStyle.contentEquals(builtHighlightStyle) &&
            c == builtHighlightConstellation && d == builtHighlightDso && m == builtHighlightMeteor &&
            reticleName == builtReticleName
        ) return
        highlightStyle.copyInto(builtHighlightStyle)
        highlightScene = scene
        builtHighlightConstellation = c
        builtHighlightDso = d
        builtHighlightMeteor = m
        builtReticleName = reticleName
        val atlas = scene.highlightAtlas
        atlas.begin()
        val constellationName = constellationLabels.getOrNull(c)
        highlightConstellationSprite = if (constellationName == null) {
            -1
        } else {
            val own = if (labelIsAsterism.getOrElse(c) { false }) asterismLabelPaint else constellationLabelPaint
            selectedNamePaint.textSize = own.textSize
            selectedNamePaint.typeface = own.typeface
            val accent = constellationHighlightLabelPaint.color
            val backdrop = AndroidColor.argb(SELECTED_NAME_BACKDROP_ALPHA, AndroidColor.red(accent), AndroidColor.green(accent), AndroidColor.blue(accent))
            atlas.addText(constellationName, selectedNamePaint, backdrop)
        }
        highlightDsoSprite = dsoLabels.getOrNull(d)?.let { atlas.addText(it, dsoHighlightLabelPaint) } ?: -1
        highlightMeteorSprite = meteorLabels.getOrNull(m)?.let { atlas.addText(it, radiantHighlightLabelPaint) } ?: -1
        reticleNameSprite = reticleName?.let { atlas.addText(it, reticleTextPaint) } ?: -1
        atlas.end()
    }

    /**
     * Lists this frame's marks into [scene]'s batches: constellation names (fading and sliding up
     * with their figures), Messier numbers and DSO glyphs, meteor radiants and names, the selection
     * reticle with its caption, and the grid's labels. Call after `scene.update(frame)` and before
     * the frame is rendered.
     *
     * Nothing here depends on the pose except through the focus progress: the GPU projects every
     * mark and hides it behind the camera, so a pan over a settled sky lists the same marks and the
     * batches upload nothing. A mark whose direction is below the ground is left out, as the
     * Canvas renderer's landscape used to cover it.
     */
    fun updateGpuMarks(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        catalog: NightSkyCatalog,
        showConstellations: Boolean,
        showMeteorShowers: Boolean,
        selectedBody: CelestialBodyDetails?,
        debugFocusDisplayThreshold: Float,
    ) {
        ensureOverlayLabels(catalog)
        ensureStaticCelestialVectors(catalog)
        val pointSelected = selectedStarIndex >= 0 || selectedPlanetId != null || selectedDsoIndex >= 0 || selectedMeteorIndex >= 0
        val reticleRa = selectedBody?.raDegrees
        val reticleDec = selectedBody?.decDegrees
        val showReticle = pointSelected && reticleRa != null && reticleDec != null
        // Peak names stand on the real terrain skyline: with the stylised landscape or none there is
        // no ridge for them to stand on. The ridge's colour is the night vision primary when it is on.
        val peakNames = if (frame.showPeakNames && frame.useRealHorizon) {
            currentHorizonProfile?.drawnLandmarks.orEmpty()
        } else {
            emptyList()
        }
        val peakColor = if (frame.nightVisionActive) {
            paletteForTheme(frame.nightVisionTheme).primary.toArgb()
        } else {
            NormalTerrain.toArgb()
        }
        // Font scale 1f on purpose: every other name on this screen (constellations, DSOs, radiants,
        // grid) is drawn at a fixed pixel size that ignores the system font scale, and a peak name that
        // alone followed it would be the odd one out. Sun AR scales all of its marks, so it passes the real one.
        peakMarks.update(peakNames, frame.languageTag, peakColor, frame.density, 1f)
        ensureMarkAtlas(scene)
        ensureHighlightAtlas(scene, frame, if (showReticle) selectedBody?.name else null)

        val density = frame.density
        val c2e = scene.celestialToEnu
        val labels = scene.celestialLabels
        val highlight = scene.highlightLabels
        val glyphs = scene.celestialGlyphs
        labels.begin()
        highlight.begin()
        glyphs.begin()

        // Constellation and asterism names, sliding up as the figure grows, clear of each other: the
        // selection first (it never moves), then constellations, then asterisms, each pass in
        // catalogue order, so the more important name keeps its spot (LabelDeclutter).
        nameDeclutter.clear()
        if (previousNameSlot.size < scene.constellationCount) previousNameSlot = IntArray(scene.constellationCount)
        for (pass in 0 until 3) {
            for (c in 0 until scene.constellationCount) {
                val isSelected = c == frame.selectedConstellation
                val isAsterism = labelIsAsterism.getOrElse(c) { false }
                val inPass = when (pass) {
                    0 -> isSelected
                    1 -> !isSelected && !isAsterism
                    else -> !isSelected && isAsterism
                }
                if (inPass) listConstellationName(scene, frame, c, isSelected, showConstellations, debugFocusDisplayThreshold)
            }
        }

        // Deep-sky glyphs (only where no photograph is on screen) and Messier numbers.
        val opticScale = StarOptics.computeOpticScale(frame.zoomRatio)
        val focal = scene.projector.focalLengthPixels
        for (d in 0 until minOf(scene.dsoCount, dsoLabels.size)) {
            val isSelected = d == frame.selectedDso
            if (!frame.showDsos && !isSelected) continue
            val x = scene.dsoCel[d * 3]
            val y = scene.dsoCel[d * 3 + 1]
            val z = scene.dsoCel[d * 3 + 2]
            if (celestialBelowGround(frame, c2e, x, y, z)) continue
            catalog.dsos(reusableDso, d)
            val radius = dsoRenderedRadiusPx(reusableDso.sizeArcmin(), focal, density, opticScale)
            if (!scene.dsoArtShown[d]) addDsoGlyph(glyphs, reusableDso, x, y, z, radius, density)
            val offsetY = radius + 14f * density
            if (isSelected) highlight.add(highlightDsoSprite, x, y, z, offsetYPx = offsetY)
            else labels.add(dsoSprites[d], x, y, z, offsetYPx = offsetY)
        }

        // Meteor shower radiants. The starburst is raw pixels (dot r = 4, rays from 8 to 24 along
        // the diagonals), as the Canvas path was.
        val meteorCount = minOf(catalog.meteorShowersLength(), MAX_METEOR_SHOWERS, meteorLabels.size)
        val radiantArgb = radiantGlyphColor()
        for (m in 0 until meteorCount) {
            val isSelected = m == selectedMeteorIndex
            if (!showMeteorShowers && !isSelected) continue
            val x = meteorCelX[m]
            val y = meteorCelY[m]
            val z = meteorCelZ[m]
            if (celestialBelowGround(frame, c2e, x, y, z)) continue
            glyphs.add(
                com.dazix.caelis.ar.scene.GlyphBatch.Shape.RADIANT, x, y, z,
                radiusPx = 4f, strokePx = radiantPaint.strokeWidth, argb = radiantArgb,
                a = 8f * SQRT_2, b = 24f * SQRT_2,
            )
            val offsetY = 32f * density
            if (isSelected) highlight.add(highlightMeteorSprite, x, y, z, offsetYPx = offsetY)
            else labels.add(meteorSprites[m], x, y, z, offsetYPx = offsetY)
        }

        // The selection reticle: three arms around a star, planet, DSO or radiant (r1 9.75 dp,
        // r2 18 dp, the top arm left open), its name in the opening. Not for a constellation, whose
        // figure is its own highlight.
        if (showReticle) {
            celestialUnitVector(reticleRa!!, reticleDec!!, reticleCel)
            val x = reticleCel[0]
            val y = reticleCel[1]
            val z = reticleCel[2]
            glyphs.add(
                com.dazix.caelis.ar.scene.GlyphBatch.Shape.RETICLE, x, y, z,
                radiusPx = 0f, strokePx = reticlePaint.strokeWidth, argb = reticlePaint.color,
                a = 9.75f * density, b = 18f * density,
            )
            highlight.add(reticleNameSprite, x, y, z, offsetYPx = -12.5f * density)
        }

        glyphs.end()
        labels.end()
        highlight.end()

        scene.gridLabels.begin()
        scene.gridGlyphs.begin()
        if (frame.showGrid) gridMarkers.emit(scene.gridLabels, scene.gridGlyphs)
        scene.gridGlyphs.end()
        scene.gridLabels.end()

        // The peak names clear only each other: they are drawn under the constellation artwork and
        // lines (NightSkyScene.PEAK_LABEL_PRIORITY), and the other marks print over them where one
        // lands on the ridge, so a shared clearance has not been worth the cost.
        scene.landmarkLabels.begin()
        peakMarks.beginClearance()
        // A name crossing a constellation drawing's paint is faded: under a drawing at its default
        // opacity it still read as lying on top of the figure.
        scene.prepareArtCoverage()
        peakMarks.cover = scene
        peakMarks.emit(scene.landmarkLabels, scene.projector)
        scene.landmarkLabels.end()
    }

    /**
     * Lists constellation or asterism [c]'s name, moved a line down or up, or left out, when a name
     * listed before it in this frame already stands there. A name that is off screen or behind the
     * camera is listed at its own spot without asking: the GPU hides it, and the listing stays the
     * same while the phone pans, so the batch uploads nothing.
     */
    private fun listConstellationName(
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        c: Int,
        isSelected: Boolean,
        showConstellations: Boolean,
        debugFocusDisplayThreshold: Float,
    ) {
        if (!isConstellationNameShown(labelIsAsterism.getOrElse(c) { false }, isSelected, showConstellations, frame.showAsterismNames)) return
        if (constellationLabels.getOrNull(c) == null) return
        val p = scene.focus.progress[c]
        if (!(p > maxOf(0.05f, debugFocusDisplayThreshold) || isSelected)) return
        val c2e = scene.celestialToEnu
        val x = constellationCentroidCelX[c]
        val y = constellationCentroidCelY[c]
        val z = constellationCentroidCelZ[c]
        if (celestialBelowGround(frame, c2e, x, y, z)) return
        val effectiveP = when {
            isSelected -> 1f
            debugFocusDisplayThreshold > 0f -> ((p - debugFocusDisplayThreshold) / (1f - debugFocusDisplayThreshold)).coerceIn(0f, 1f)
            else -> p
        }
        val density = frame.density
        var offsetY = -CONSTELLATION_LABEL_SLIDE_DP * density * (1f - effectiveP)
        // The spot is worked out on the plain name even when it is selected: the selected sprite's
        // backdrop pads it differently, and a box and line step of another size moved the name, and
        // the names around it, as soon as it was tapped.
        val plain = constellationSprites.getOrElse(c) { -1 }
        val usePlain = plain in 0 until scene.markAtlas.spriteCount
        val atlas = if (usePlain || !isSelected) scene.markAtlas else scene.highlightAtlas
        val sprite = if (usePlain || !isSelected) plain else highlightConstellationSprite
        if (sprite in 0 until atlas.spriteCount) {
            val projector = scene.projector
            val inFront = projector.projectCelestial(c2e, x, y, z, overlayProjected)
            val w = atlas.spriteWidth[sprite]
            val h = atlas.spriteHeight[sprite]
            val cx = overlayProjected[0] - atlas.anchorX[sprite] + w * 0.5f
            val cy = overlayProjected[1] + offsetY - atlas.anchorY[sprite] + h * 0.5f
            val hw = w * 0.5f
            val hh = h * 0.5f
            val onScreen = inFront && cx + hw >= 0f && cx - hw <= projector.viewportWidth &&
                cy + hh >= 0f && cy - hh <= projector.viewportHeight
            if (onScreen) {
                // The figure's own stars on screen; a name on them was hard to read, so it steers
                // around them. The selection is placed first and keeps the spot it had before it
                // was tapped (pinned): sent home instead, it jumped a line and pushed the names that
                // had made way for it, and one with no spot left vanished (Emu, Cygnus and the
                // Northern Cross).
                var starCount = 0
                if (c + 1 < figureStarStart.size) {
                    for (j in figureStarStart[c] until figureStarStart[c + 1]) {
                        val s = figureStars[j]
                        if (!projector.projectCelestial(c2e, starCelX[s], starCelY[s], starCelZ[s], overlayProjected)) continue
                        figureStarX[starCount] = overlayProjected[0]
                        figureStarY[starCount] = overlayProjected[1]
                        starCount++
                    }
                }
                // The sprite's height already carries the backdrop and shadow padding; a little more
                // keeps a moved name from touching the one it made way for.
                val step = h + NAME_LINE_GAP_DP * density
                val slot = nameDeclutter.place(
                    cx, cy, hw, hh, step, previousNameSlot[c], NAME_HYSTERESIS_DP * density,
                    figureStarX, figureStarY, starCount, FIGURE_STAR_RADIUS_DP * density,
                    pinned = isSelected,
                )
                previousNameSlot[c] = slot
                if (slot == LabelDeclutter.HIDDEN) return
                offsetY += LabelDeclutter.offsetOf(slot, step)
            } else {
                previousNameSlot[c] = LabelDeclutter.SLOT_BASE
            }
        }
        if (isSelected) {
            scene.highlightLabels.add(highlightConstellationSprite, x, y, z, offsetYPx = offsetY, alpha = effectiveP)
        } else {
            scene.celestialLabels.add(constellationSprites[c], x, y, z, offsetYPx = offsetY, alpha = 200f / 255f * effectiveP)
        }
    }

    /** The radiant paint's colour as drawn: in night vision its SRC_IN filter replaced it. */
    private fun radiantGlyphColor(): Int = when (radiantPaint.colorFilter) {
        redVisionFilter -> AndroidColor.rgb(255, 23, 68)
        amberVisionFilter -> AndroidColor.rgb(255, 179, 0)
        greenVisionFilter -> AndroidColor.rgb(0, 230, 118)
        else -> radiantPaint.color
    }

    /** The celestial-frame unit vector of the scene (x = cos dec cos ra, y = sin dec, z = cos dec sin ra). */
    private fun celestialUnitVector(raDeg: Double, decDeg: Double, out: FloatArray) {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val cosDec = kotlin.math.cos(dec)
        out[0] = (cosDec * kotlin.math.cos(ra)).toFloat()
        out[1] = kotlin.math.sin(dec).toFloat()
        out[2] = (cosDec * kotlin.math.sin(ra)).toFloat()
    }

    /**
     * What remains for Compose over the GPU frame: the debug focus region, the off-screen chevron
     * (it sits on the screen edge, not on the sky, so a vsync of lag does not show) and the debug
     * tap radius.
     */
    fun drawScreenOverlay(
        drawScope: DrawScope,
        scene: com.dazix.caelis.ar.scene.NightSkyScene,
        frame: com.dazix.caelis.ar.scene.NightSkyFrame,
        displayRotation: Int,
        lstDegrees: Double,
        latitudeDegrees: Double,
        selectedBody: CelestialBodyDetails?,
        isDebugFocusRegionVisible: Boolean,
        debugFocusRadiusMultiplier: Float,
        debugFocusDisplayThreshold: Float,
        showTapRadiusIndicator: Boolean,
    ) {
        val canvas = drawScope.drawContext.canvas.nativeCanvas
        val density = drawScope.density
        val w = drawScope.size.width
        val h = drawScope.size.height
        val focus = scene.focus
        val margin = 40f

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
            for (c in 0 until scene.constellationCount) {
                if (!focus.centroidInFront[c]) continue
                val x = focus.centroidScreenX[c]
                val y = focus.centroidScreenY[c]
                if (x < -margin || x > w + margin || y < -margin || y > h + margin) continue
                debugCentroidPaint.color = if (focus.focused[c]) debugCentroidInFocusColor else debugCentroidOutOfFocusColor
                canvas.drawCircle(x, y, 6f, debugCentroidPaint)
            }
        }

        drawSelectionPointer(
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

    /**
     * The Canvas DSO glyphs as distance-field shapes: a galaxy is an oval turned by its position
     * angle, a globular cluster a circle with a cross, an open cluster a dashed circle, a nebula a
     * rounded square, a planetary nebula a circle with four ticks. Each but the last two kinds has a
     * core dot; the outline sits on a faint halo. Stroke widths are the paints' raw pixels.
     */
    private fun addDsoGlyph(
        glyphs: com.dazix.caelis.ar.scene.GlyphBatch,
        dso: DeepSkyObject,
        x: Float, y: Float, z: Float,
        radius: Float,
        density: Float,
    ) {
        val shape = com.dazix.caelis.ar.scene.GlyphBatch.Shape
        val stroke = dsoPaint.strokeWidth
        val color = dsoPaint.color
        val halo = dsoHaloPaint.strokeWidth
        val haloColor = dsoHaloPaint.color
        val core = dsoCorePaint.color
        when (dso.type()) {
            DsoType.GALAXY -> {
                val ratio = dso.axisRatio().coerceIn(0.15f, 1.0f)
                glyphs.add(
                    shape.ELLIPSE, x, y, z, radius, stroke, color, a = ratio,
                    angleRad = Math.toRadians(dso.positionAngleDeg().toDouble()).toFloat(),
                    haloPx = halo, haloArgb = haloColor,
                )
                glyphs.add(shape.DISC, x, y, z, 1.8f * density, 0f, core)
            }
            DsoType.GLOBULAR_CLUSTER -> {
                glyphs.add(shape.RING_CROSS, x, y, z, radius, stroke, color, haloPx = halo, haloArgb = haloColor)
                glyphs.add(shape.DISC, x, y, z, 1.8f * density, 0f, core)
            }
            DsoType.OPEN_CLUSTER -> {
                glyphs.add(
                    shape.DASHED_RING, x, y, z, radius, dsoDashedPaint.strokeWidth, dsoDashedPaint.color,
                    a = 6f, b = 4f, haloPx = halo, haloArgb = haloColor,
                )
                glyphs.add(shape.DISC, x, y, z, 1.8f * density, 0f, core)
            }
            DsoType.NEBULA -> {
                glyphs.add(shape.ROUNDED_SQUARE, x, y, z, radius * 0.85f, stroke, color, a = 4f * density, haloPx = halo, haloArgb = haloColor)
                glyphs.add(shape.DISC, x, y, z, 1.5f * density, 0f, core)
            }
            DsoType.PLANETARY_NEBULA ->
                glyphs.add(shape.RING_TICKS, x, y, z, radius, stroke, color, a = 4f * density, haloPx = halo, haloArgb = haloColor)
            else -> glyphs.add(shape.RING, x, y, z, radius, stroke, color)
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

    /** The ground offsets of the frame [prepareHitTest] last projected, which [findHitAt] occludes with. */
    private var hitAlignAzimuthDeg = 0f
    private var hitAlignElevationDeg = 0f

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
        hitAlignAzimuthDeg = frame.alignAzimuthOffsetDeg
        hitAlignElevationDeg = frame.alignElevationOffsetDeg

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

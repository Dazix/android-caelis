package com.dazix.caelis.ar.scene

import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.MarkReservations
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.arframe.TiltedLabel
import com.dazix.caelis.domain.horizon.HorizonLandmark
import kotlin.math.ceil

/**
 * The peak names on the terrain skyline, as sprites of a [SpriteAtlas] listed into a [LabelBatch].
 *
 * Each peak is a short tick whose foot is the anchor, pinned to the peak's azimuth and apparent
 * altitude, and "Name · 1603 m" starting at the tick's top and rising at [TEXT_ANGLE_DEG] from the
 * horizon. Both stand on the local vertical rather than on the screen's up (`label.mat`'s align
 * path): held screen-upright, the names swung against the ridge whenever the phone rolled, while
 * turning with the landscape the tick keeps standing on its summit. The tick is a sprite rather than a
 * [LineBatch] segment because it is a length in screen pixels, which a sky-space line cannot be at
 * every zoom; one tick sprite serves every peak. The text is drawn level and turned on the GPU: baked
 * at an angle it would take several times the atlas area per name and blur under a roll anyway. The
 * type is smaller than the time bubbles and in the terrain's own colour, so the names read as part of
 * the skyline rather than as a second layer of marks.
 *
 * Sprites are drawn when the list, the app language, the colour (night vision), the density or the
 * font scale change ([update] bumps [revision]; the owner of the atlas redraws it and calls
 * [addSprites]); a frame only lists them. Zero allocations per frame: every array is sized for
 * [MAX_LANDMARKS] up front.
 *
 * What the screen already shows keeps its place: the owner [reserve]s the rectangles of its time
 * bubbles and badges before [emit], and a peak whose name would land on one, or on a name listed
 * before it, is left out of this frame ([MarkClearance]) — a bubble is the precise reading, a peak
 * name the decoration. The names' boxes are laid out by [TiltedLabel], from the same projector.
 */
class LandmarkMarks {

    /** Changes whenever the sprites must be redrawn; the atlas owner compares it with its own. */
    var revision = 0
        private set

    private var landmarks: List<HorizonLandmark> = emptyList()
    private var languageTag = ""
    private var colorArgb = 0
    private var density = 0f
    private var fontScale = 0f

    private var tickSprite = -1
    private var textLift = 0f
    private val sprites = IntArray(MAX_LANDMARKS) { -1 }
    private val azimuth = DoubleArray(MAX_LANDMARKS)
    private val elevation = DoubleArray(MAX_LANDMARKS)
    private val direction = FloatArray(MAX_LANDMARKS * 3)
    private val width = FloatArray(MAX_LANDMARKS)
    private val height = FloatArray(MAX_LANDMARKS)
    private val anchorX = FloatArray(MAX_LANDMARKS)
    private val anchorY = FloatArray(MAX_LANDMARKS)

    // The names accepted in this frame, for the clearance between peaks.
    private var acceptedCount = 0
    private val acceptedX = FloatArray(MAX_LANDMARKS)
    private val acceptedY = FloatArray(MAX_LANDMARKS)
    private val acceptedHalfLength = FloatArray(MAX_LANDMARKS)
    private val acceptedHalfThickness = FloatArray(MAX_LANDMARKS)

    private val reservations = MarkReservations(MAX_RESERVED)
    private val faders = MarkReservations(MAX_FADERS)
    private val box = TiltedLabel()
    private val projected = FloatArray(3)
    private val up = FloatArray(2)
    private val scratch = FloatArray(3)
    private val fontMetrics = Paint.FontMetrics()
    private val scratchDirection = FloatArray(3)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = CASING_ARGB
    }

    /**
     * Sets what is to be drawn. A change of anything bumps [revision]; the list is compared by
     * identity because a profile keeps the same list for as long as it is the one on screen.
     */
    fun update(
        landmarks: List<HorizonLandmark>,
        languageTag: String,
        colorArgb: Int,
        density: Float,
        fontScale: Float,
    ) {
        if (landmarks === this.landmarks && languageTag == this.languageTag && colorArgb == this.colorArgb &&
            density == this.density && fontScale == this.fontScale
        ) return
        this.landmarks = landmarks
        this.languageTag = languageTag
        this.colorArgb = colorArgb
        this.density = density
        this.fontScale = fontScale
        revision++
    }

    /** Draws the sprites into [atlas], between its `begin` and `end`. */
    fun addSprites(atlas: SpriteAtlas) {
        val sp = density * fontScale
        textPaint.textSize = TEXT_SP * sp
        textPaint.color = colorArgb
        textPaint.setShadowLayer(SHADOW_DP * density, 0f, 0f, CASING_ARGB)
        tickPaint.color = colorArgb
        tickPaint.strokeWidth = TICK_WIDTH_DP * density
        tickCasingPaint.strokeWidth = (TICK_WIDTH_DP + 1.6f) * density
        textPaint.getFontMetrics(fontMetrics)
        val pad = ceil(SHADOW_DP * density).toInt() + 2
        val tick = TICK_DP * density
        textLift = tick + TEXT_GAP_DP * density
        for (i in 0 until MAX_LANDMARKS) sprites[i] = -1
        tickSprite = -1
        val count = minOf(landmarks.size, MAX_LANDMARKS)
        if (count == 0) return

        val tw = ceil(tickCasingPaint.strokeWidth).toInt() + 2 * pad
        val th = ceil(tick).toInt() + 2 * pad
        val tax = tw * 0.5f
        val tay = (th - pad).toFloat()
        tickSprite = atlas.add(tw, th, tax, tay) { canvas, left, top ->
            val cx = left + tax
            canvas.drawLine(cx, top + pad, cx, top + tay, tickCasingPaint)
            canvas.drawLine(cx, top + pad, cx, top + tay, tickPaint)
        }
        if (tickSprite < 0) return

        val h = ceil(fontMetrics.bottom - fontMetrics.top).toInt() + 2 * pad
        val baseline = pad - fontMetrics.top
        // The text's axis runs through the middle of its letters, between ascent and descent, so the
        // turned line starts squarely at the tick's top rather than with its baseline there.
        val ay = baseline + (fontMetrics.ascent + fontMetrics.descent) * 0.5f
        for (i in 0 until count) {
            val landmark = landmarks[i]
            // An OSM name has no length limit. A sprite wider than the atlas cannot be packed at all
            // (place returns -1) and the peak would vanish without a trace, so a long one is cut with an
            // ellipsis, well inside the page and at a width that still reads as a label on a phone.
            val text = TextUtils.ellipsize(
                landmark.label(languageTag),
                textPaint,
                minOf(atlas.width * MAX_LABEL_ATLAS_FRACTION, MAX_LABEL_DP * density),
                TextUtils.TruncateAt.END,
            ).toString()
            val w = ceil(textPaint.measureText(text)).toInt() + 2 * pad
            val ax = pad.toFloat()
            val sprite = atlas.add(w, h, ax, ay) { canvas, left, top ->
                canvas.drawText(text, left + ax, top + baseline, textPaint)
            }
            sprites[i] = sprite
            azimuth[i] = landmark.azimuthDegrees
            elevation[i] = landmark.apparentElevationDegrees
            AltAzGridBuilder.direction(azimuth[i], elevation[i], scratchDirection)
            scratchDirection.copyInto(direction, i * 3)
            width[i] = w.toFloat()
            height[i] = h.toFloat()
            anchorX[i] = ax
            anchorY[i] = ay
        }
    }

    /** How many names the last [addSprites] managed to draw (an atlas that is full drops the rest). */
    val spriteCount: Int get() = sprites.count { it >= 0 }

    /** How many sprites of the atlas the last [addSprites] took: the names and the one tick. */
    val atlasSpriteCount: Int get() = spriteCount + if (tickSprite >= 0) 1 else 0

    /** Starts a frame's clearance: nothing is reserved or faded yet. */
    fun beginClearance() {
        reservations.clear()
        faders.clear()
    }

    /**
     * Marks a screen rectangle (centre and half extents, pixels) under which a peak name is drawn
     * faded to [FADED_ALPHA] rather than left out: Night Sky's constellation drawings. Drawn under a
     * drawing at its 35 % default opacity a name still shone through at nearly full strength and read
     * as lying on top of the figure.
     */
    fun fadeUnder(x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        faders.reserve(x, y, halfWidth, halfHeight)
    }

    /** Marks a screen rectangle (centre and half extents, pixels) as taken by a mark that has priority. */
    fun reserve(x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        reservations.reserve(x, y, halfWidth, halfHeight)
    }

    /**
     * Lists this frame's peak names into [labels] (between its `begin` and `end`), left to right.
     *
     * A name on screen that would overlap something reserved or a peak name listed before it is left
     * out, tick and all. One that is off screen or behind the camera is always listed: the GPU hides
     * it, and listing it regardless keeps the listing the same while the phone pans, so nothing is
     * uploaded.
     */
    fun emit(labels: LabelBatch, projector: SkyProjector) {
        if (tickSprite < 0) return
        acceptedCount = 0
        val count = minOf(landmarks.size, MAX_LANDMARKS)
        for (i in 0 until count) {
            val sprite = sprites[i]
            if (sprite < 0) continue
            var alpha = 1f
            val inFront = projector.projectAltAz(azimuth[i], elevation[i], projected)
            if (inFront) {
                val x = projected[0]
                val y = projected[1]
                TiltedLabel.screenUp(projector, azimuth[i], elevation[i], x, y, up, scratch)
                box.place(x, y, up[0], up[1], textLift, TEXT_ANGLE_RAD, width[i], height[i], anchorX[i], anchorY[i])
                val hw = box.boundsHalfWidth
                val hh = box.boundsHalfHeight
                val cx = box.centerX
                val cy = box.centerY
                val onScreen = cx + hw >= 0f && cx - hw <= projector.viewportWidth &&
                    cy + hh >= 0f && cy - hh <= projector.viewportHeight
                if (onScreen) {
                    if (reservations.isTaken(cx, cy, hw, hh) || overlapsAccepted()) continue
                    acceptedX[acceptedCount] = cx
                    acceptedY[acceptedCount] = cy
                    acceptedHalfLength[acceptedCount] = box.halfLength
                    acceptedHalfThickness[acceptedCount] = box.halfThickness
                    acceptedCount++
                    if (faders.isTaken(cx, cy, hw, hh)) alpha = FADED_ALPHA
                }
            }
            val dx = direction[i * 3]
            val dy = direction[i * 3 + 1]
            val dz = direction[i * 3 + 2]
            labels.add(tickSprite, dx, dy, dz, alpha = alpha, alignToHorizon = true)
            labels.add(sprite, dx, dy, dz, alpha = alpha, alignToHorizon = true, liftPx = textLift, angleRad = TEXT_ANGLE_RAD)
        }
    }

    private fun overlapsAccepted(): Boolean {
        for (j in 0 until acceptedCount) {
            if (box.overlapsParallel(acceptedX[j], acceptedY[j], acceptedHalfLength[j], acceptedHalfThickness[j])) {
                return true
            }
        }
        return false
    }

    companion object {
        /** More than [com.dazix.caelis.domain.horizon.LandmarkVisibility.DEFAULT_MAX_LABELS] with room to spare. */
        const val MAX_LANDMARKS = 16

        /** Sprites [addSprites] can put into an atlas: every name and the one tick. */
        const val ATLAS_SPRITES = MAX_LANDMARKS + 1

        /** Sprites [emit] can list into a batch: a tick and a name per peak. */
        const val LISTED_SPRITES = 2 * MAX_LANDMARKS

        /** Counter-clockwise from the horizon line, at which a name rises from its tick. */
        const val TEXT_ANGLE_DEG = 60f
        private val TEXT_ANGLE_RAD = Math.toRadians(TEXT_ANGLE_DEG.toDouble()).toFloat()

        /** Two days of bubbles, four badges and the peaks, with room to spare. */
        private const val MAX_RESERVED = 256

        /** Every constellation drawing on screen at once, with room to spare. */
        private const val MAX_FADERS = 128

        /** A peak name's opacity where it crosses a constellation drawing ([fadeUnder]). */
        const val FADED_ALPHA = 0.3f

        private const val MAX_LABEL_ATLAS_FRACTION = 0.6f
        private const val MAX_LABEL_DP = 260f
        private const val TEXT_SP = 10f
        internal const val TICK_DP = 5.6f
        internal const val TEXT_GAP_DP = 6f
        private const val TICK_WIDTH_DP = 1.2f
        private const val SHADOW_DP = 2f
        private const val CASING_ARGB = 0xB3000000.toInt()
    }
}

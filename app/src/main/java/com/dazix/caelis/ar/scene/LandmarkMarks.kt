package com.dazix.caelis.ar.scene

import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.arframe.MarkReservations
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.horizon.HorizonLandmark
import kotlin.math.ceil

/**
 * The peak names on the terrain skyline, as sprites of a [SpriteAtlas] listed into a [LabelBatch].
 *
 * Each sprite is "Name · 1603 m" over a short tick whose foot is the anchor: pinned to the peak's
 * azimuth and apparent altitude, the text stands clear above the ridge and the tick says which
 * summit it means. The tick is part of the sprite rather than a [LineBatch] segment because it is a
 * length in screen pixels, which a sky-space line cannot be at every zoom, and a sprite costs
 * nothing per frame. The type is smaller than the time bubbles and in the terrain's own colour, so
 * the names read as part of the skyline rather than as a second layer of marks.
 *
 * Sprites are drawn when the list, the app language, the colour (night vision), the density or the
 * font scale change ([update] bumps [revision]; the owner of the atlas redraws it and calls
 * [addSprites]); a frame only lists them. Zero allocations per frame: every array is sized for
 * [MAX_LANDMARKS] up front.
 *
 * What the screen already shows keeps its place: the owner [reserve]s the rectangles of its time
 * bubbles and badges before [emit], and a peak whose label would land on one is left out of this
 * frame ([MarkClearance]) — a bubble is the precise reading, a peak name the decoration.
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

    private val sprites = IntArray(MAX_LANDMARKS) { -1 }
    private val azimuth = DoubleArray(MAX_LANDMARKS)
    private val elevation = DoubleArray(MAX_LANDMARKS)
    private val direction = FloatArray(MAX_LANDMARKS * 3)
    private val halfWidth = FloatArray(MAX_LANDMARKS)
    private val halfHeight = FloatArray(MAX_LANDMARKS)

    /** From the anchor (the foot of the tick) to the sprite's centre, Y down, so negative. */
    private val centreOffsetY = FloatArray(MAX_LANDMARKS)

    private val reservations = MarkReservations(MAX_RESERVED)
    private val projected = FloatArray(3)
    private val fontMetrics = Paint.FontMetrics()
    private val scratchDirection = FloatArray(3)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
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
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val pad = ceil(SHADOW_DP * density).toInt() + 2
        val tick = TICK_DP * density
        val h = ceil(textHeight + tick).toInt() + 2 * pad
        for (i in 0 until MAX_LANDMARKS) sprites[i] = -1
        val count = minOf(landmarks.size, MAX_LANDMARKS)
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
            val ax = w * 0.5f
            val ay = (h - pad).toFloat()
            val sprite = atlas.add(w, h, ax, ay) { canvas, left, top ->
                val cx = left + ax
                canvas.drawText(text, cx, top + pad - fontMetrics.top, textPaint)
                val tickTop = top + pad + textHeight
                canvas.drawLine(cx, tickTop, cx, top + ay, tickCasingPaint)
                canvas.drawLine(cx, tickTop, cx, top + ay, tickPaint)
            }
            sprites[i] = sprite
            azimuth[i] = landmark.azimuthDegrees
            elevation[i] = landmark.apparentElevationDegrees
            AltAzGridBuilder.direction(azimuth[i], elevation[i], scratchDirection)
            scratchDirection.copyInto(direction, i * 3)
            halfWidth[i] = w * 0.5f
            halfHeight[i] = h * 0.5f
            centreOffsetY[i] = h * 0.5f - ay
        }
    }

    /** How many names the last [addSprites] managed to draw (an atlas that is full drops the rest). */
    val spriteCount: Int get() = sprites.count { it >= 0 }

    /** Starts a frame's clearance: nothing is reserved yet. */
    fun beginClearance() {
        reservations.clear()
    }

    /** Marks a screen rectangle (centre and half extents, pixels) as taken by a mark that has priority. */
    fun reserve(x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        reservations.reserve(x, y, halfWidth, halfHeight)
    }

    /**
     * Lists this frame's peak names into [labels] (between its `begin` and `end`), left to right.
     *
     * A name on screen that would overlap something reserved (including a peak name listed before it)
     * is left out. One that is off screen or behind the camera is always listed: the GPU hides it, and
     * listing it regardless keeps the listing the same while the phone pans, so nothing is uploaded.
     */
    fun emit(labels: LabelBatch, projector: SkyProjector) {
        val count = minOf(landmarks.size, MAX_LANDMARKS)
        for (i in 0 until count) {
            val sprite = sprites[i]
            if (sprite < 0) continue
            val inFront = projector.projectAltAz(azimuth[i], elevation[i], projected)
            if (inFront) {
                val x = projected[0]
                val y = projected[1] + centreOffsetY[i]
                val onScreen = x + halfWidth[i] >= 0f && x - halfWidth[i] <= projector.viewportWidth &&
                    y + halfHeight[i] >= 0f && y - halfHeight[i] <= projector.viewportHeight
                if (onScreen) {
                    if (!reservations.tryReserve(x, y, halfWidth[i], halfHeight[i])) continue
                }
            }
            labels.add(sprite, direction[i * 3], direction[i * 3 + 1], direction[i * 3 + 2])
        }
    }

    companion object {
        /** More than [com.dazix.caelis.domain.horizon.LandmarkVisibility.DEFAULT_MAX_LABELS] with room to spare. */
        const val MAX_LANDMARKS = 16

        /** Two days of bubbles, four badges and the peaks, with room to spare. */
        private const val MAX_RESERVED = 256

        private const val MAX_LABEL_ATLAS_FRACTION = 0.6f
        private const val MAX_LABEL_DP = 260f
        private const val TEXT_SP = 10f
        private const val TICK_DP = 7f
        private const val TICK_WIDTH_DP = 1.2f
        private const val SHADOW_DP = 2f
        private const val CASING_ARGB = 0xB3000000.toInt()
    }
}

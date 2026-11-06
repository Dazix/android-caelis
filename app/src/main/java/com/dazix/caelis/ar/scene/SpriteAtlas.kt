package com.dazix.caelis.ar.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.dazix.caelis.domain.arframe.AtlasPacker
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.android.TextureHelper
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Labels and badges pre-rendered with Android's own text stack into one texture, so the GPU can draw
 * them in the same frame as the stars ([LabelBatch]).
 *
 * They were a Compose overlay once. On a Pixel 7 Pro the TextureView showed a Filament frame about
 * 20 ms (2-3 vsyncs) after it was rendered, while the overlay drew each vsync with the newest pose,
 * so held in the hand the names jumped against the stars. Filament has no text; a texture of
 * Canvas-drawn sprites keeps Skia's shaping, fonts, emoji and shadows and moves the drawing into the
 * one frame that cannot disagree with itself.
 *
 * Each sprite is drawn in its final colours (the paint's, themed on the CPU like `line` and `disc`),
 * at the size it is shown, so it is sampled 1:1. Its *anchor* is the point that sits on the sky
 * position: for text, the baseline at the paint's alignment, which is where `drawText(x, y)` put it.
 *
 * The atlas is rebuilt only when its content changes (catalogue, theme, density, locale,
 * selection), between [begin] and [end]; drawing a frame never touches it.
 */
class SpriteAtlas(
    private val engine: Engine,
    val width: Int,
    val height: Int,
    val maxSprites: Int,
) {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val packer = AtlasPacker(width, height, GUTTER)
    private val placed = IntArray(2)
    private val fontMetrics = Paint.FontMetrics()
    private var uploadInFlight = false

    val texture: Texture = Texture.Builder()
        .width(width)
        .height(height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .build(engine)

    /** Sprite size in pixels. */
    val spriteWidth = FloatArray(maxSprites)
    val spriteHeight = FloatArray(maxSprites)

    /** Where the anchor sits inside the sprite, pixels from its top-left. */
    val anchorX = FloatArray(maxSprites)
    val anchorY = FloatArray(maxSprites)

    /** Texture coordinates of the sprite, top-left (u0, v0) and bottom-right (u1, v1). */
    val u0 = FloatArray(maxSprites)
    val v0 = FloatArray(maxSprites)
    val u1 = FloatArray(maxSprites)
    val v1 = FloatArray(maxSprites)

    var spriteCount = 0
        private set

    /** Draws one sprite whose top-left corner is at ([left], [top]) of [canvas]. */
    fun interface SpriteDrawer {
        fun draw(canvas: Canvas, left: Float, top: Float)
    }

    /** Starts a rebuild: every sprite is forgotten and the bitmap cleared. */
    fun begin() {
        // The previous upload reads the bitmap on Filament's thread; it must be done before the
        // bitmap is drawn into again. Rebuilds are rare (a theme or a selection), so waiting is cheap.
        if (uploadInFlight) {
            engine.flushAndWait()
            uploadInFlight = false
        }
        bitmap.eraseColor(0)
        packer.reset()
        spriteCount = 0
    }

    /**
     * One line of text drawn with [paint] (its colour, size, typeface, alignment and shadow). The
     * anchor is the baseline at the paint's alignment. Returns the sprite index, or -1 when the atlas
     * is full.
     */
    fun addText(text: String, paint: Paint): Int = addText(text, paint, 0)

    /**
     * As [addText], on a rounded backdrop of [backdropArgb] (0 for none) that reaches a little past
     * the letters, so a name stays readable where it lands on a bright star or artwork. The backdrop
     * is part of the sprite and fades with it.
     */
    fun addText(text: String, paint: Paint, backdropArgb: Int): Int {
        paint.getFontMetrics(fontMetrics)
        val shadowPad = ceil(paint.shadowLayerRadius + maxOf(abs(paint.shadowLayerDx), abs(paint.shadowLayerDy))).toInt() + 1
        val backdrop = backdropArgb != 0
        val padX = shadowPad + if (backdrop) ceil(paint.textSize * BACKDROP_PAD_X).toInt() else 0
        val padY = shadowPad + if (backdrop) ceil(paint.textSize * BACKDROP_PAD_Y).toInt() else 0
        val textWidth = paint.measureText(text)
        val w = ceil(textWidth).toInt() + 2 * padX
        val h = ceil(fontMetrics.bottom - fontMetrics.top).toInt() + 2 * padY
        val ax = padX + when (paint.textAlign) {
            Paint.Align.CENTER -> textWidth * 0.5f
            Paint.Align.RIGHT -> textWidth
            else -> 0f
        }
        val ay = padY - fontMetrics.top
        if (!backdrop) return add(w, h, ax, ay) { c, left, top -> c.drawText(text, left + ax, top + ay, paint) }
        // The pill hugs the letters (ascent to descent), not the font's whole line box, and stays
        // inside the shadow's margin so its edge is not clipped by the sprite.
        val ascent = fontMetrics.ascent
        val descent = fontMetrics.descent
        val textSize = paint.textSize
        return add(w, h, ax, ay) { c, left, top ->
            backdropPaint.color = backdropArgb
            val bx = textSize * BACKDROP_PAD_X
            val by = textSize * BACKDROP_PAD_Y
            backdropRect.set(
                left + shadowPad,
                top + ay + ascent - by,
                left + w - shadowPad,
                top + ay + descent + by,
            )
            val radius = backdropRect.height() * 0.5f
            c.drawRoundRect(backdropRect, radius, radius, backdropPaint)
            c.drawText(text, left + ax, top + ay, paint)
        }
    }

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropRect = android.graphics.RectF()

    /**
     * A sprite of [w] x [h] pixels drawn by [drawer], anchored at ([anchorX], [anchorY]) inside it.
     * Returns the sprite index, or -1 when the atlas is full.
     */
    fun add(w: Int, h: Int, anchorX: Float, anchorY: Float, drawer: SpriteDrawer): Int {
        if (spriteCount >= maxSprites || !packer.place(w, h, placed)) return -1
        val i = spriteCount++
        val left = placed[0].toFloat()
        val top = placed[1].toFloat()
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)
        drawer.draw(canvas, left, top)
        canvas.restore()
        spriteWidth[i] = w.toFloat()
        spriteHeight[i] = h.toFloat()
        this.anchorX[i] = anchorX
        this.anchorY[i] = anchorY
        u0[i] = left / width
        u1[i] = (left + w) / width
        // The materials keep matc's default flipUV, which flips V: v = 1 is the bitmap's first row.
        v0[i] = 1f - top / height
        v1[i] = 1f - (top + h) / height
        return i
    }

    /** Uploads what was drawn since [begin]. */
    fun end() {
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        uploadInFlight = true
    }

    fun destroy() {
        engine.destroyTexture(texture)
        bitmap.recycle()
    }

    private companion object {
        /** Empty pixels around each sprite, so linear filtering never reaches a neighbour. */
        const val GUTTER = 2

        /** A text backdrop's reach past the letters, as fractions of the text size. */
        const val BACKDROP_PAD_X = 0.4f
        const val BACKDROP_PAD_Y = 0.12f
    }
}

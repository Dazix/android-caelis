package com.dazix.caelis.domain.solar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one rise/set icon of the app: the body over the horizon, an arrow above it pointing up for a
 * rise and down for a set.
 *
 * Sun AR's pills used to start with an emoji (🌅 🌇 🌙 🌘). At pill size the sunrise and the sunset
 * emoji were the same orange blur, and the moon ones named a phase, not a rise or a set; which pill
 * was which could only be read from the time. The arrow is what says it, and it stands above the
 * body, never through the horizon, where at 16 dp it merged with the line.
 *
 * It is geometry only, in a [SIZE] x [SIZE] box (the `AppIcons` grid, y down): [strokes] to be
 * stroked [STROKE] wide with round caps and joins, and [fills], closed outlines to be filled. Two
 * renderers draw it: the pills, on an Android `Canvas` into Sun AR's sprite atlas, and
 * `AppIcons.Sunrise` / `Sunset` in Compose. Arcs are sampled here rather than left to each renderer's
 * `arcTo`, which take different arguments, so the two cannot drift apart.
 */
object RiseSetGlyph {

    enum class Body { SUN, MOON }

    /** One variant of the icon. The arrays are shared: never modify them. */
    class Drawing(val strokes: List<FloatArray>, val fills: List<FloatArray>)

    const val SIZE = 24f
    const val STROKE = 2.2f

    /** The horizon line's y; nothing of the body reaches below it. */
    const val HORIZON_Y = 19f

    private const val CENTER_X = 12f
    private const val HORIZON_START_X = 2.5f
    private const val HORIZON_END_X = 21.5f

    private const val SUN_RADIUS = 4.6f
    private const val RAY_INNER = 6.6f
    private const val RAY_OUTER = 8.6f

    /**
     * None straight up: that place is the arrow's. None lower than 25° either: at 10° a ray ran into
     * the horizon's stroke and the pair read as a bump on the line.
     */
    private val RAY_ANGLES_DEG = floatArrayOf(25f, 60f, 120f, 155f)

    // The moon is a filled crescent floating just above the horizon. Stroked, a crescent small enough
    // to fit under the arrow closed up into a blob; cut by the horizon like the sun, it lost its lower
    // horn and read as a fin or a wave. Whole and solid, it reads as the moon even at pill size. Under
    // the arrow it was still too small to see in a pill, so it stands left of centre and the arrow
    // beside it, to the right, where both have the box's whole height.
    private const val MOON_CENTER_X = 9f
    private const val MOON_CENTER_Y = 11.6f
    private const val MOON_RADIUS = 5.4f
    private const val MOON_INNER_RADIUS = 4.4f
    private const val MOON_INNER_DX = 2.4f
    private const val MOON_INNER_DY = -1.3f

    private const val ARROW_TOP_Y = 1.8f
    private const val ARROW_BOTTOM_Y = 6.6f
    private const val ARROW_HEAD = 2.1f

    private const val MOON_ARROW_X = 18.6f
    private const val MOON_ARROW_TOP_Y = 3f
    private const val MOON_ARROW_BOTTOM_Y = 15.4f
    private const val MOON_ARROW_HEAD = 2.6f

    private const val ARC_SEGMENTS = 16

    private val cache = arrayOfNulls<Drawing>(4)

    /** The icon for [body] rising or setting. Built once per variant. */
    fun drawing(body: Body, rising: Boolean): Drawing {
        val index = body.ordinal * 2 + if (rising) 1 else 0
        return cache[index] ?: build(body, rising).also { cache[index] = it }
    }

    private val bodyCache = arrayOfNulls<Drawing>(2)

    /**
     * The body alone, centred in the box, with no horizon and no arrow: the whole sun (disc and eight
     * rays, the rise/set icon's radii) or the moon's crescent. For a heading that names the body, so
     * it looks like the body on the pills.
     */
    fun body(body: Body): Drawing =
        bodyCache[body.ordinal] ?: buildBody(body).also { bodyCache[body.ordinal] = it }

    private fun buildBody(body: Body): Drawing {
        val c = SIZE / 2
        return when (body) {
            Body.SUN -> {
                val strokes = ArrayList<FloatArray>()
                strokes += arc(c, c, SUN_RADIUS, 0.0, 2 * PI)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    strokes += floatArrayOf(c + dx * RAY_INNER, c + dy * RAY_INNER, c + dx * RAY_OUTER, c + dy * RAY_OUTER)
                }
                Drawing(strokes, emptyList())
            }
            // The crescent's ink sits left of its disc's centre; the disc is nudged right so it looks centred.
            Body.MOON -> Drawing(emptyList(), listOf(crescentOutline(c + 1f, c, BODY_MOON_RADIUS)))
        }
    }

    private const val BODY_MOON_RADIUS = 8.4f

    /** How many of a drawing's strokes are rays (the sun has them, the moon does not). */
    fun rayCount(body: Body): Int = if (body == Body.SUN) RAY_ANGLES_DEG.size else 0

    /**
     * Strokes, each as `x0, y0, x1, y1, …`: the horizon first, then the sun's disc and rays, then the
     * arrow's shaft and head last.
     */
    private fun build(body: Body, rising: Boolean): Drawing {
        val strokes = ArrayList<FloatArray>()
        val fills = ArrayList<FloatArray>()
        strokes += floatArrayOf(HORIZON_START_X, HORIZON_Y, HORIZON_END_X, HORIZON_Y)
        when (body) {
            Body.SUN -> {
                // The upper half of the disc, left to right through the top.
                strokes += arc(CENTER_X, HORIZON_Y, SUN_RADIUS, PI, 2 * PI)
                for (deg in RAY_ANGLES_DEG) {
                    val a = Math.toRadians(deg.toDouble())
                    val dx = cos(a).toFloat()
                    val dy = -sin(a).toFloat()
                    strokes += floatArrayOf(
                        CENTER_X + dx * RAY_INNER, HORIZON_Y + dy * RAY_INNER,
                        CENTER_X + dx * RAY_OUTER, HORIZON_Y + dy * RAY_OUTER,
                    )
                }
            }
            Body.MOON -> fills += crescent()
        }
        val moon = body == Body.MOON
        val x = if (moon) MOON_ARROW_X else CENTER_X
        val top = if (moon) MOON_ARROW_TOP_Y else ARROW_TOP_Y
        val bottom = if (moon) MOON_ARROW_BOTTOM_Y else ARROW_BOTTOM_Y
        val head = if (moon) MOON_ARROW_HEAD else ARROW_HEAD
        val headY = if (rising) top else bottom
        val armY = if (rising) headY + head else headY - head
        strokes += floatArrayOf(x, bottom, x, top)
        strokes += floatArrayOf(
            x - head, armY,
            x, headY,
            x + head, armY,
        )
        return Drawing(strokes, fills)
    }

    private fun crescent(): FloatArray = crescentOutline(MOON_CENTER_X, MOON_CENTER_Y, MOON_RADIUS)

    /**
     * The moon's crescent around the disc ([cx], [cy], [radius]), opening to the upper right: the
     * disc's edge away from the hollow, then the hollow's edge inside the disc back to the start. One
     * closed outline (last point = first point), to be filled. The same shape as in the rise/set icon
     * at any size, so Sun AR's off-screen moon pointer draws the moon the pills show.
     */
    fun crescentOutline(cx: Float, cy: Float, radius: Float): FloatArray {
        val scale = radius / MOON_RADIUS
        val ox = cx
        val oy = cy
        val r = radius
        val innerR = MOON_INNER_RADIUS * scale
        val ix = ox + MOON_INNER_DX * scale
        val iy = oy + MOON_INNER_DY * scale
        // The two horns: where the disc's edge meets the hollow's.
        val dx = ix - ox
        val dy = iy - oy
        val d = sqrt(dx * dx + dy * dy)
        val along = (d * d + r * r - innerR * innerR) / (2 * d)
        val half = sqrt(r * r - along * along)
        val mx = ox + dx * along / d
        val my = oy + dy * along / d
        val px = mx - dy * half / d
        val py = my + dx * half / d
        val qx = mx + dy * half / d
        val qy = my - dx * half / d

        val outer = arcBetween(ox, oy, r, px, py, qx, qy, towardX = ix, towardY = iy, containToward = false)
        val inner = arcBetween(ix, iy, innerR, qx, qy, px, py, towardX = ox, towardY = oy, containToward = true)
        // The inner arc starts where the outer one ended; skip its duplicate first point.
        return outer + inner.copyOfRange(2, inner.size)
    }

    /**
     * The arc of the circle ([cx], [cy], [r]) from one point on it to another, on the side that does
     * ([containToward]) or does not contain the direction towards ([towardX], [towardY]).
     */
    private fun arcBetween(
        cx: Float, cy: Float, r: Float,
        fromX: Float, fromY: Float, toX: Float, toY: Float,
        towardX: Float, towardY: Float, containToward: Boolean,
    ): FloatArray {
        val start = atan2((fromY - cy).toDouble(), (fromX - cx).toDouble())
        val end = atan2((toY - cy).toDouble(), (toX - cx).toDouble())
        val toward = atan2((towardY - cy).toDouble(), (towardX - cx).toDouble())
        val sweepPositive = positive(end - start)
        val towardInPositive = positive(toward - start) < sweepPositive
        val sweep = if (towardInPositive == containToward) sweepPositive else sweepPositive - 2 * PI
        return arc(cx, cy, r, start, start + sweep)
    }

    /** [ARC_SEGMENTS] segments of a circle from angle [from] to [to] (radians, y down). */
    private fun arc(cx: Float, cy: Float, r: Float, from: Double, to: Double): FloatArray {
        val out = FloatArray((ARC_SEGMENTS + 1) * 2)
        for (i in 0..ARC_SEGMENTS) {
            val a = from + (to - from) * i / ARC_SEGMENTS
            out[i * 2] = cx + r * cos(a).toFloat()
            out[i * 2 + 1] = cy + r * sin(a).toFloat()
        }
        return out
    }

    private fun positive(angle: Double): Double {
        val m = angle % (2 * PI)
        return if (m < 0) m + 2 * PI else m
    }
}

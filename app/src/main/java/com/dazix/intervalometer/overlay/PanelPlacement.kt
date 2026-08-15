package com.dazix.intervalometer.overlay

import kotlin.math.max
import kotlin.math.min

/**
 * Framework-free integer rectangle in display pixels. Deliberately not [android.graphics.Rect]: the
 * placement maths below is the part that has to be provable, and a JVM unit test cannot construct an
 * `android.graphics` object.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersects(other: Box): Boolean =
        !isEmpty && !other.isEmpty &&
            left < other.right && other.left < right &&
            top < other.bottom && other.top < bottom

    fun overlapArea(other: Box): Long {
        if (!intersects(other)) return 0L
        val w = (min(right, other.right) - max(left, other.left)).toLong()
        val h = (min(bottom, other.bottom) - max(top, other.top)).toLong()
        return w * h
    }

    fun inflated(by: Int): Box = Box(left - by, top - by, right + by, bottom + by)

    companion object {
        fun of(x: Int, y: Int, width: Int, height: Int): Box = Box(x, y, x + width, y + height)
    }
}

/** Top-left corner of the panel window, in display pixels. */
data class PanelPosition(val x: Int, val y: Int)

/**
 * Decides where the floating panel may sit.
 *
 * The panel is a touchable `TYPE_APPLICATION_OVERLAY` window and
 * `AccessibilityService.dispatchGesture` injects at input-dispatcher level, so a synthetic shutter
 * tap whose coordinates fall inside the panel is delivered to the panel — possibly to its own
 * Pause/Stop buttons — and never reaches the camera app. The only way to rule that out with no
 * timing involved at all is to never *place* the panel over a pixel a synthetic tap can target.
 *
 * [resolve] returns the position closest to what the user asked for that overlaps none of [avoid],
 * which the caller fills with the calibrated target (inflated by a margin) and the bottom-centre
 * region the node-search heuristic falls back to.
 *
 * ### Why the candidate lattice is exhaustive
 * For one forbidden box `A`, panel origins that collide with it are exactly
 * `x ∈ (A.left - panelWidth, A.right)` and `y ∈ (A.top - panelHeight, A.bottom)`. Escaping `A`
 * therefore means moving x to `A.left - panelWidth` or `A.right`, or y to `A.top - panelHeight` or
 * `A.bottom` — nothing between those values helps. Any optimal placement can be slid back towards the
 * desired position until it touches one of those bounds or reaches the desired coordinate itself, so
 * the optimum always lies on the lattice built from {desired, screen edges, per-box escape bounds}.
 * Scoring every lattice point is a few dozen comparisons and cannot oscillate or fail to terminate.
 */
object PanelPlacement {

    /**
     * @param safe the area the panel must stay inside, in the same raw-display pixels as everything
     *   else here. The panel's window spans the whole display (`FLAG_LAYOUT_NO_LIMITS`), because that is
     *   what makes its coordinates the same ones a dispatched gesture is aimed at — which also means
     *   nothing stops it from being placed under the status bar or behind the navigation pill, where the
     *   system eats its touches. Passing the system-bar insets here keeps the two apart: raw space for
     *   the arithmetic, safe area for where the panel may actually land. `null` means the whole display,
     *   which is what the unit tests use.
     */
    fun resolve(
        desiredX: Int,
        desiredY: Int,
        panelWidth: Int,
        panelHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        avoid: List<Box>,
        safe: Box? = null,
    ): PanelPosition {
        // An unusable safe area (a panel taller than the space between the bars, a nonsense inset) is
        // ignored rather than obeyed: clipping the panel to nothing would lose Stop altogether.
        val usable = safe?.takeIf {
            it.right - it.left >= panelWidth && it.bottom - it.top >= panelHeight
        }
        val minX = usable?.left?.coerceAtLeast(0) ?: 0
        val minY = usable?.top?.coerceAtLeast(0) ?: 0
        val maxX = ((usable?.right ?: screenWidth) - panelWidth).coerceIn(minX, maxOf(minX, screenWidth))
        val maxY = ((usable?.bottom ?: screenHeight) - panelHeight).coerceIn(minY, maxOf(minY, screenHeight))
        val wantX = desiredX.coerceIn(minX, maxX)
        val wantY = desiredY.coerceIn(minY, maxY)
        val blocking = avoid.filterNot { it.isEmpty }
        if (blocking.isEmpty() || panelWidth <= 0 || panelHeight <= 0) {
            return PanelPosition(wantX, wantY)
        }

        val xs = candidateCoordinates(
            wantX, minX, maxX, blocking.flatMap { listOf(it.left - panelWidth, it.right) },
        )
        val ys = candidateCoordinates(
            wantY, minY, maxY, blocking.flatMap { listOf(it.top - panelHeight, it.bottom) },
        )

        var bestX = wantX
        var bestY = wantY
        var bestOverlap = Long.MAX_VALUE
        var bestDistance = Long.MAX_VALUE
        for (y in ys) {
            for (x in xs) {
                val panel = Box.of(x, y, panelWidth, panelHeight)
                var overlap = 0L
                for (box in blocking) overlap += panel.overlapArea(box)
                val dx = (x - wantX).toLong()
                val dy = (y - wantY).toLong()
                val distance = dx * dx + dy * dy
                // Strictly better only, so equal scores keep the earlier (lower) coordinate and the
                // result is a pure function of the inputs.
                if (overlap < bestOverlap || (overlap == bestOverlap && distance < bestDistance)) {
                    bestOverlap = overlap
                    bestDistance = distance
                    bestX = x
                    bestY = y
                }
            }
        }
        return PanelPosition(bestX, bestY)
    }

    /** True when a panel of this size at this position clears every box; used by tests and callers. */
    fun isClear(
        position: PanelPosition,
        panelWidth: Int,
        panelHeight: Int,
        avoid: List<Box>,
    ): Boolean {
        val panel = Box.of(position.x, position.y, panelWidth, panelHeight)
        return avoid.none { panel.intersects(it) }
    }

    private fun candidateCoordinates(
        desired: Int,
        minValue: Int,
        maxValue: Int,
        escapes: List<Int>,
    ): List<Int> =
        (escapes + listOf(desired, minValue, maxValue))
            .map { it.coerceIn(minValue, maxValue) }
            .distinct()
            .sorted()
}

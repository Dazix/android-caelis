package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.domain.arframe.SkyProjector

/**
 * Which constellations are "in focus" and how far each one's figure has grown (0..1).
 *
 * Moved out of the Canvas renderer unchanged in behaviour, so the GPU scene and the Compose label
 * overlay read one state:
 * - A constellation locks on when its centroid enters a circle of `w * m * 45/48` around the screen
 *   centre and lets go only when it leaves `w * m * 55/48` (m = focus radius multiplier, 0.48 by
 *   default). The ring between the two absorbs hand tremor that would otherwise flicker the figure.
 * - The selected constellation is always focused, even behind the camera.
 * - Progress grows at 2/s and dissolves at 3/s; dt is clamped to 1..100 ms so a dropped frame or a
 *   resume from background does not jump a figure to fully drawn.
 *
 * Zero allocations; arrays are sized once to the catalogue's constellation count.
 */
class ConstellationFocusState(val capacity: Int) {

    val progress = FloatArray(capacity)
    val focused = BooleanArray(capacity)
    val centroidInFront = BooleanArray(capacity)
    val centroidScreenX = FloatArray(capacity)
    val centroidScreenY = FloatArray(capacity)

    private val projected = FloatArray(3)

    /**
     * @param centroidCel celestial unit vectors of the centroids, packed x, y, z per constellation.
     */
    fun update(
        count: Int,
        centroidCel: FloatArray,
        celestialToEnu16: FloatArray,
        projector: SkyProjector,
        selectedIndex: Int,
        dtSeconds: Float,
        focusRadiusMultiplier: Float = DEFAULT_FOCUS_RADIUS_MULTIPLIER,
    ) {
        val n = minOf(count, capacity)
        val w = projector.viewportWidth
        val halfW = w * 0.5f
        val halfH = projector.viewportHeight * 0.5f
        val radiusIn = w * (focusRadiusMultiplier * (45f / 48f))
        val radiusOut = w * (focusRadiusMultiplier * (55f / 48f))
        val radiusInSq = radiusIn * radiusIn
        val radiusOutSq = radiusOut * radiusOut
        val dt = dtSeconds.coerceIn(0.001f, 0.1f)

        for (c in 0 until n) {
            val isSelected = c == selectedIndex
            val inFront = projector.projectCelestial(
                celestialToEnu16, centroidCel[c * 3], centroidCel[c * 3 + 1], centroidCel[c * 3 + 2], projected,
            )
            centroidInFront[c] = inFront
            if (inFront) {
                centroidScreenX[c] = projected[0]
                centroidScreenY[c] = projected[1]
                if (isSelected) {
                    focused[c] = true
                } else {
                    val dx = projected[0] - halfW
                    val dy = projected[1] - halfH
                    val distSq = dx * dx + dy * dy
                    if (!focused[c] && distSq <= radiusInSq) {
                        focused[c] = true
                    } else if (focused[c] && distSq > radiusOutSq) {
                        focused[c] = false
                    }
                }
            } else {
                focused[c] = isSelected
            }

            val step = dt * GROW_PER_SECOND
            val p = if (focused[c]) progress[c] + step else progress[c] - step * DISSOLVE_FACTOR
            progress[c] = p.coerceIn(0f, 1f)
        }
    }

    fun reset() {
        progress.fill(0f)
        focused.fill(false)
        centroidInFront.fill(false)
    }

    companion object {
        const val DEFAULT_FOCUS_RADIUS_MULTIPLIER = 0.48f
        const val GROW_PER_SECOND = 2.0f
        /** Dissolve slightly faster than build-up. */
        const val DISSOLVE_FACTOR = 1.5f
    }
}

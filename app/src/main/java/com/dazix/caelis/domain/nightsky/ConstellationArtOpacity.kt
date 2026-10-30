package com.dazix.caelis.domain.nightsky

/**
 * How strongly the constellation artwork is drawn: the quad's opacity at a fully grown figure, as a
 * fraction of opaque. The default is the alpha 90 the artwork was always drawn with, so a user who
 * never touches the slider sees no change.
 *
 * The floor is not zero on purpose: switching the artwork off is the art switch's job, and a
 * slider pulled to its end that hid the pictures while the switch said "on" would look broken.
 */
object ConstellationArtOpacity {
    const val DEFAULT = 90f / 255f
    const val MIN = 0.1f
    const val MAX = 1f

    /** Into [MIN]..[MAX]; NaN (a corrupt stored value) falls back to [DEFAULT]. */
    fun clamp(value: Float): Float = if (value.isNaN()) DEFAULT else value.coerceIn(MIN, MAX)

    /** The artwork quad's opacity for a figure at focus [progress] (0..1). */
    fun quadOpacity(opacity: Float, progress: Float): Float = (clamp(opacity) * progress).coerceIn(0f, 1f)
}

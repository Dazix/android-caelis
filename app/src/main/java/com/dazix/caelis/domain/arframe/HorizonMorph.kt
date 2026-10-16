package com.dazix.caelis.domain.arframe

/**
 * The transition of the drawn skyline from one shape to the next.
 *
 * Until a terrain profile arrives the night sky draws a flat ring at 0°; when the profile lands (or
 * a new one replaces it after a move) the ridge rises into place over [durationSeconds] instead of
 * popping in. The shape being left is captured by the caller with [blend] at the moment of the
 * change, so a profile arriving mid-transition continues from what is on screen, not from flat.
 */
class HorizonMorph(private val durationSeconds: Float = DEFAULT_DURATION_SECONDS) {

    /** Linear progress 0..1; 1 means the target shape is fully shown. */
    var progress: Float = 1f
        private set

    /** Progress with ease-in-out (smoothstep), what the renderer interpolates with. */
    val eased: Float get() = progress * progress * (3f - 2f * progress)

    val isRunning: Boolean get() = progress < 1f

    /** Starts a new transition from the shape currently shown. */
    fun restart() {
        progress = 0f
    }

    /** Jumps to the target, for a profile that is ready before anything has been drawn. */
    fun finish() {
        progress = 1f
    }

    /** Advances by one frame; returns true when [eased] changed. */
    fun advance(dtSeconds: Float): Boolean {
        if (!isRunning) return false
        val step = if (durationSeconds <= 0f) 1f else dtSeconds.coerceAtLeast(0f) / durationSeconds
        progress = (progress + step).coerceAtMost(1f)
        return true
    }

    /** The value currently shown between [from] and [to]. */
    fun blend(from: Float, to: Float): Float = from + (to - from) * eased

    companion object {
        const val DEFAULT_DURATION_SECONDS = 0.8f
    }
}

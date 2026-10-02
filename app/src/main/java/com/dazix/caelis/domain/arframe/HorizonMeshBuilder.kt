package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.horizon.HorizonProfile
import kotlin.math.cos
import kotlin.math.sin

/**
 * The terrain horizon as geometry: a ring of quads whose top edge follows the profile and whose
 * bottom edge hangs to the nadir, so it hides everything below the skyline by depth.
 *
 * Why geometry and not a texture strip: the AGSL landscape once read the profile strip with the
 * panorama's texture coordinate and drew every ridge half a turn out — and it looked plausible,
 * because a horizon is a horizon whichever way you face. Here sample `i` becomes the vertex at
 * azimuth `i * 0.25 deg` directly; north is sample 0 by construction, with no coordinate to mix up.
 *
 * Vertices carry `(azimuthDeg, elevationDeg, u, v)`; the alignment offsets are applied by
 * [directionOf] (mirrored in `landscape.mat`) so a drag or an auto-alignment does not rebuild the
 * mesh. The offsets follow `StarHitTester.isOccludedByLandscape`: a sky point at azimuth `a` and
 * elevation `e` is behind terrain when `e + elevationOffset < profile(a + azimuthOffset)`, so the
 * profile sample at azimuth `A` is drawn at `A - azimuthOffset`, lowered by `elevationOffset`.
 *
 * `u` is the panorama coordinate, whose north is in the middle of the image (`az / 360 + 0.5`);
 * `v` is 0 on the skyline and 1 at the nadir.
 */
object HorizonMeshBuilder {

    const val FLOATS_PER_VERTEX = 4
    private const val SAMPLES = HorizonProfile.SAMPLE_COUNT

    /** Vertices: two per sample plus a duplicated closing column so `u` does not wrap mid-quad. */
    const val VERTEX_COUNT = (SAMPLES + 1) * 2
    const val INDEX_COUNT = SAMPLES * 6

    /**
     * Fills [out] (size `VERTEX_COUNT * FLOATS_PER_VERTEX`) from a profile, or a flat 0 deg ring for
     * the stylised landscape when [profile] is null or not usable (below MIN_USABLE_SAMPLES the
     * repository refuses to draw it, and so does this).
     */
    fun buildVertices(profile: HorizonProfile?, out: FloatArray) {
        require(out.size >= VERTEX_COUNT * FLOATS_PER_VERTEX) { "vertex array too small" }
        val elevations = profile?.takeIf { it.isUsable }?.elevationsDegrees
        var o = 0
        for (i in 0..SAMPLES) {
            val sample = i % SAMPLES
            val azimuth = (i * HorizonProfile.STEP_DEGREES).toFloat()
            val elevation = elevations?.get(sample) ?: 0f
            val u = azimuth / 360f + 0.5f
            out[o++] = azimuth; out[o++] = elevation; out[o++] = u; out[o++] = 0f
            out[o++] = azimuth; out[o++] = NADIR; out[o++] = u; out[o++] = 1f
        }
    }

    fun buildIndices(out: IntArray) {
        require(out.size >= INDEX_COUNT) { "index array too small" }
        var o = 0
        for (i in 0 until SAMPLES) {
            val top = i * 2
            val bottom = top + 1
            val nextTop = top + 2
            val nextBottom = top + 3
            out[o++] = top; out[o++] = bottom; out[o++] = nextTop
            out[o++] = nextTop; out[o++] = bottom; out[o++] = nextBottom
        }
    }

    /**
     * The ENU unit direction of a mesh vertex after alignment — the exact function `landscape.mat`
     * evaluates per vertex.
     */
    fun directionOf(
        azimuthDeg: Float,
        elevationDeg: Float,
        azimuthOffsetDeg: Float,
        elevationOffsetDeg: Float,
        out3: FloatArray,
    ) {
        val az = Math.toRadians((azimuthDeg - azimuthOffsetDeg).toDouble())
        // The nadir stays the nadir whatever the offset; only the skyline moves.
        val elDeg = if (elevationDeg <= NADIR) NADIR else elevationDeg - elevationOffsetDeg
        val el = Math.toRadians(elDeg.toDouble().coerceIn(-90.0, 90.0))
        out3[0] = (sin(az) * cos(el)).toFloat()
        out3[1] = (cos(az) * cos(el)).toFloat()
        out3[2] = sin(el).toFloat()
    }

    const val NADIR = -90f
}

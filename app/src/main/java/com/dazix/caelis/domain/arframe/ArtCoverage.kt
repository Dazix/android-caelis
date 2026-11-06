package com.dazix.caelis.domain.arframe

/**
 * Where on the sky a constellation drawing actually has paint, rather than where its quad lies.
 *
 * A peak name is faded where it crosses a drawing. Judged by the quad's screen rectangle, a name
 * next to a figure was faded too (Ještěd beside the Fishes): a drawing is a figure on a transparent
 * picture, and its corners are mostly empty. This maps a sky direction to the picture through the
 * quad exactly as the GPU does (two triangles, corners top-left, top-right, bottom-right,
 * bottom-left, positions interpolated linearly) and reads a coarse mask of the picture's alpha.
 */
class ArtCoverage {

    /**
     * Writes the picture coordinates (u right, v down from the picture's top, 0..1) where the ray
     * from the origin along ([dx], [dy], [dz]) meets the quad of the four unit-vector [corners] (12
     * floats from [offset]: top-left, top-right, bottom-right, bottom-left) into [outUv] and returns
     * true, or returns false when it misses the quad.
     */
    fun uvOnQuad(dx: Float, dy: Float, dz: Float, corners: FloatArray, offset: Int, outUv: FloatArray): Boolean {
        // Triangle (TL, TR, BR) carries u = (0, 1, 1), v = (0, 0, 1); (TL, BR, BL) u = (0, 1, 0), v = (0, 1, 1).
        if (hit(dx, dy, dz, corners, offset, 0, 1, 2)) {
            outUv[0] = b1 + b2
            outUv[1] = b2
            return true
        }
        if (hit(dx, dy, dz, corners, offset, 0, 2, 3)) {
            outUv[0] = b1
            outUv[1] = b1 + b2
            return true
        }
        return false
    }

    // Barycentric weights of the second and third vertex from the last successful hit.
    private var b1 = 0f
    private var b2 = 0f

    /** Möller–Trumbore from the origin. */
    private fun hit(dx: Float, dy: Float, dz: Float, c: FloatArray, o: Int, i0: Int, i1: Int, i2: Int): Boolean {
        val ax = c[o + i0 * 3]; val ay = c[o + i0 * 3 + 1]; val az = c[o + i0 * 3 + 2]
        val e1x = c[o + i1 * 3] - ax; val e1y = c[o + i1 * 3 + 1] - ay; val e1z = c[o + i1 * 3 + 2] - az
        val e2x = c[o + i2 * 3] - ax; val e2y = c[o + i2 * 3 + 1] - ay; val e2z = c[o + i2 * 3 + 2] - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -1e-9f && det < 1e-9f) return false
        val inv = 1f / det
        // Ray origin is (0, 0, 0): s = origin - a.
        val sx = -ax; val sy = -ay; val sz = -az
        val u = (sx * px + sy * py + sz * pz) * inv
        if (u < 0f || u > 1f) return false
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0f || u + v > 1f) return false
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        if (t <= 0f) return false
        b1 = u
        b2 = v
        return true
    }

    companion object {
        /** Mask cells per side. A cell of a 300 px drawing is ~10 px on screen: a letter's height. */
        const val MASK_SIZE = 32

        /** Whether the mask cell at picture coordinates ([u], [v]) has paint. */
        fun covers(mask: BooleanArray, u: Float, v: Float): Boolean {
            if (u < 0f || u > 1f || v < 0f || v > 1f) return false
            val col = (u * MASK_SIZE).toInt().coerceAtMost(MASK_SIZE - 1)
            val row = (v * MASK_SIZE).toInt().coerceAtMost(MASK_SIZE - 1)
            return mask[row * MASK_SIZE + col]
        }
    }
}

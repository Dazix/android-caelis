package com.dazix.caelis.domain.arframe

import kotlin.math.sqrt

/**
 * Corners of an image laid on the sky around a direction, for `TexturedQuad`.
 *
 * The Canvas renderer drew a planet as a bitmap turned on screen so its top pointed at the
 * celestial pole (`computeCelestialNorthAngle`). On the GPU the same picture is four directions
 * in the tangent plane of the body: "up" is the pole direction with its radial part removed, and
 * "right" is `direction x up` — which, seen from inside the sphere, is where a screen's right edge
 * is when up is up. Extents are in tangent units (pixels / focal length), so the image keeps its
 * size in pixels at the centre of the view, as the Canvas bitmap did.
 *
 * Pure Kotlin, no allocations.
 */
object SkyQuadMath {

    /**
     * Writes top-left, top-right, bottom-right, bottom-left (x, y, z each) into [out12].
     *
     * @return false when [up] is parallel to the direction (a body exactly at the pole), in which
     *   case nothing is written.
     */
    fun orientedCorners(
        dx: Float, dy: Float, dz: Float,
        upX: Float, upY: Float, upZ: Float,
        halfWidth: Float,
        halfHeight: Float,
        out12: FloatArray,
    ): Boolean {
        val dot = upX * dx + upY * dy + upZ * dz
        var ux = upX - dx * dot
        var uy = upY - dy * dot
        var uz = upZ - dz * dot
        val len = sqrt(ux * ux + uy * uy + uz * uz)
        if (len < 1e-6f) return false
        ux /= len; uy /= len; uz /= len
        // right = d x up
        val rx = dy * uz - dz * uy
        val ry = dz * ux - dx * uz
        val rz = dx * uy - dy * ux
        corner(out12, 0, dx, dy, dz, rx, ry, rz, ux, uy, uz, -halfWidth, halfHeight)
        corner(out12, 1, dx, dy, dz, rx, ry, rz, ux, uy, uz, halfWidth, halfHeight)
        corner(out12, 2, dx, dy, dz, rx, ry, rz, ux, uy, uz, halfWidth, -halfHeight)
        corner(out12, 3, dx, dy, dz, rx, ry, rz, ux, uy, uz, -halfWidth, -halfHeight)
        return true
    }

    private fun corner(
        out: FloatArray, index: Int,
        dx: Float, dy: Float, dz: Float,
        rx: Float, ry: Float, rz: Float,
        ux: Float, uy: Float, uz: Float,
        right: Float, up: Float,
    ) {
        out[index * 3] = dx + rx * right + ux * up
        out[index * 3 + 1] = dy + ry * right + uy * up
        out[index * 3 + 2] = dz + rz * right + uz * up
    }
}

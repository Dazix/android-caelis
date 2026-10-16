package com.dazix.caelis.ar.scene

import com.dazix.caelis.data.nightsky.NightSkyCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The CPU half of [StarField]: the catalogue turned into quad vertices, indices and unit vectors.
 *
 * Kept apart from the Filament objects so it can be built off the main thread. Done inline it was
 * most of the 387 ms the Night Sky scene took to build on a Pixel 7 Pro, which froze the screen
 * transition for some 56 frames; the engine calls that remain need the main thread and are cheap.
 */
class StarGeometry(catalog: NightSkyCatalog) {

    val starCount: Int = catalog.starsLength().coerceAtMost(MAX_STARS)

    /** Celestial unit vectors (x, y, z per star), Y towards the north celestial pole. */
    val celestial = FloatArray(starCount * 3)

    /** Interleaved position (sky radius), corner and magnitude; see `star.mat`. */
    val vertices: ByteBuffer = newBuffer(starCount * 4 * STRIDE)

    /** Two triangles per star, uint32. */
    val indices: ByteBuffer = newBuffer(starCount * 6 * 4)

    init {
        val bb = catalog.byteBuffer
        val base = catalog.starsVectorOffset()
        val r = SkySurface.SKY_RADIUS
        // Filled on the heap and copied in bulk: 2.8M single putFloat calls into a direct buffer
        // are several times slower than one array copy.
        val floats = FloatArray(starCount * 4 * FLOATS_PER_VERTEX)
        var f = 0
        for (i in 0 until starCount) {
            val offset = base + i * 12
            val ra = Math.toRadians(bb.getFloat(offset).toDouble())
            val dec = Math.toRadians(bb.getFloat(offset + 4).toDouble())
            val magnitude = bb.getFloat(offset + 8)
            val x = (cos(dec) * cos(ra)).toFloat()
            val y = sin(dec).toFloat()
            val z = (cos(dec) * sin(ra)).toFloat()
            celestial[i * 3] = x
            celestial[i * 3 + 1] = y
            celestial[i * 3 + 2] = z
            for (corner in 0 until 4) {
                floats[f++] = x * r; floats[f++] = y * r; floats[f++] = z * r
                floats[f++] = CORNER_X[corner]; floats[f++] = CORNER_Y[corner]
                floats[f++] = magnitude; floats[f++] = 0f
            }
        }
        vertices.asFloatBuffer().put(floats)

        val ints = IntArray(starCount * 6)
        var k = 0
        for (i in 0 until starCount) {
            val v = i * 4
            ints[k++] = v; ints[k++] = v + 1; ints[k++] = v + 2
            ints[k++] = v; ints[k++] = v + 2; ints[k++] = v + 3
        }
        indices.asIntBuffer().put(ints)
    }

    companion object {
        const val MAX_STARS = 100_000
        const val STRIDE = 28
        private const val FLOATS_PER_VERTEX = STRIDE / 4
        private val CORNER_X = floatArrayOf(-1f, 1f, 1f, -1f)
        private val CORNER_Y = floatArrayOf(-1f, -1f, 1f, 1f)

        private fun newBuffer(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

package com.dazix.caelis.ar.scene

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer

/**
 * Vector marks pinned to sky directions (`glyph.mat`): one quad each, its shape drawn from a
 * distance field in the fragment, sizes and stroke widths in screen pixels.
 *
 * Listed between [begin] and [end] like [LabelBatch], and uploaded only when the listing changed.
 */
class GlyphBatch(
    private val surface: SkySurface,
    material: Material,
    val maxGlyphs: Int,
    priority: Int,
    blendOrder: Int,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val buffers = arrayOf(newDirectBuffer(maxGlyphs * 4 * STRIDE), newDirectBuffer(maxGlyphs * 4 * STRIDE))
    private var uploadedIndex = 1
    private var uploadedCount = -1
    private var writing: ByteBuffer? = null

    var glyphCount = 0
        private set

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(maxGlyphs * 4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 28, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM2, 0, VertexBuffer.AttributeType.FLOAT4, 44, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 60, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM3, 0, VertexBuffer.AttributeType.FLOAT4, 76, STRIDE)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, newDirectBuffer(maxGlyphs * 4 * STRIDE))

        val indices = newDirectBuffer(maxGlyphs * 6 * 4)
        for (g in 0 until maxGlyphs) {
            val v = g * 4
            indices.putInt(v).putInt(v + 1).putInt(v + 2)
            indices.putInt(v).putInt(v + 2).putInt(v + 3)
        }
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(maxGlyphs * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        val r = 2 * SkySurface.SKY_RADIUS
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, r, r, r))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 0)
            .material(0, materialInstance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(priority)
            .blendOrder(0, blendOrder)
            .build(engine, entity)
        engine.transformManager.create(entity)
        surface.scene.addEntity(entity)
    }

    fun begin() {
        glyphCount = 0
        writing = buffers[1 - uploadedIndex].also { it.clear() }
    }

    /**
     * A [shape] (see [Shape]) centred on the sky direction ([x], [y], [z]) (unit vector, model
     * space). [radiusPx], [a], [b] and [strokePx] are the shape's pixel sizes, [angleRad] turns it
     * clockwise on screen, and [haloPx] draws a wider stroke of [haloArgb] under its outline.
     * Colours are ARGB ints, already themed. Returns false when the batch is full.
     */
    fun add(
        shape: Int,
        x: Float, y: Float, z: Float,
        radiusPx: Float,
        strokePx: Float,
        argb: Int,
        a: Float = 0f,
        b: Float = 0f,
        angleRad: Float = 0f,
        haloPx: Float = 0f,
        haloArgb: Int = 0,
    ): Boolean {
        val buffer = checkNotNull(writing) { "add outside begin/end" }
        if (glyphCount >= maxGlyphs) return false
        val reach = maxOf(radiusPx, a, b)
        val extent = reach + 0.5f * maxOf(strokePx, haloPx) + 2f
        val px = x * SkySurface.SKY_RADIUS
        val py = y * SkySurface.SKY_RADIUS
        val pz = z * SkySurface.SKY_RADIUS
        for (corner in 0 until 4) {
            buffer.putFloat(px).putFloat(py).putFloat(pz)
            buffer.putFloat(CORNER_X[corner]).putFloat(CORNER_Y[corner]).putFloat(extent).putFloat(shape.toFloat())
            buffer.putFloat(radiusPx).putFloat(a).putFloat(b).putFloat(strokePx)
            buffer.putFloat(angleRad).putFloat(haloPx).putFloat(0f).putFloat(0f)
            putColor(buffer, argb)
            putColor(buffer, haloArgb)
        }
        glyphCount++
        return true
    }

    private fun putColor(b: ByteBuffer, argb: Int) {
        b.putFloat(((argb shr 16) and 0xFF) / 255f)
        b.putFloat(((argb shr 8) and 0xFF) / 255f)
        b.putFloat((argb and 0xFF) / 255f)
        b.putFloat(((argb ushr 24) and 0xFF) / 255f)
    }

    /** Uploads the listing, unless it is exactly what the GPU already has. */
    fun end() {
        val buffer = checkNotNull(writing) { "end without begin" }
        writing = null
        buffer.flip()
        if (glyphCount == uploadedCount && buffer == buffers[uploadedIndex]) return
        val engine = surface.engine
        if (buffer.remaining() > 0) vertexBuffer.setBufferAt(engine, 0, buffer)
        val rm = engine.renderableManager
        rm.setGeometryAt(
            rm.getInstance(entity), 0, RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer, indexBuffer, 0, glyphCount * 6,
        )
        uploadedIndex = 1 - uploadedIndex
        uploadedCount = glyphCount
    }

    fun setTransform(model16: FloatArray) {
        val tm = surface.engine.transformManager
        tm.setTransform(tm.getInstance(entity), model16)
    }

    fun destroy() {
        val engine = surface.engine
        surface.scene.removeEntity(entity)
        engine.destroyEntity(entity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyMaterialInstance(materialInstance)
        EntityManager.get().destroy(entity)
    }

    /** The shapes `glyph.mat` draws. */
    object Shape {
        const val DISC = 0
        const val RING = 1
        const val ELLIPSE = 2
        const val DASHED_RING = 3
        const val ROUNDED_SQUARE = 4
        const val RING_CROSS = 5
        const val RING_TICKS = 6
        const val RADIANT = 7
        const val RETICLE = 8
        const val GRID_CROSS = 9
    }

    private companion object {
        const val STRIDE = 92
        val CORNER_X = floatArrayOf(-1f, 1f, 1f, -1f)
        val CORNER_Y = floatArrayOf(-1f, -1f, 1f, 1f)
    }
}

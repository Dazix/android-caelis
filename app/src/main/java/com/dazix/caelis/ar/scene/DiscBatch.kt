package com.dazix.caelis.ar.scene

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer

/**
 * Screen-sized discs at sky positions (`disc.mat`), rebuilt between [begin] and [end].
 *
 * Planets, the sun and the moon move and their pixel radii follow zoom and magnitude, so this batch
 * is rewritten whenever the caller has something new — a few dozen quads at most.
 */
class DiscBatch(
    private val surface: SkySurface,
    material: Material,
    val maxDiscs: Int,
    priority: Int,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val geometry = DoubleBuffer(maxDiscs * 4 * STRIDE)
    private var writing: java.nio.ByteBuffer? = null

    var discCount = 0
        private set

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(maxDiscs * 4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 28, STRIDE)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, geometry.next().also { it.position(it.capacity()); it.flip() })

        val indices = newDirectBuffer(maxDiscs * 6 * 2)
        for (d in 0 until maxDiscs) {
            val v = (d * 4).toShort()
            indices.putShort(v).putShort((v + 1).toShort()).putShort((v + 2).toShort())
            indices.putShort(v).putShort((v + 2).toShort()).putShort((v + 3).toShort())
        }
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(maxDiscs * 6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
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
            .build(engine, entity)
        engine.transformManager.create(entity)
        surface.scene.addEntity(entity)
    }

    fun begin() {
        discCount = 0
        writing = geometry.next()
    }

    /**
     * A disc centred on a sky direction (unit vector, model space). `profile` 0 = solid core,
     * 1 = radial glow. Colour is straight RGBA. Returns false when the batch is full.
     */
    fun addDisc(
        x: Float, y: Float, z: Float,
        radiusPx: Float,
        profile: Int,
        r: Float, g: Float, b: Float, a: Float,
    ): Boolean {
        val buffer = checkNotNull(writing) { "addDisc outside begin/end" }
        if (discCount >= maxDiscs) return false
        val px = x * SkySurface.SKY_RADIUS
        val py = y * SkySurface.SKY_RADIUS
        val pz = z * SkySurface.SKY_RADIUS
        for (corner in 0 until 4) {
            buffer.putFloat(px).putFloat(py).putFloat(pz)
            buffer.putFloat(CORNER_X[corner]).putFloat(CORNER_Y[corner]).putFloat(radiusPx).putFloat(profile.toFloat())
            buffer.putFloat(r).putFloat(g).putFloat(b).putFloat(a)
        }
        discCount++
        return true
    }

    fun end() {
        val buffer = checkNotNull(writing) { "end without begin" }
        writing = null
        buffer.flip()
        val engine = surface.engine
        if (buffer.remaining() > 0) vertexBuffer.setBufferAt(engine, 0, buffer)
        val rm = engine.renderableManager
        rm.setGeometryAt(
            rm.getInstance(entity), 0, RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer, indexBuffer, 0, discCount * 6,
        )
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

    private companion object {
        const val STRIDE = 44
        val CORNER_X = floatArrayOf(-1f, 1f, 1f, -1f)
        val CORNER_Y = floatArrayOf(-1f, -1f, 1f, 1f)
    }
}

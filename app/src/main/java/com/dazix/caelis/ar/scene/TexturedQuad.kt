package com.dazix.caelis.ar.scene

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer

/**
 * One image on the sky through four corner directions (`textured.mat`): a constellation figure,
 * a DSO photograph or a planet disc. The corners are unit vectors in model space; the image's
 * top-left, top-right, bottom-right, bottom-left map to corners 0..3.
 */
class TexturedQuad(
    private val surface: SkySurface,
    material: Material,
    priority: Int,
    /** Order among the transparent renderables of the same [priority]; higher draws later. */
    blendOrder: Int = 0,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val corners = DoubleBuffer(4 * STRIDE)
    private var visible = true

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .build(engine)
        setCorners(floatArrayOf(0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f))

        val indices = newDirectBuffer(6 * 2)
        indices.putShort(0).putShort(1).putShort(2).putShort(0).putShort(2).putShort(3).flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        val r = 2 * SkySurface.SKY_RADIUS
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, r, r, r))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
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

    /** Twelve floats: four unit direction vectors, top-left first, clockwise. */
    fun setCorners(directions12: FloatArray) {
        val buffer = corners.next()
        for (c in 0 until 4) {
            buffer.putFloat(directions12[c * 3] * SkySurface.SKY_RADIUS)
            buffer.putFloat(directions12[c * 3 + 1] * SkySurface.SKY_RADIUS)
            buffer.putFloat(directions12[c * 3 + 2] * SkySurface.SKY_RADIUS)
            buffer.putFloat(U[c]).putFloat(V[c])
        }
        buffer.flip()
        vertexBuffer.setBufferAt(surface.engine, 0, buffer)
    }

    fun setTexture(texture: Texture) {
        materialInstance.setParameter("image", texture, skySampler(repeatU = false))
    }

    fun setOpacity(opacity: Float) {
        materialInstance.setParameter("opacity", opacity)
    }

    fun setTransform(model16: FloatArray) {
        val tm = surface.engine.transformManager
        tm.setTransform(tm.getInstance(entity), model16)
    }

    fun setVisible(value: Boolean) {
        if (value == visible) return
        visible = value
        if (value) surface.scene.addEntity(entity) else surface.scene.removeEntity(entity)
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
        const val STRIDE = 20
        // textured.mat keeps flipUV's default, which flips V: v = 1 here is the image's top row.
        val U = floatArrayOf(0f, 1f, 1f, 0f)
        val V = floatArrayOf(1f, 1f, 0f, 0f)
    }
}

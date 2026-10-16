package com.dazix.caelis.ar.scene

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer

/**
 * One triangle covering the whole screen for a `vertexDomain : device` material that ray-casts
 * per pixel (Milky Way, atmosphere, stylised landscape, the ARCore camera image).
 *
 * The triangle is (-1,-1), (3,-1), (-1,3) in clip space: bigger than the screen so there is no
 * diagonal seam. Depth is 0.5, inside the clip range of either depth convention; these materials
 * neither test nor write depth, so the value only has to survive clipping.
 */
class FullScreenLayer(
    private val surface: SkySurface,
    material: Material,
    priority: Int,
    withUv: Boolean = false,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val uvBuffers = if (withUv) DoubleBuffer(3 * 8) else null

    init {
        val engine = surface.engine
        val positions = newDirectBuffer(3 * 12)
        positions.putFloat(-1f).putFloat(-1f).putFloat(0.5f)
        positions.putFloat(3f).putFloat(-1f).putFloat(0.5f)
        positions.putFloat(-1f).putFloat(3f).putFloat(0.5f)
        positions.flip()

        val builder = VertexBuffer.Builder()
            .vertexCount(3)
            .bufferCount(if (withUv) 2 else 1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        if (withUv) builder.attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        vertexBuffer = builder.build(engine)
        vertexBuffer.setBufferAt(engine, 0, positions)
        if (withUv) setUv(0f, 0f, 2f, 0f, 0f, 2f)

        val indices = newDirectBuffer(3 * 2)
        indices.putShort(0).putShort(1).putShort(2).flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(3)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(priority)
            .build(engine, entity)
        surface.scene.addEntity(entity)
    }

    /** Texture coordinates of the three corners (only for a layer created `withUv`). */
    fun setUv(u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float) {
        val buffer = checkNotNull(uvBuffers) { "layer has no UVs" }.next()
        buffer.putFloat(u0).putFloat(v0).putFloat(u1).putFloat(v1).putFloat(u2).putFloat(v2).flip()
        vertexBuffer.setBufferAt(surface.engine, 1, buffer)
    }

    fun setVisible(visible: Boolean) {
        val scene = surface.scene
        if (visible && !scene.hasEntity(entity)) scene.addEntity(entity)
        if (!visible && scene.hasEntity(entity)) scene.removeEntity(entity)
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
}

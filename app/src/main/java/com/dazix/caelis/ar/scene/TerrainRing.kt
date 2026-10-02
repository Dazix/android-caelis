package com.dazix.caelis.ar.scene

import com.dazix.caelis.domain.arframe.HorizonMeshBuilder
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer

/**
 * The real terrain skyline (`terrain.mat`) built from a [HorizonProfile] by [HorizonMeshBuilder].
 *
 * The mesh changes only with the profile; alignment offsets are material parameters, so a drag or
 * an auto-alignment moves the ridge without re-uploading 2882 vertices.
 */
class TerrainRing(
    private val surface: SkySurface,
    material: Material,
    priority: Int,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val vertices = FloatArray(HorizonMeshBuilder.VERTEX_COUNT * HorizonMeshBuilder.FLOATS_PER_VERTEX)
    private val upload = DoubleBuffer(HorizonMeshBuilder.VERTEX_COUNT * STRIDE)
    // Declared before init: setColor runs from it.
    private val color = FloatArray(4)
    private var profileStamp = Long.MIN_VALUE
    private var hasProfile = false
    private var requestedVisible = false

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(HorizonMeshBuilder.VERTEX_COUNT)
            .bufferCount(1)
            // POSITION is required by Filament; terrain.mat computes the real position from custom0.
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .build(engine)

        val idx = IntArray(HorizonMeshBuilder.INDEX_COUNT)
        HorizonMeshBuilder.buildIndices(idx)
        val indices = newDirectBuffer(idx.size * 2)
        for (i in idx) indices.putShort(i.toShort())
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(idx.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)
        uploadMesh(null)

        val r = 2 * SkySurface.SKY_RADIUS
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, r, r, r))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(priority)
            .build(engine, entity)
        setOffsets(0f, 0f)
        setColor(0.024f, 0.031f, 0.043f, 1f)
    }

    val hasUsableProfile: Boolean get() = hasProfile

    /** Null or unusable -> never drawn; the stylised landscape layer covers that case, not a flat ring. */
    fun setProfile(profile: HorizonProfile?) {
        val usable = profile != null && profile.isUsable
        if (usable && hasProfile && profile!!.generatedAtEpochMillis == profileStamp) return
        hasProfile = usable
        profileStamp = if (usable) profile!!.generatedAtEpochMillis else Long.MIN_VALUE
        if (usable) uploadMesh(profile)
        applyVisibility()
    }

    fun setVisible(visible: Boolean) {
        if (visible == requestedVisible) return
        requestedVisible = visible
        applyVisibility()
    }

    private fun applyVisibility() {
        val show = requestedVisible && hasProfile
        val scene = surface.scene
        if (show && !scene.hasEntity(entity)) scene.addEntity(entity)
        if (!show && scene.hasEntity(entity)) scene.removeEntity(entity)
    }

    /** Alignment offsets in degrees, the same convention as `StarHitTester.isOccludedByLandscape`. */
    fun setOffsets(azimuthDeg: Float, elevationDeg: Float) {
        materialInstance.setParameter("azimuthOffset", azimuthDeg)
        materialInstance.setParameter("elevationOffset", elevationDeg)
    }

    /**
     * Straight RGBA, written as a raw float4: the `RgbaType` overloads convert and premultiply,
     * and terrain.mat premultiplies itself.
     */
    fun setColor(r: Float, g: Float, b: Float, a: Float) {
        color[0] = r; color[1] = g; color[2] = b; color[3] = a
        materialInstance.setParameter("terrainColor", MaterialInstance.FloatElement.FLOAT4, color, 0, 1)
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

    private fun uploadMesh(profile: HorizonProfile?) {
        HorizonMeshBuilder.buildVertices(profile, vertices)
        val buffer = upload.next()
        val f = HorizonMeshBuilder.FLOATS_PER_VERTEX
        for (v in 0 until HorizonMeshBuilder.VERTEX_COUNT) {
            buffer.putFloat(0f).putFloat(0f).putFloat(0f)
            buffer.putFloat(vertices[v * f]).putFloat(vertices[v * f + 1])
            buffer.putFloat(vertices[v * f + 2]).putFloat(vertices[v * f + 3])
        }
        buffer.flip()
        vertexBuffer.setBufferAt(surface.engine, 0, buffer)
    }

    private companion object {
        const val STRIDE = 28
    }
}

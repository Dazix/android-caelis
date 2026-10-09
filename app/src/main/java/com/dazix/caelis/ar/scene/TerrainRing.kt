package com.dazix.caelis.ar.scene

import com.dazix.caelis.domain.arframe.HorizonMeshBuilder
import com.dazix.caelis.domain.arframe.HorizonMorph
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

    // Per-vertex elevation the ridge is morphing from and to; see HorizonMorph.
    private val fromElevation = FloatArray(HorizonMeshBuilder.VERTEX_COUNT)
    private val toElevation = FloatArray(HorizonMeshBuilder.VERTEX_COUNT)
    private val morph = HorizonMorph()
    private var framesShown = 0

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
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT, 28, STRIDE)
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
        materialInstance.setParameter("morph", morph.eased)
    }

    val hasUsableProfile: Boolean get() = hasProfile

    /**
     * Null or unusable draws a flat ring at 0° — the placeholder while a profile is on its way; the
     * owner shows the stylised landscape instead once none is coming. A new shape rises out of
     * whatever is on screen, unless nothing has been drawn yet, in which case it simply appears.
     */
    fun setProfile(profile: HorizonProfile?) {
        val usable = profile != null && profile.isUsable
        val stamp = if (usable) profile!!.generatedAtEpochMillis else Long.MIN_VALUE
        if (usable == hasProfile && stamp == profileStamp) return
        hasProfile = usable
        profileStamp = stamp
        uploadMesh(if (usable) profile else null)
        applyVisibility()
    }

    /** Advances the rise of the ridge by one frame. Call from the frame loop. */
    fun advance(dtSeconds: Float) {
        if (isShown()) framesShown++
        if (morph.advance(dtSeconds)) materialInstance.setParameter("morph", morph.eased)
    }

    fun setVisible(visible: Boolean) {
        if (visible == requestedVisible) return
        requestedVisible = visible
        applyVisibility()
    }

    private fun isShown() = surface.scene.hasEntity(entity)

    private fun applyVisibility() {
        val show = requestedVisible
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
        val f = HorizonMeshBuilder.FLOATS_PER_VERTEX
        // Start from what is on screen now; before anything was drawn there is nothing to morph from.
        val animate = framesShown > 0
        for (v in 0 until HorizonMeshBuilder.VERTEX_COUNT) {
            val target = vertices[v * f + 1]
            fromElevation[v] = if (animate) morph.blend(fromElevation[v], toElevation[v]) else target
            toElevation[v] = target
        }
        if (animate) morph.restart() else morph.finish()
        materialInstance.setParameter("morph", morph.eased)

        val buffer = upload.next()
        for (v in 0 until HorizonMeshBuilder.VERTEX_COUNT) {
            buffer.putFloat(0f).putFloat(0f).putFloat(0f)
            buffer.putFloat(vertices[v * f]).putFloat(vertices[v * f + 1])
            buffer.putFloat(vertices[v * f + 2]).putFloat(vertices[v * f + 3])
            buffer.putFloat(fromElevation[v])
        }
        buffer.flip()
        vertexBuffer.setBufferAt(surface.engine, 0, buffer)
    }

    private companion object {
        const val STRIDE = 32
    }
}

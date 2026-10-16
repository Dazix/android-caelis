package com.dazix.caelis.ar.scene

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The whole star catalogue as one renderable of camera-facing quads (see `star.mat`).
 *
 * Geometry is built once, in the celestial frame, and never touched again: the sky turns by
 * moving the renderable's transform (celestial -> ENU), not by rewriting 100k positions per frame.
 * The only per-star data that changes is the "forced" flag (stars of a visible or selected
 * constellation bypass the telescope limit), held in its own small buffer and uploaded only when
 * the set changes.
 */
class StarField(private val surface: SkySurface, geometry: StarGeometry, material: Material) {

    val entity: Int = EntityManager.get().create()
    val starCount: Int = geometry.starCount
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexCount = starCount * 4
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer

    // Two alternating upload buffers: Filament reads a buffer asynchronously after setBufferAt, so
    // the one just handed over must not be written again until the next change.
    private val forcedBuffers = arrayOf(newBuffer(vertexCount * 4), newBuffer(vertexCount * 4))
    private val forced = BooleanArray(starCount)

    // The vertex stream mirrored on the heap and copied into the upload buffer in one bulk put.
    // Writing it byte by byte (1.6 MB as 400k separate puts into a direct buffer) took 25-30 ms
    // on a Pixel 7 Pro, every time a constellation faded in or out during a pan.
    private val forcedBytes = ByteArray(vertexCount * 4)
    private val forcedList = IntArray(starCount)
    private var forcedListSize = 0
    private var forcedFront = 0
    private var forcedDirty = false

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.CUSTOM1)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, geometry.vertices)
        uploadForced()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(starCount * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, geometry.indices)

        val r = SkySurface.SKY_RADIUS * 1.1f
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, r, r, r))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            // The quads are expanded in the vertex shader, so the CPU-side box says nothing useful
            // about what is on screen; the whole sphere is always potentially visible.
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        engine.transformManager.create(entity)
        surface.scene.addEntity(entity)
    }

    /** Adds a star to the forced set; [clearForced] empties the whole set. */
    fun force(starIndex: Int) {
        if (starIndex !in 0 until starCount || forced[starIndex]) return
        forced[starIndex] = true
        forcedList[forcedListSize++] = starIndex
        writeFlag(starIndex, -1)
        forcedDirty = true
    }

    fun isForced(starIndex: Int): Boolean = starIndex in 0 until starCount && forced[starIndex]

    fun clearForced() {
        for (k in 0 until forcedListSize) {
            val i = forcedList[k]
            forced[i] = false
            writeFlag(i, 0)
            forcedDirty = true
        }
        forcedListSize = 0
    }

    private fun writeFlag(starIndex: Int, value: Byte) {
        val base = starIndex * 16
        for (corner in 0 until 4) forcedBytes[base + corner * 4] = value
    }

    /** Uploads the forced flags if they changed since the last frame. Call from the frame loop. */
    fun flush() {
        if (forcedDirty) uploadForced()
    }

    private fun uploadForced() {
        forcedFront = 1 - forcedFront
        val buffer = forcedBuffers[forcedFront]
        buffer.clear()
        buffer.put(forcedBytes)
        buffer.flip()
        vertexBuffer.setBufferAt(surface.engine, 1, buffer)
        forcedDirty = false
    }

    fun setTransform(celestialToEnu16: FloatArray) {
        val tm = surface.engine.transformManager
        tm.setTransform(tm.getInstance(entity), celestialToEnu16)
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

    companion object {
        private const val STRIDE = StarGeometry.STRIDE

        private fun newBuffer(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

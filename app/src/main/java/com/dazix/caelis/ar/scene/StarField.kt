package com.dazix.caelis.ar.scene

import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The whole star catalogue as one renderable of camera-facing quads (see `star.mat`).
 *
 * Geometry is built once, in the celestial frame, and never touched again: the sky turns by
 * moving the renderable's transform (celestial -> ENU), not by rewriting 100k positions per frame.
 * The only per-star data that changes is the "forced" flag (stars of a visible or selected
 * constellation bypass the telescope limit), held in its own small buffer and uploaded only when
 * the set changes.
 */
class StarField(private val surface: SkySurface, catalog: NightSkyCatalog, material: Material) {

    val entity: Int = EntityManager.get().create()
    val starCount: Int = catalog.starsLength().coerceAtMost(MAX_STARS)
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexCount = starCount * 4
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer

    // Two alternating upload buffers: Filament reads a buffer asynchronously after setBufferAt, so
    // the one just handed over must not be written again until the next change.
    private val forcedBuffers = arrayOf(newBuffer(vertexCount * 4), newBuffer(vertexCount * 4))
    private val forced = BooleanArray(starCount)
    private var forcedFront = 0
    private var forcedDirty = false

    init {
        val geometry = newBuffer(vertexCount * STRIDE)
        val bb = catalog.byteBuffer
        val base = catalog.starsVectorOffset()
        for (i in 0 until starCount) {
            val offset = base + i * 12
            val ra = Math.toRadians(bb.getFloat(offset).toDouble())
            val dec = Math.toRadians(bb.getFloat(offset + 4).toDouble())
            val magnitude = bb.getFloat(offset + 8)
            val x = (cos(dec) * cos(ra)).toFloat() * SkySurface.SKY_RADIUS
            val y = sin(dec).toFloat() * SkySurface.SKY_RADIUS
            val z = (cos(dec) * sin(ra)).toFloat() * SkySurface.SKY_RADIUS
            for (corner in 0 until 4) {
                geometry.putFloat(x).putFloat(y).putFloat(z)
                geometry.putFloat(CORNER_X[corner]).putFloat(CORNER_Y[corner])
                geometry.putFloat(magnitude).putFloat(0f)
            }
        }
        geometry.flip()

        val indices = newBuffer(starCount * 6 * 4)
        for (i in 0 until starCount) {
            val v = i * 4
            indices.putInt(v).putInt(v + 1).putInt(v + 2)
            indices.putInt(v).putInt(v + 2).putInt(v + 3)
        }
        indices.flip()

        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.CUSTOM1)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, geometry)
        uploadForced()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(starCount * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

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

    fun setForced(starIndex: Int, value: Boolean) {
        if (starIndex !in 0 until starCount || forced[starIndex] == value) return
        forced[starIndex] = value
        forcedDirty = true
    }

    fun clearForced() {
        for (i in 0 until starCount) {
            if (forced[i]) {
                forced[i] = false
                forcedDirty = true
            }
        }
    }

    /** Uploads the forced flags if they changed since the last frame. Call from the frame loop. */
    fun flush() {
        if (forcedDirty) uploadForced()
    }

    private fun uploadForced() {
        forcedFront = 1 - forcedFront
        val buffer = forcedBuffers[forcedFront]
        buffer.clear()
        for (i in 0 until starCount) {
            val b: Byte = if (forced[i]) -1 else 0
            for (corner in 0 until 4) buffer.put(b).put(0).put(0).put(0)
        }
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
        const val MAX_STARS = 100_000
        private const val STRIDE = 28
        private val CORNER_X = floatArrayOf(-1f, 1f, 1f, -1f)
        private val CORNER_Y = floatArrayOf(-1f, -1f, 1f, 1f)

        private fun newBuffer(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

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
 * Sprites of a [SpriteAtlas] pinned to sky directions, drawn by `label.mat` in the same Filament
 * frame as everything else. Each one is shown 1:1 in pixels: the direction is projected on the GPU
 * and the sprite laid out around it in screen pixels, so it stays the same size at every zoom.
 *
 * The caller lists the sprites of this frame between [begin] and [end]. The listing is cheap and
 * pose-independent (the GPU projects), and [end] uploads only when it differs from the last upload,
 * so a phone held still or a pan over a settled sky uploads nothing.
 */
class LabelBatch(
    private val surface: SkySurface,
    material: Material,
    private val atlas: SpriteAtlas,
    val maxSprites: Int,
    priority: Int,
    blendOrder: Int,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer

    // Two buffers in turn: the one last handed to Filament may still be read on its thread, the
    // other is written. A listing equal to the last upload is dropped and the roles stay.
    private val buffers = arrayOf(newDirectBuffer(maxSprites * 4 * STRIDE), newDirectBuffer(maxSprites * 4 * STRIDE))
    private var uploadedIndex = 1
    private var uploadedCount = -1
    private var writing: ByteBuffer? = null

    var spriteCount = 0
        private set

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(maxSprites * 4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 36, STRIDE)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, newDirectBuffer(maxSprites * 4 * STRIDE))

        val indices = newDirectBuffer(maxSprites * 6 * 4)
        for (s in 0 until maxSprites) {
            val v = s * 4
            indices.putInt(v).putInt(v + 1).putInt(v + 2)
            indices.putInt(v).putInt(v + 2).putInt(v + 3)
        }
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(maxSprites * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        materialInstance.setParameter("atlas", atlas.texture, skySampler(repeatU = false))

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
        spriteCount = 0
        writing = buffers[1 - uploadedIndex].also { it.clear() }
    }

    /**
     * Sprite [sprite] of the atlas with its anchor at the sky direction ([x], [y], [z]) (unit
     * vector, model space), moved by ([offsetXPx], [offsetYPx]) screen pixels (Y down) and drawn at
     * [alpha]. With a margin, the sprite is hidden while its anchor is closer than [marginXPx] /
     * [marginYPx] to the screen edge (the grid's altitude labels, which would crowd the corners).
     *
     * With [alignToHorizon] the sprite stands on the local vertical at the anchor rather than on the
     * screen's up: its anchor is moved [liftPx] along that vertical and the sprite is turned a
     * further [angleRad] counter-clockwise around it (`label.mat`; the CPU twin is
     * [com.dazix.caelis.domain.arframe.TiltedLabel]). The offsets should then stay zero.
     *
     * Returns false when the batch is full or the sprite does not exist.
     */
    fun add(
        sprite: Int,
        x: Float, y: Float, z: Float,
        offsetXPx: Float = 0f,
        offsetYPx: Float = 0f,
        alpha: Float = 1f,
        marginXPx: Float = 0f,
        marginYPx: Float = 0f,
        alignToHorizon: Boolean = false,
        liftPx: Float = 0f,
        angleRad: Float = 0f,
    ): Boolean {
        val buffer = checkNotNull(writing) { "add outside begin/end" }
        if (sprite !in 0 until atlas.spriteCount || spriteCount >= maxSprites || alpha <= 0f) return false
        val px = x * SkySurface.SKY_RADIUS
        val py = y * SkySurface.SKY_RADIUS
        val pz = z * SkySurface.SKY_RADIUS
        val left = offsetXPx - atlas.anchorX[sprite]
        val top = offsetYPx - atlas.anchorY[sprite]
        val right = left + atlas.spriteWidth[sprite]
        val bottom = top + atlas.spriteHeight[sprite]
        val u0 = atlas.u0[sprite]
        val v0 = atlas.v0[sprite]
        val u1 = atlas.u1[sprite]
        val v1 = atlas.v1[sprite]
        val angle = if (alignToHorizon) angleRad else 0f
        val lift = if (alignToHorizon) liftPx else 0f
        val align = if (alignToHorizon) 1f else 0f
        vertex(buffer, px, py, pz, u0, v0, left, top, alpha, angle, marginXPx, marginYPx, lift, align)
        vertex(buffer, px, py, pz, u1, v0, right, top, alpha, angle, marginXPx, marginYPx, lift, align)
        vertex(buffer, px, py, pz, u1, v1, right, bottom, alpha, angle, marginXPx, marginYPx, lift, align)
        vertex(buffer, px, py, pz, u0, v1, left, bottom, alpha, angle, marginXPx, marginYPx, lift, align)
        spriteCount++
        return true
    }

    private fun vertex(
        b: ByteBuffer,
        px: Float, py: Float, pz: Float,
        u: Float, v: Float,
        dx: Float, dy: Float,
        alpha: Float, angle: Float,
        mx: Float, my: Float,
        lift: Float, align: Float,
    ) {
        b.putFloat(px).putFloat(py).putFloat(pz)
        b.putFloat(u).putFloat(v)
        b.putFloat(dx).putFloat(dy).putFloat(alpha).putFloat(angle)
        b.putFloat(mx).putFloat(my).putFloat(lift).putFloat(align)
    }

    /** Uploads the listing, unless it is exactly what the GPU already has. */
    fun end() {
        val buffer = checkNotNull(writing) { "end without begin" }
        writing = null
        buffer.flip()
        val previous = buffers[uploadedIndex]
        if (spriteCount == uploadedCount && buffer == previous) return
        val engine = surface.engine
        if (buffer.remaining() > 0) vertexBuffer.setBufferAt(engine, 0, buffer)
        val rm = engine.renderableManager
        rm.setGeometryAt(
            rm.getInstance(entity), 0, RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer, indexBuffer, 0, spriteCount * 6,
        )
        uploadedIndex = 1 - uploadedIndex
        uploadedCount = spriteCount
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
        const val STRIDE = 52
    }
}

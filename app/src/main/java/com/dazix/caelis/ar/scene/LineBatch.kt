package com.dazix.caelis.ar.scene

import com.dazix.caelis.domain.arframe.AltAzGridBuilder
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer

/**
 * A batch of pixel-width line segments drawn with `line.mat`, in one renderable.
 *
 * Segments are written between [begin] and [end]; geometry is re-uploaded only by [end], so a
 * static figure (constellations, the grid) costs nothing per frame. Each segment belongs to a
 * *group* whose progress (the constellation's meet-in-the-middle animation) lives in a separate,
 * tiny buffer: [setGroupProgress] + [flushProgress] update it without touching the geometry.
 *
 * Positions are in the renderable's model space — celestial for constellation lines, ENU for the
 * grid and arcs — chosen by the transform the owner sets. No allocations after construction.
 */
class LineBatch(
    private val surface: SkySurface,
    material: Material,
    val maxSegments: Int,
    private val maxGroups: Int,
    priority: Int,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val geometry = DoubleBuffer(maxSegments * 4 * STRIDE)
    private val progressUpload = DoubleBuffer(maxSegments * 4 * 4)
    private val segmentGroup = IntArray(maxSegments)
    private val groupProgress = FloatArray(maxGroups) { 1f }
    private var progressDirty = true

    var segmentCount = 0
        private set
    private var writing: java.nio.ByteBuffer? = null

    init {
        val engine = surface.engine
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(maxSegments * 4)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 28, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 44, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM3, 0, VertexBuffer.AttributeType.FLOAT4, 60, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM2, 1, VertexBuffer.AttributeType.FLOAT, 0, 4)
            .build(engine)

        val indices = newDirectBuffer(maxSegments * 6 * 4)
        for (s in 0 until maxSegments) {
            val v = s * 4
            indices.putInt(v).putInt(v + 1).putInt(v + 2)
            indices.putInt(v).putInt(v + 2).putInt(v + 3)
        }
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(maxSegments * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        // Zeroed first uploads of both buffers, so no attribute is ever read from unset memory.
        vertexBuffer.setBufferAt(engine, 0, geometry.next().also { it.position(it.capacity()); it.flip() })
        vertexBuffer.setBufferAt(engine, 1, progressUpload.next().also { it.position(it.capacity()); it.flip() })

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
        flushProgress()
    }

    fun begin() {
        segmentCount = 0
        writing = geometry.next()
    }

    /**
     * Adds a segment from A to B. [color] is straight RGBA in 0..1. [gapPx] is left free at both
     * ends (the Canvas renderer's 7 dp around each star), [dashPx] > 0 dashes the line.
     * Returns false when the batch is full.
     */
    fun addSegment(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        widthPx: Float,
        r: Float, g: Float, b: Float, a: Float,
        gapPx: Float = 0f,
        dashPx: Float = 0f,
        group: Int = 0,
    ): Boolean {
        val buffer = checkNotNull(writing) { "addSegment outside begin/end" }
        if (segmentCount >= maxSegments) return false
        // v0 = A left, v1 = A right, v2 = B right, v3 = B left: the quad's outline, which the
        // indices (0,1,2)(0,2,3) need. `side` is relative to the A -> B normal at both ends, so
        // B right is +1 like A right. With B's two sides swapped the two triangles crossed into a
        // bow tie: a notch out of one edge of every segment and a doubled sliver on the other,
        // which on the densely sampled sun arcs read as a saw blade.
        putVertex(buffer, ax, ay, az, bx, by, bz, -1f, 0f, widthPx, gapPx, dashPx, r, g, b, a, 0f, 0f, 0f, 0f)
        putVertex(buffer, ax, ay, az, bx, by, bz, 1f, 0f, widthPx, gapPx, dashPx, r, g, b, a, 0f, 0f, 0f, 0f)
        putVertex(buffer, bx, by, bz, ax, ay, az, 1f, 1f, widthPx, gapPx, dashPx, r, g, b, a, 0f, 0f, 0f, 0f)
        putVertex(buffer, bx, by, bz, ax, ay, az, -1f, 1f, widthPx, gapPx, dashPx, r, g, b, a, 0f, 0f, 0f, 0f)
        segmentGroup[segmentCount] = group.coerceIn(0, maxGroups - 1)
        segmentCount++
        return true
    }

    /**
     * A segment A-B of a polyline, mitred onto its neighbours: P is the point before A and N the
     * one after B ([hasPrevious] / [hasNext] false at an open end). Adjacent segments then share
     * their edges exactly. Butt-ended segments leave a wedge on the outside of every bend and
     * overlap on the inside, which on a densely sampled curve with a translucent casing read as a
     * saw blade (the sun arcs, sampled every 2 minutes, on a Pixel 7 Pro).
     */
    fun addJoinedSegment(
        px: Float, py: Float, pz: Float, hasPrevious: Boolean,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        nx: Float, ny: Float, nz: Float, hasNext: Boolean,
        widthPx: Float,
        r: Float, g: Float, b: Float, a: Float,
    ): Boolean {
        val buffer = checkNotNull(writing) { "addJoinedSegment outside begin/end" }
        if (segmentCount >= maxSegments) return false
        val joinA = if (hasPrevious) 1f else 0f
        val joinB = if (hasNext) 1f else 0f
        putVertex(buffer, ax, ay, az, bx, by, bz, -1f, 0f, widthPx, 0f, 0f, r, g, b, a, px, py, pz, joinA)
        putVertex(buffer, ax, ay, az, bx, by, bz, 1f, 0f, widthPx, 0f, 0f, r, g, b, a, px, py, pz, joinA)
        putVertex(buffer, bx, by, bz, ax, ay, az, 1f, 1f, widthPx, 0f, 0f, r, g, b, a, nx, ny, nz, joinB)
        putVertex(buffer, bx, by, bz, ax, ay, az, -1f, 1f, widthPx, 0f, 0f, r, g, b, a, nx, ny, nz, joinB)
        segmentGroup[segmentCount] = 0
        segmentCount++
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
            vertexBuffer, indexBuffer, 0, segmentCount * 6,
        )
        progressDirty = true
        flushProgress()
    }

    fun setGroupProgress(group: Int, progress: Float) {
        if (group !in 0 until maxGroups) return
        val p = progress.coerceIn(0f, 1f)
        if (groupProgress[group] != p) {
            groupProgress[group] = p
            progressDirty = true
        }
    }

    /** Uploads group progress if any changed. Call once per frame after [setGroupProgress]. */
    fun flushProgress() {
        if (!progressDirty) return
        progressDirty = false
        val buffer = progressUpload.next()
        for (s in 0 until segmentCount) {
            val p = groupProgress[segmentGroup[s]]
            buffer.putFloat(p).putFloat(p).putFloat(p).putFloat(p)
        }
        buffer.flip()
        if (buffer.remaining() > 0) vertexBuffer.setBufferAt(surface.engine, 1, buffer)
    }

    fun setTransform(model16: FloatArray) {
        val tm = surface.engine.transformManager
        tm.setTransform(tm.getInstance(entity), model16)
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

    private fun putVertex(
        buffer: java.nio.ByteBuffer,
        x: Float, y: Float, z: Float,
        ox: Float, oy: Float, oz: Float,
        side: Float, t: Float,
        widthPx: Float, gapPx: Float, dashPx: Float,
        r: Float, g: Float, b: Float, a: Float,
        jx: Float, jy: Float, jz: Float, join: Float,
    ) {
        buffer.putFloat(x).putFloat(y).putFloat(z)
        buffer.putFloat(ox).putFloat(oy).putFloat(oz).putFloat(side)
        buffer.putFloat(t).putFloat(widthPx).putFloat(gapPx).putFloat(dashPx)
        buffer.putFloat(r).putFloat(g).putFloat(b).putFloat(a)
        buffer.putFloat(jx).putFloat(jy).putFloat(jz).putFloat(join)
    }

    private companion object {
        const val STRIDE = 76
    }
}

/**
 * Rebuilds this batch as the ENU alt-az grid ([AltAzGridBuilder]) in the colours the Canvas grid
 * used. Needs [AltAzGridBuilder.SEGMENT_COUNT] segments.
 */
fun LineBatch.fillAltAzGrid(nightVision: Boolean, theme: NightVisionTheme) {
    val palette = paletteForTheme(theme)
    val horizon = if (nightVision) palette.secondary else NormalHorizon
    val major = if (nightVision) palette.secondary.copy(alpha = 0.6f) else NormalHorizon.copy(alpha = 0.55f)
    val minor = if (nightVision) palette.grid else NormalGrid
    val r = SkySurface.SKY_RADIUS
    begin()
    AltAzGridBuilder.build { ax, ay, az, bx, by, bz, w, role ->
        val c = when (role) {
            AltAzGridBuilder.Role.HORIZON -> horizon
            AltAzGridBuilder.Role.MAJOR -> major
            AltAzGridBuilder.Role.MINOR -> minor
        }
        addSegment(ax * r, ay * r, az * r, bx * r, by * r, bz * r, w, c.red, c.green, c.blue, c.alpha)
    }
    end()
}

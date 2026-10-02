package com.dazix.caelis.ar.scene

import android.graphics.Bitmap
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Render priorities (Filament draws 0 first). Occlusion is draw order, as on the Canvas. */
object SkyPriority {
    const val CAMERA = 0
    const val SKY_BACKGROUND = 1
    const val MILKY_WAY = 2
    const val ATMOSPHERE = 3
    const val CELESTIAL = 4
    const val CELESTIAL_OVERLAY = 5
    const val LANDSCAPE = 6
    const val GRID = 7
}

/**
 * A pair of direct buffers used alternately for uploads.
 *
 * `VertexBuffer.setBufferAt` hands the buffer to Filament, which reads it on its own thread some
 * time later; writing the same buffer again for the next frame could tear the upload in flight.
 * Alternating between two allocated once keeps uploads race-free without allocating per frame.
 */
class DoubleBuffer(bytes: Int) {
    private val buffers = arrayOf(newDirectBuffer(bytes), newDirectBuffer(bytes))
    private var index = 0

    /** The buffer to fill for the next upload, cleared and positioned at 0. */
    fun next(): ByteBuffer {
        index = 1 - index
        return buffers[index].also { it.clear() }
    }
}

fun newDirectBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

/**
 * Uploads a Bitmap as an RGBA8 texture. Not sRGB on purpose: post-processing is off and every other
 * colour in the scene is written raw, as Skia did, so the artwork must be sampled raw too.
 * Bitmap memory is premultiplied, which is what the materials expect.
 */
fun createBitmapTexture(engine: Engine, bitmap: Bitmap): Texture {
    val texture = Texture.Builder()
        .width(bitmap.width)
        .height(bitmap.height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .build(engine)
    TextureHelper.setBitmap(engine, texture, 0, bitmap)
    return texture
}

/** Linear filtering; U repeats for panoramas that wrap around the horizon, V always clamps. */
fun skySampler(repeatU: Boolean): TextureSampler = TextureSampler(
    TextureSampler.MinFilter.LINEAR,
    TextureSampler.MagFilter.LINEAR,
    if (repeatU) TextureSampler.WrapMode.REPEAT else TextureSampler.WrapMode.CLAMP_TO_EDGE,
).also { it.wrapModeT = TextureSampler.WrapMode.CLAMP_TO_EDGE }

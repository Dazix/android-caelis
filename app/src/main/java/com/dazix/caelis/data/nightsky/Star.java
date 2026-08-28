package com.dazix.caelis.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Struct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers inline struct representing a star's astronomical coordinates and visual magnitude.
 * Stored contiguously in 12 bytes [ra: 4B, dec: 4B, mag: 4B] for zero-allocation access.
 */
@SuppressWarnings("unused")
public final class Star extends Struct {
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public Star __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public float ra() { return bb.getFloat(bb_pos + 0); }
    public float dec() { return bb.getFloat(bb_pos + 4); }
    public float mag() { return bb.getFloat(bb_pos + 8); }

    public static int createStar(FlatBufferBuilder builder, float ra, float dec, float mag) {
        builder.prep(4, 12);
        builder.putFloat(mag);
        builder.putFloat(dec);
        builder.putFloat(ra);
        return builder.offset();
    }
}

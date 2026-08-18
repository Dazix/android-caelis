package com.dazix.intervalometer.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers table storing slow metadata for named stars.
 */
@SuppressWarnings("unused")
public final class StarMetadata extends Table {
    public static void ValidateVersion() { Constants.FLATBUFFERS_24_3_25(); }
    public static StarMetadata getRootAsStarMetadata(ByteBuffer _bb) { return getRootAsStarMetadata(_bb, new StarMetadata()); }
    public static StarMetadata getRootAsStarMetadata(ByteBuffer _bb, StarMetadata obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public StarMetadata __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public int starIndex() { int o = __offset(4); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public String name() { int o = __offset(6); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer nameAsByteBuffer() { return __vector_as_bytebuffer(6, 1); }
    public ByteBuffer nameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 6, 1); }

    public static int createStarMetadata(FlatBufferBuilder builder,
        int starIndex,
        int nameOffset) {
        builder.startTable(2);
        StarMetadata.addName(builder, nameOffset);
        StarMetadata.addStarIndex(builder, starIndex);
        return StarMetadata.endStarMetadata(builder);
    }

    public static void startStarMetadata(FlatBufferBuilder builder) { builder.startTable(2); }
    public static void addStarIndex(FlatBufferBuilder builder, int starIndex) { builder.addInt(0, starIndex, 0); }
    public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(1, nameOffset, 0); }
    public static int endStarMetadata(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

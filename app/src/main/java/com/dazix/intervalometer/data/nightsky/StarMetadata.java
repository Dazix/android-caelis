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
    public String bodyType() { int o = __offset(8); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer bodyTypeAsByteBuffer() { return __vector_as_bytebuffer(8, 1); }
    public ByteBuffer bodyTypeInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 8, 1); }
    public String constellation() { int o = __offset(10); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer constellationAsByteBuffer() { return __vector_as_bytebuffer(10, 1); }
    public ByteBuffer constellationInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 10, 1); }
    public float distanceLy() { int o = __offset(12); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }

    public static int createStarMetadata(FlatBufferBuilder builder,
        int starIndex,
        int nameOffset,
        int bodyTypeOffset,
        int constellationOffset,
        float distanceLy) {
        builder.startTable(5);
        StarMetadata.addDistanceLy(builder, distanceLy);
        StarMetadata.addConstellation(builder, constellationOffset);
        StarMetadata.addBodyType(builder, bodyTypeOffset);
        StarMetadata.addName(builder, nameOffset);
        StarMetadata.addStarIndex(builder, starIndex);
        return StarMetadata.endStarMetadata(builder);
    }

    public static int createStarMetadata(FlatBufferBuilder builder,
        int starIndex,
        int nameOffset) {
        return createStarMetadata(builder, starIndex, nameOffset, 0, 0, 0.0f);
    }

    public static void startStarMetadata(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addStarIndex(FlatBufferBuilder builder, int starIndex) { builder.addInt(0, starIndex, 0); }
    public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(1, nameOffset, 0); }
    public static void addBodyType(FlatBufferBuilder builder, int bodyTypeOffset) { builder.addOffset(2, bodyTypeOffset, 0); }
    public static void addConstellation(FlatBufferBuilder builder, int constellationOffset) { builder.addOffset(3, constellationOffset, 0); }
    public static void addDistanceLy(FlatBufferBuilder builder, float distanceLy) { builder.addFloat(4, distanceLy, 0.0f); }
    public static int endStarMetadata(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

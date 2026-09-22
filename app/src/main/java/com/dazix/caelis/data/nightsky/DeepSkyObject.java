package com.dazix.caelis.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers table storing astronomical data for Messier Deep Sky Objects (DSOs).
 */
@SuppressWarnings("unused")
public final class DeepSkyObject extends Table {
    public static void ValidateVersion() { Constants.FLATBUFFERS_24_3_25(); }
    public static DeepSkyObject getRootAsDeepSkyObject(ByteBuffer _bb) { return getRootAsDeepSkyObject(_bb, new DeepSkyObject()); }
    public static DeepSkyObject getRootAsDeepSkyObject(ByteBuffer _bb, DeepSkyObject obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public DeepSkyObject __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public short messierId() { int o = __offset(4); return o != 0 ? bb.getShort(o + bb_pos) : 0; }
    public float ra() { int o = __offset(6); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public float dec() { int o = __offset(8); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public float mag() { int o = __offset(10); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public float sizeArcmin() { int o = __offset(12); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public byte type() { int o = __offset(14); return o != 0 ? bb.get(o + bb_pos) : 0; }
    public String localizedName() { int o = __offset(16); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer localizedNameAsByteBuffer() { return __vector_as_bytebuffer(16, 1); }
    public ByteBuffer localizedNameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 16, 1); }
    public String constellationId() { int o = __offset(18); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer constellationIdAsByteBuffer() { return __vector_as_bytebuffer(18, 1); }
    public ByteBuffer constellationIdInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 18, 1); }
    public String englishName() { int o = __offset(20); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer englishNameAsByteBuffer() { return __vector_as_bytebuffer(20, 1); }
    public ByteBuffer englishNameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 20, 1); }
    public String ngcId() { int o = __offset(22); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer ngcIdAsByteBuffer() { return __vector_as_bytebuffer(22, 1); }
    public ByteBuffer ngcIdInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 22, 1); }

    public static int createDeepSkyObject(FlatBufferBuilder builder,
        short messierId,
        float ra,
        float dec,
        float mag,
        float sizeArcmin,
        byte type,
        int localizedNameOffset,
        int constellationIdOffset,
        int englishNameOffset,
        int ngcIdOffset) {
        builder.startTable(10);
        DeepSkyObject.addNgcId(builder, ngcIdOffset);
        DeepSkyObject.addEnglishName(builder, englishNameOffset);
        DeepSkyObject.addConstellationId(builder, constellationIdOffset);
        DeepSkyObject.addLocalizedName(builder, localizedNameOffset);
        DeepSkyObject.addSizeArcmin(builder, sizeArcmin);
        DeepSkyObject.addMag(builder, mag);
        DeepSkyObject.addDec(builder, dec);
        DeepSkyObject.addRa(builder, ra);
        DeepSkyObject.addMessierId(builder, messierId);
        DeepSkyObject.addType(builder, type);
        return DeepSkyObject.endDeepSkyObject(builder);
    }

    public static void startDeepSkyObject(FlatBufferBuilder builder) { builder.startTable(10); }
    public static void addMessierId(FlatBufferBuilder builder, short messierId) { builder.addShort(0, messierId, 0); }
    public static void addRa(FlatBufferBuilder builder, float ra) { builder.addFloat(1, ra, 0.0f); }
    public static void addDec(FlatBufferBuilder builder, float dec) { builder.addFloat(2, dec, 0.0f); }
    public static void addMag(FlatBufferBuilder builder, float mag) { builder.addFloat(3, mag, 0.0f); }
    public static void addSizeArcmin(FlatBufferBuilder builder, float sizeArcmin) { builder.addFloat(4, sizeArcmin, 0.0f); }
    public static void addType(FlatBufferBuilder builder, byte type) { builder.addByte(5, type, 0); }
    public static void addLocalizedName(FlatBufferBuilder builder, int localizedNameOffset) { builder.addOffset(6, localizedNameOffset, 0); }
    public static void addConstellationId(FlatBufferBuilder builder, int constellationIdOffset) { builder.addOffset(7, constellationIdOffset, 0); }
    public static void addEnglishName(FlatBufferBuilder builder, int englishNameOffset) { builder.addOffset(8, englishNameOffset, 0); }
    public static void addNgcId(FlatBufferBuilder builder, int ngcIdOffset) { builder.addOffset(9, ngcIdOffset, 0); }
    public static int endDeepSkyObject(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

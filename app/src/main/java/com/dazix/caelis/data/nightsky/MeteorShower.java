package com.dazix.caelis.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers table storing astronomical and calendar data for meteor shower radiants.
 */
@SuppressWarnings("unused")
public final class MeteorShower extends Table {
    public static void ValidateVersion() { Constants.FLATBUFFERS_24_3_25(); }
    public static MeteorShower getRootAsMeteorShower(ByteBuffer _bb) { return getRootAsMeteorShower(_bb, new MeteorShower()); }
    public static MeteorShower getRootAsMeteorShower(ByteBuffer _bb, MeteorShower obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public MeteorShower __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public short id() { int o = __offset(4); return o != 0 ? bb.getShort(o + bb_pos) : 0; }
    public String name() { int o = __offset(6); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer nameAsByteBuffer() { return __vector_as_bytebuffer(6, 1); }
    public ByteBuffer nameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 6, 1); }
    public float ra() { int o = __offset(8); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public float dec() { int o = __offset(10); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public short zhr() { int o = __offset(12); return o != 0 ? bb.getShort(o + bb_pos) : 0; }
    public byte peakMonth() { int o = __offset(14); return o != 0 ? bb.get(o + bb_pos) : 0; }
    public byte peakDay() { int o = __offset(16); return o != 0 ? bb.get(o + bb_pos) : 0; }
    public String constellationId() { int o = __offset(18); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer constellationIdAsByteBuffer() { return __vector_as_bytebuffer(18, 1); }
    public ByteBuffer constellationIdInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 18, 1); }

    public static int createMeteorShower(FlatBufferBuilder builder,
        short id,
        int nameOffset,
        float ra,
        float dec,
        short zhr,
        byte peakMonth,
        byte peakDay,
        int constellationIdOffset) {
        builder.startTable(8);
        MeteorShower.addConstellationId(builder, constellationIdOffset);
        MeteorShower.addDec(builder, dec);
        MeteorShower.addRa(builder, ra);
        MeteorShower.addName(builder, nameOffset);
        MeteorShower.addZhr(builder, zhr);
        MeteorShower.addId(builder, id);
        MeteorShower.addPeakDay(builder, peakDay);
        MeteorShower.addPeakMonth(builder, peakMonth);
        return MeteorShower.endMeteorShower(builder);
    }

    public static void startMeteorShower(FlatBufferBuilder builder) { builder.startTable(8); }
    public static void addId(FlatBufferBuilder builder, short id) { builder.addShort(0, id, 0); }
    public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(1, nameOffset, 0); }
    public static void addRa(FlatBufferBuilder builder, float ra) { builder.addFloat(2, ra, 0.0f); }
    public static void addDec(FlatBufferBuilder builder, float dec) { builder.addFloat(3, dec, 0.0f); }
    public static void addZhr(FlatBufferBuilder builder, short zhr) { builder.addShort(4, zhr, 0); }
    public static void addPeakMonth(FlatBufferBuilder builder, byte peakMonth) { builder.addByte(5, peakMonth, 0); }
    public static void addPeakDay(FlatBufferBuilder builder, byte peakDay) { builder.addByte(6, peakDay, 0); }
    public static void addConstellationId(FlatBufferBuilder builder, int constellationIdOffset) { builder.addOffset(7, constellationIdOffset, 0); }
    public static int endMeteorShower(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

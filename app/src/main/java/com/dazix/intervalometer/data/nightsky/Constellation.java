package com.dazix.intervalometer.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.IntVector;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers table storing constellation outline lines as star index pairs.
 */
@SuppressWarnings("unused")
public final class Constellation extends Table {
    public static void ValidateVersion() { Constants.FLATBUFFERS_24_3_25(); }
    public static Constellation getRootAsConstellation(ByteBuffer _bb) { return getRootAsConstellation(_bb, new Constellation()); }
    public static Constellation getRootAsConstellation(ByteBuffer _bb, Constellation obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public Constellation __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public String name() { int o = __offset(4); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer nameAsByteBuffer() { return __vector_as_bytebuffer(4, 1); }
    public ByteBuffer nameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 4, 1); }
    public int lines(int j) { int o = __offset(6); return o != 0 ? bb.getInt(__vector(o) + j * 4) : 0; }
    public int linesLength() { int o = __offset(6); return o != 0 ? __vector_len(o) : 0; }
    public IntVector linesVector() { return linesVector(new IntVector()); }
    public IntVector linesVector(IntVector obj) { int o = __offset(6); return o != 0 ? obj.__assign(__vector(o), bb) : null; }
    public ByteBuffer linesAsByteBuffer() { return __vector_as_bytebuffer(6, 4); }
    public ByteBuffer linesInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 6, 4); }

    public static int createConstellation(FlatBufferBuilder builder,
        int nameOffset,
        int linesOffset) {
        builder.startTable(2);
        Constellation.addLines(builder, linesOffset);
        Constellation.addName(builder, nameOffset);
        return Constellation.endConstellation(builder);
    }

    public static void startConstellation(FlatBufferBuilder builder) { builder.startTable(2); }
    public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(0, nameOffset, 0); }
    public static void addLines(FlatBufferBuilder builder, int linesOffset) { builder.addOffset(1, linesOffset, 0); }
    public static int createLinesVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addInt(data[i]); return builder.endVector(); }
    public static void startLinesVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static int endConstellation(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

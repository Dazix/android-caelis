package com.dazix.intervalometer.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.IntVector;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers table storing constellation data, outline line networks, centroid, and astronomy metadata.
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
    public float centroidRa() { int o = __offset(6); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public float centroidDec() { int o = __offset(8); return o != 0 ? bb.getFloat(o + bb_pos) : 0.0f; }
    public int lines(int j) { int o = __offset(10); return o != 0 ? bb.getInt(__vector(o) + j * 4) : 0; }
    public int linesLength() { int o = __offset(10); return o != 0 ? __vector_len(o) : 0; }
    public IntVector linesVector() { return linesVector(new IntVector()); }
    public IntVector linesVector(IntVector obj) { int o = __offset(10); return o != 0 ? obj.__assign(__vector(o), bb) : null; }
    public ByteBuffer linesAsByteBuffer() { return __vector_as_bytebuffer(10, 4); }
    public ByteBuffer linesInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 10, 4); }
    public String localizedName() { int o = __offset(12); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer localizedNameAsByteBuffer() { return __vector_as_bytebuffer(12, 1); }
    public ByteBuffer localizedNameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 12, 1); }
    public String brightestStar() { int o = __offset(14); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer brightestStarAsByteBuffer() { return __vector_as_bytebuffer(14, 1); }
    public ByteBuffer brightestStarInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 14, 1); }
    public String family() { int o = __offset(16); return o != 0 ? __string(o + bb_pos) : null; }
    public ByteBuffer familyAsByteBuffer() { return __vector_as_bytebuffer(16, 1); }
    public ByteBuffer familyInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 16, 1); }

    public static int createConstellation(FlatBufferBuilder builder,
        int nameOffset,
        float centroidRa,
        float centroidDec,
        int linesOffset,
        int localizedNameOffset,
        int brightestStarOffset,
        int familyOffset) {
        builder.startTable(7);
        Constellation.addFamily(builder, familyOffset);
        Constellation.addBrightestStar(builder, brightestStarOffset);
        Constellation.addLocalizedName(builder, localizedNameOffset);
        Constellation.addLines(builder, linesOffset);
        Constellation.addCentroidDec(builder, centroidDec);
        Constellation.addCentroidRa(builder, centroidRa);
        Constellation.addName(builder, nameOffset);
        return Constellation.endConstellation(builder);
    }

    public static int createConstellation(FlatBufferBuilder builder,
        int nameOffset,
        int linesOffset) {
        return createConstellation(builder, nameOffset, 0.0f, 0.0f, linesOffset, 0, 0, 0);
    }

    public static void startConstellation(FlatBufferBuilder builder) { builder.startTable(7); }
    public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(0, nameOffset, 0); }
    public static void addCentroidRa(FlatBufferBuilder builder, float centroidRa) { builder.addFloat(1, centroidRa, 0.0f); }
    public static void addCentroidDec(FlatBufferBuilder builder, float centroidDec) { builder.addFloat(2, centroidDec, 0.0f); }
    public static void addLines(FlatBufferBuilder builder, int linesOffset) { builder.addOffset(3, linesOffset, 0); }
    public static void addLocalizedName(FlatBufferBuilder builder, int localizedNameOffset) { builder.addOffset(4, localizedNameOffset, 0); }
    public static void addBrightestStar(FlatBufferBuilder builder, int brightestStarOffset) { builder.addOffset(5, brightestStarOffset, 0); }
    public static void addFamily(FlatBufferBuilder builder, int familyOffset) { builder.addOffset(6, familyOffset, 0); }
    public static int createLinesVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addInt(data[i]); return builder.endVector(); }
    public static void startLinesVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static int endConstellation(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
}

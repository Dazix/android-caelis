package com.dazix.caelis.data.nightsky;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers root table representing the whole Night Sky catalog.
 */
@SuppressWarnings("unused")
public final class NightSkyCatalog extends Table {
    public static void ValidateVersion() { Constants.FLATBUFFERS_24_3_25(); }
    public static NightSkyCatalog getRootAsNightSkyCatalog(ByteBuffer _bb) { return getRootAsNightSkyCatalog(_bb, new NightSkyCatalog()); }
    public static NightSkyCatalog getRootAsNightSkyCatalog(ByteBuffer _bb, NightSkyCatalog obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
    public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
    public NightSkyCatalog __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

    public Star stars(int j) { return stars(new Star(), j); }
    public Star stars(Star obj, int j) { int o = __offset(4); return o != 0 ? obj.__assign(__vector(o) + j * 12, bb) : null; }
    public int starsLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
    public int starsVectorOffset() { int o = __offset(4); return o != 0 ? __vector(o) : 0; }
    public ByteBuffer starsAsByteBuffer() { return __vector_as_bytebuffer(4, 12); }
    public ByteBuffer starsInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 4, 12); }

    public StarMetadata metadata(int j) { return metadata(new StarMetadata(), j); }
    public StarMetadata metadata(StarMetadata obj, int j) { int o = __offset(6); return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null; }
    public int metadataLength() { int o = __offset(6); return o != 0 ? __vector_len(o) : 0; }

    public Constellation constellations(int j) { return constellations(new Constellation(), j); }
    public Constellation constellations(Constellation obj, int j) { int o = __offset(8); return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null; }
    public int constellationsLength() { int o = __offset(8); return o != 0 ? __vector_len(o) : 0; }

    public DeepSkyObject dsos(int j) { return dsos(new DeepSkyObject(), j); }
    public DeepSkyObject dsos(DeepSkyObject obj, int j) { int o = __offset(10); return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null; }
    public int dsosLength() { int o = __offset(10); return o != 0 ? __vector_len(o) : 0; }

    public MeteorShower meteorShowers(int j) { return meteorShowers(new MeteorShower(), j); }
    public MeteorShower meteorShowers(MeteorShower obj, int j) { int o = __offset(12); return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null; }
    public int meteorShowersLength() { int o = __offset(12); return o != 0 ? __vector_len(o) : 0; }

    public static int createNightSkyCatalog(FlatBufferBuilder builder,
        int starsOffset,
        int metadataOffset,
        int constellationsOffset,
        int dsosOffset,
        int meteorShowersOffset) {
        builder.startTable(5);
        NightSkyCatalog.addMeteorShowers(builder, meteorShowersOffset);
        NightSkyCatalog.addDsos(builder, dsosOffset);
        NightSkyCatalog.addConstellations(builder, constellationsOffset);
        NightSkyCatalog.addMetadata(builder, metadataOffset);
        NightSkyCatalog.addStars(builder, starsOffset);
        return NightSkyCatalog.endNightSkyCatalog(builder);
    }

    public static int createNightSkyCatalog(FlatBufferBuilder builder,
        int starsOffset,
        int metadataOffset,
        int constellationsOffset,
        int dsosOffset) {
        return createNightSkyCatalog(builder, starsOffset, metadataOffset, constellationsOffset, dsosOffset, 0);
    }

    public static int createNightSkyCatalog(FlatBufferBuilder builder,
        int starsOffset,
        int metadataOffset,
        int constellationsOffset) {
        return createNightSkyCatalog(builder, starsOffset, metadataOffset, constellationsOffset, 0, 0);
    }

    public static void startNightSkyCatalog(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addStars(FlatBufferBuilder builder, int starsOffset) { builder.addOffset(0, starsOffset, 0); }
    public static void startStarsVector(FlatBufferBuilder builder, int numElems) { builder.startVector(12, numElems, 4); }
    public static void addMetadata(FlatBufferBuilder builder, int metadataOffset) { builder.addOffset(1, metadataOffset, 0); }
    public static int createMetadataVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]); return builder.endVector(); }
    public static void startMetadataVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static void addConstellations(FlatBufferBuilder builder, int constellationsOffset) { builder.addOffset(2, constellationsOffset, 0); }
    public static int createConstellationsVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]); return builder.endVector(); }
    public static void startConstellationsVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static void addDsos(FlatBufferBuilder builder, int dsosOffset) { builder.addOffset(3, dsosOffset, 0); }
    public static int createDsosVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]); return builder.endVector(); }
    public static void startDsosVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static void addMeteorShowers(FlatBufferBuilder builder, int meteorShowersOffset) { builder.addOffset(4, meteorShowersOffset, 0); }
    public static int createMeteorShowersVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]); return builder.endVector(); }
    public static void startMeteorShowersVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
    public static int endNightSkyCatalog(FlatBufferBuilder builder) {
        int o = builder.endTable();
        return o;
    }
    public static void finishNightSkyCatalogBuffer(FlatBufferBuilder builder, int offset) { builder.finish(offset); }
    public static void finishSizePrefixedNightSkyCatalogBuffer(FlatBufferBuilder builder, int offset) { builder.finishSizePrefixed(offset); }
}

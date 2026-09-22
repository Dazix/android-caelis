package com.dazix.caelis.data.nightsky;

/**
 * Deep Sky Object classification type.
 */
@SuppressWarnings("unused")
public final class DsoType {
    private DsoType() { }
    public static final byte GALAXY = 0;
    public static final byte GLOBULAR_CLUSTER = 1;
    public static final byte OPEN_CLUSTER = 2;
    public static final byte NEBULA = 3;
    public static final byte PLANETARY_NEBULA = 4;

    public static final String[] names = { "Galaxy", "GlobularCluster", "OpenCluster", "Nebula", "PlanetaryNebula" };

    public static String name(int e) { return names[e]; }
}

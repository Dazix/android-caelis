#!/usr/bin/env python3
"""
Offline Data Pipeline for Night Sky AR.
Packs star catalog data, star metadata, and constellation stick figures
into a high-performance Google FlatBuffers binary file (catalog.bin).
"""

import os
import sys
import math
import urllib.request
import gzip
import io
import flatbuffers

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUTPUT_RAW_BIN = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "raw", "catalog.bin")
OUTPUT_PIPELINE_BIN = os.path.join(os.path.dirname(__file__), "catalog.bin")

# FlatBuffers Python Schema Serialization

def pack_catalog(stars_data, star_names, constellations_data, output_path):
    """
    Serializes star list, metadata, and constellations using FlatBuffers.
    
    stars_data: list of (ra_deg, dec_deg, mag)
    star_names: list of (star_index, name_str)
    constellations_data: list of (constellation_name, [line_index_pairs])
    """
    builder = flatbuffers.Builder(1024 * 1024)

    # 1. Serialize Constellations
    constellation_offsets = []
    for name, line_indices in constellations_data:
        name_off = builder.CreateString(name)
        
        # Create lines vector: [int]
        builder.StartVector(4, len(line_indices), 4)
        for idx in reversed(line_indices):
            builder.PrependInt32(idx)
        lines_vec = builder.EndVector()
        
        # Start Constellation table
        builder.StartObject(2)
        builder.PrependUOffsetTRelativeSlot(0, name_off, 0)
        builder.PrependUOffsetTRelativeSlot(1, lines_vec, 0)
        const_off = builder.EndObject()
        constellation_offsets.append(const_off)

    builder.StartVector(4, len(constellation_offsets), 4)
    for c_off in reversed(constellation_offsets):
        builder.PrependUOffsetTRelative(c_off)
    constellations_vec = builder.EndVector()

    # 2. Serialize StarMetadata
    meta_offsets = []
    for star_idx, name in star_names:
        name_off = builder.CreateString(name)
        builder.StartObject(2)
        builder.PrependInt32Slot(0, star_idx, 0)
        builder.PrependUOffsetTRelativeSlot(1, name_off, 0)
        m_off = builder.EndObject()
        meta_offsets.append(m_off)

    builder.StartVector(4, len(meta_offsets), 4)
    for m_off in reversed(meta_offsets):
        builder.PrependUOffsetTRelative(m_off)
    metadata_vec = builder.EndVector()

    # 3. Serialize Stars Vector (Structs are 12 bytes: [ra: 4B, dec: 4B, mag: 4B])
    # Structs must be written in reverse order directly into the vector
    builder.StartVector(12, len(stars_data), 4)
    for ra, dec, mag in reversed(stars_data):
        builder.Prep(4, 12)
        builder.PrependFloat32(mag)
        builder.PrependFloat32(dec)
        builder.PrependFloat32(ra)
    stars_vec = builder.EndVector()

    # 4. Serialize Root NightSkyCatalog Table
    builder.StartObject(3)
    builder.PrependUOffsetTRelativeSlot(0, stars_vec, 0)
    builder.PrependUOffsetTRelativeSlot(1, metadata_vec, 0)
    builder.PrependUOffsetTRelativeSlot(2, constellations_vec, 0)
    root_catalog = builder.EndObject()

    builder.Finish(root_catalog)
    buf = builder.Output()

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(buf)

    print(f"Generated FlatBuffer binary ({len(buf):,} bytes) at {output_path}")
    print(f"  Total Stars: {len(stars_data):,}")
    print(f"  Named Stars Metadata: {len(star_names):,}")
    print(f"  Constellations: {len(constellations_data):,}")
    return buf

# Astronomical Data Fetching & Processing

def fetch_yale_bright_stars():
    """
    Downloads or provides the standard astronomical bright star catalog dataset (J2000.0).
    """
    raw_catalog_bin = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "raw", "stars_catalog.bin")
    stars = []
    
    if os.path.exists(raw_catalog_bin):
        import struct
        with open(raw_catalog_bin, "rb") as f:
            data = f.read()
        count = len(data) // 12
        for i in range(count):
            ra, dec, mag = struct.unpack_from("<fff", data, i * 12)
            stars.append((ra, dec, mag))
    else:
        # Fallback benchmark bright stars
        stars = [
            (101.287, -16.716, -1.44), # Sirius
            (95.988, -52.696, -0.62),  # Canopus
            (213.915, 19.182, -0.05),  # Arcturus
            (219.902, -60.834, -0.01), # Alpha Centauri
            (279.235, 38.784, 0.03),   # Vega
            (78.634, 45.998, 0.08),    # Capella
            (75.873, -8.202, 0.18),    # Rigel
            (114.825, 5.225, 0.40),    # Procyon
            (88.793, 7.407, 0.45),     # Betelgeuse
            (247.352, -26.432, 0.96),  # Antares
            (68.980, 16.509, 0.85),    # Aldebaran
            (310.358, 45.280, 1.25),   # Deneb
            (37.955, 89.264, 1.98),    # Polaris
        ]
    return stars

def find_closest_star_index(stars, target_ra, target_dec):
    """Finds index of star closest to target RA/Dec coordinates."""
    best_idx = 0
    best_dist = float("inf")
    for idx, (ra, dec, mag) in enumerate(stars):
        dra = (ra - target_ra) * math.cos(math.radians(dec))
        ddec = dec - target_dec
        dist = dra * dra + ddec * ddec
        if dist < best_dist:
            best_dist = dist
            best_idx = idx
    return best_idx

def build_constellations_and_names(stars):
    # Prominent Named Stars
    major_stars = [
        ("Sirius", 101.287, -16.716),
        ("Canopus", 95.988, -52.696),
        ("Arcturus", 213.915, 19.182),
        ("Vega", 279.235, 38.784),
        ("Capella", 78.634, 45.998),
        ("Rigel", 75.873, -8.202),
        ("Procyon", 114.825, 5.225),
        ("Betelgeuse", 88.793, 7.407),
        ("Achernar", 24.429, -57.237),
        ("Hadar", 210.956, -60.373),
        ("Altair", 297.696, 8.868),
        ("Acrux", 186.650, -63.099),
        ("Aldebaran", 68.980, 16.509),
        ("Antares", 247.352, -26.432),
        ("Spica", 201.298, -11.161),
        ("Pollux", 116.140, 28.026),
        ("Fomalhaut", 344.413, -29.622),
        ("Deneb", 310.358, 45.280),
        ("Regulus", 152.093, 11.967),
        ("Polaris", 37.955, 89.264),
    ]

    star_metadata = []
    for name, ra, dec in major_stars:
        idx = find_closest_star_index(stars, ra, dec)
        star_metadata.append((idx, name))

    # Constellations with stick figure lines (Pairs of indices: [A, B, B, C...])
    constellations = []

    # 1. Ursa Major (Big Dipper)
    um_coords = [
        (165.93, 61.75), # Dubhe (0)
        (165.46, 56.38), # Merak (1)
        (178.46, 53.69), # Phecda (2)
        (183.86, 57.03), # Megrez (3)
        (193.51, 55.96), # Alioth (4)
        (200.98, 54.92), # Mizar (5)
        (206.89, 49.31), # Alkaid (6)
    ]
    um_idx = [find_closest_star_index(stars, r, d) for r, d in um_coords]
    # Bowl: 0-1, 1-2, 2-3, 3-0; Handle: 3-4, 4-5, 5-6
    um_lines = [
        um_idx[0], um_idx[1],
        um_idx[1], um_idx[2],
        um_idx[2], um_idx[3],
        um_idx[3], um_idx[0],
        um_idx[3], um_idx[4],
        um_idx[4], um_idx[5],
        um_idx[5], um_idx[6],
    ]
    constellations.append(("Ursa Major", um_lines))

    # 2. Orion
    ori_coords = [
        (88.79, 7.41),   # Betelgeuse (0)
        (81.28, 6.35),   # Bellatrix (1)
        (84.05, -1.20),  # Mintaka (2)
        (84.41, -1.94),  # Alnilam (3)
        (85.19, -1.94),  # Alnitak (4)
        (86.94, -9.67),  # Saiph (5)
        (78.63, -8.20),  # Rigel (6)
    ]
    ori_idx = [find_closest_star_index(stars, r, d) for r, d in ori_coords]
    ori_lines = [
        ori_idx[0], ori_idx[4], # Betelgeuse to Belt
        ori_idx[1], ori_idx[2], # Bellatrix to Belt
        ori_idx[2], ori_idx[3], # Belt
        ori_idx[3], ori_idx[4], # Belt
        ori_idx[4], ori_idx[5], # Belt to Saiph
        ori_idx[2], ori_idx[6], # Belt to Rigel
        ori_idx[0], ori_idx[1], # Betelgeuse to Bellatrix
        ori_idx[5], ori_idx[6], # Saiph to Rigel
    ]
    constellations.append(("Orion", ori_lines))

    # 3. Cassiopeia (W shape)
    cas_coords = [
        (9.83, 59.15),   # Caph (0)
        (10.13, 56.54),  # Schedar (1)
        (14.18, 60.72),  # Gamma Cas (2)
        (20.40, 60.23),  # Ruchbah (3)
        (26.65, 63.67),  # Segin (4)
    ]
    cas_idx = [find_closest_star_index(stars, r, d) for r, d in cas_coords]
    cas_lines = [
        cas_idx[0], cas_idx[1],
        cas_idx[1], cas_idx[2],
        cas_idx[2], cas_idx[3],
        cas_idx[3], cas_idx[4],
    ]
    constellations.append(("Cassiopeia", cas_lines))

    # 4. Crux (Southern Cross)
    crux_coords = [
        (187.56, -57.11), # Gacrux (0)
        (186.65, -63.10), # Acrux (1)
        (191.93, -59.69), # Mimosa (2)
        (183.99, -58.75), # Delta Cru (3)
    ]
    crux_idx = [find_closest_star_index(stars, r, d) for r, d in crux_coords]
    crux_lines = [
        crux_idx[0], crux_idx[1], # Long axis
        crux_idx[2], crux_idx[3], # Cross axis
    ]
    constellations.append(("Crux", crux_lines))

    # 5. Cygnus (Northern Cross)
    cyg_coords = [
        (310.36, 45.28), # Deneb (0)
        (305.56, 40.26), # Sadr (1)
        (292.68, 27.96), # Albireo (2)
        (311.24, 33.97), # Gienah (3)
        (296.24, 45.13), # Delta Cyg (4)
    ]
    cyg_idx = [find_closest_star_index(stars, r, d) for r, d in cyg_coords]
    cyg_lines = [
        cyg_idx[0], cyg_idx[1],
        cyg_idx[1], cyg_idx[2], # Spine
        cyg_idx[4], cyg_idx[1],
        cyg_idx[1], cyg_idx[3], # Wings
    ]
    constellations.append(("Cygnus", cyg_lines))

    return star_metadata, constellations

def main():
    print("Loading astronomical star catalog dataset...")
    stars = fetch_yale_bright_stars()
    print(f"Loaded {len(stars)} stars.")

    metadata, constellations = build_constellations_and_names(stars)

    print("Packing into FlatBuffers format...")
    pack_catalog(stars, metadata, constellations, OUTPUT_RAW_BIN)
    pack_catalog(stars, metadata, constellations, OUTPUT_PIPELINE_BIN)
    print("FlatBuffers catalog generated successfully!")

if __name__ == "__main__":
    main()

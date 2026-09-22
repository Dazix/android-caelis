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
import csv
import re
import flatbuffers
from PIL import Image

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUTPUT_RAW_BIN = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "raw", "catalog.bin")
OUTPUT_PIPELINE_BIN = os.path.join(os.path.dirname(__file__), "catalog.bin")
CACHE_DIR = os.path.join(os.path.dirname(__file__), "cache")

HYG_V41_URL = "https://astronexus.com/downloads/catalogs/hygdata_v41.csv.gz"
STELLARIUM_LINES_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/constellationship.fab"
STELLARIUM_NAMES_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/constellation_names.eng.fab"
STELLARIUM_ASTERISM_LINES_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/asterism_lines.fab"
STELLARIUM_ASTERISM_NAMES_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/asterism_names.eng.fab"
STELLARIUM_ART_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/constellationsart.fab"

def parse_stellarium_art(stars_by_hip):
    """Parses constellationsart.fab and calculates exact RA/Dec coordinates for all 4 image corners via 3D Barycentric Inversion."""
    art_bytes = download_or_read_cache(STELLARIUM_ART_URL, "constellationsart.fab")
    art_text = art_bytes.decode("utf-8")
    
    art_data = {}
    for line in art_text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 11:
            continue
        abbr = parts[0]
        png_filename = parts[1]
        res_name = f"art_{abbr.lower()}"
        
        try:
            x1, y1, hip1 = float(parts[2]), float(parts[3]), int(parts[4])
            x2, y2, hip2 = float(parts[5]), float(parts[6]), int(parts[7])
            x3, y3, hip3 = float(parts[8]), float(parts[9]), int(parts[10])
            
            s1 = stars_by_hip.get(hip1)
            s2 = stars_by_hip.get(hip2)
            s3 = stars_by_hip.get(hip3)
            if not s1 or not s2 or not s3:
                continue
            
            # Determine exact image dimensions from cached source PNG
            png_path = os.path.join(CACHE_DIR, "art_pngs", png_filename)
            img_width, img_height = None, None
            if os.path.exists(png_path):
                try:
                    with Image.open(png_path) as img:
                        img_width, img_height = float(img.width), float(img.height)
                except Exception:
                    pass
            if img_width is None or img_height is None:
                max_coord = max(x1, x2, x3, y1, y2, y3)
                if max_coord <= 128.0:
                    img_width, img_height = 128.0, 128.0
                elif max_coord <= 256.0:
                    img_width, img_height = 256.0, 256.0
                else:
                    img_width, img_height = 512.0, 512.0

            # 1. Compute 3D unit vectors for the 3 anchor stars
            stars_ra_dec = [(s1["ra"], s1["dec"]), (s2["ra"], s2["dec"]), (s3["ra"], s3["dec"])]
            star_vectors = []
            for r_deg, d_deg in stars_ra_dec:
                r_rad = math.radians(r_deg)
                d_rad = math.radians(d_deg)
                vx = math.cos(d_rad) * math.cos(r_rad)
                vy = math.cos(d_rad) * math.sin(r_rad)
                vz = math.sin(d_rad)
                star_vectors.append((vx, vy, vz))

            # 2. 2D Affine / Barycentric Inversion for the 3 Anchor Pixels
            # Matrix A = [[x1, x2, x3], [y1, y2, y3], [1, 1, 1]]
            det = x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)
            if abs(det) < 1e-6:
                print(f"Warning: Degenerate anchor triangle for {abbr}")
                continue

            c11 = y2 - y3; c12 = y3 - y1; c13 = y1 - y2
            c21 = x3 - x2; c22 = x1 - x3; c23 = x2 - x1
            c31 = x2 * y3 - x3 * y2; c32 = x3 * y1 - x1 * y3; c33 = x1 * y2 - x2 * y1

            # 3. Solve for 4 Image Corners: TL (0,0), TR (W,0), BR (W,H), BL (0,H)
            corners = [(0.0, 0.0), (img_width, 0.0), (img_width, img_height), (0.0, img_height)]
            corner_ra = []
            corner_dec = []

            for px, py in corners:
                w1 = (c11 * px + c21 * py + c31) / det
                w2 = (c12 * px + c22 * py + c32) / det
                w3 = (c13 * px + c23 * py + c33) / det

                cvx = w1 * star_vectors[0][0] + w2 * star_vectors[1][0] + w3 * star_vectors[2][0]
                cvy = w1 * star_vectors[0][1] + w2 * star_vectors[1][1] + w3 * star_vectors[2][1]
                cvz = w1 * star_vectors[0][2] + w2 * star_vectors[1][2] + w3 * star_vectors[2][2]

                v_len = math.sqrt(cvx * cvx + cvy * cvy + cvz * cvz)
                if v_len < 1e-9:
                    cvx, cvy, cvz = 1.0, 0.0, 0.0
                else:
                    cvx /= v_len; cvy /= v_len; cvz /= v_len

                c_dec = math.degrees(math.asin(max(-1.0, min(1.0, cvz))))
                c_ra = (math.degrees(math.atan2(cvy, cvx))) % 360.0

                corner_ra.append(c_ra)
                corner_dec.append(c_dec)

            art_data[abbr] = (res_name, corner_ra, corner_dec)
        except Exception as e:
            print(f"Failed to process art for {abbr}: {e}")
            continue
            
    print(f"Computed 4-corner perspective coordinates for {len(art_data)} constellation artworks.")
    return art_data

def serialize_dsos(builder, dsos_data):
    """
    dsos_data: list of tuples:
    (messier_id, ra, dec, mag, size_arcmin, dso_type, czech_name, english_name, ngc_id, con_abbr,
     [position_angle_deg, axis_ratio, art_resource_name, art_width_deg, art_rotation_deg])
    """
    dso_offsets = []
    for item in dsos_data:
        m_id, ra, dec, mag, size_arcmin, dso_type, czech_name, english_name, ngc_id, con_abbr = item[:10]
        pos_angle = item[10] if len(item) > 10 else 0.0
        axis_ratio = item[11] if len(item) > 11 else 1.0
        art_res = item[12] if len(item) > 12 else ""
        art_width_deg = item[13] if len(item) > 13 else 0.0
        art_rot_deg = item[14] if len(item) > 14 else 0.0

        loc_off = builder.CreateString(czech_name) if czech_name else 0
        con_off = builder.CreateString(con_abbr) if con_abbr else 0
        eng_off = builder.CreateString(english_name) if english_name else 0
        ngc_off = builder.CreateString(ngc_id) if ngc_id else 0
        art_off = builder.CreateString(art_res) if art_res else 0

        # Start DeepSkyObject table (15 fields)
        builder.StartObject(15)
        builder.PrependInt16Slot(0, int(m_id), 0)
        builder.PrependFloat32Slot(1, float(ra), 0.0)
        builder.PrependFloat32Slot(2, float(dec), 0.0)
        builder.PrependFloat32Slot(3, float(mag), 0.0)
        builder.PrependFloat32Slot(4, float(size_arcmin), 0.0)
        builder.PrependByteSlot(5, int(dso_type), 0)
        if loc_off:
            builder.PrependUOffsetTRelativeSlot(6, loc_off, 0)
        if con_off:
            builder.PrependUOffsetTRelativeSlot(7, con_off, 0)
        if eng_off:
            builder.PrependUOffsetTRelativeSlot(8, eng_off, 0)
        if ngc_off:
            builder.PrependUOffsetTRelativeSlot(9, ngc_off, 0)
        builder.PrependFloat32Slot(10, float(pos_angle), 0.0)
        builder.PrependFloat32Slot(11, float(axis_ratio), 1.0)
        if art_off:
            builder.PrependUOffsetTRelativeSlot(12, art_off, 0)
        builder.PrependFloat32Slot(13, float(art_width_deg), 0.0)
        builder.PrependFloat32Slot(14, float(art_rot_deg), 0.0)
        d_off = builder.EndObject()
        dso_offsets.append(d_off)

    builder.StartVector(4, len(dso_offsets), 4)
    for d_off in reversed(dso_offsets):
        builder.PrependUOffsetTRelative(d_off)
    return builder.EndVector()

def serialize_meteor_showers(builder, meteor_showers_data):
    """
    meteor_showers_data: list of tuples:
    (id, localized_name, ra, dec, zhr, peak_month, peak_day, constellation_id)
    """
    offsets = []
    for item in meteor_showers_data:
        m_id, loc_name, ra, dec, zhr, peak_m, peak_d, con_id = item
        loc_off = builder.CreateString(loc_name) if loc_name else 0
        con_off = builder.CreateString(con_id) if con_id else 0

        # Start MeteorShower table (8 fields)
        builder.StartObject(8)
        builder.PrependInt16Slot(0, int(m_id), 0)
        if loc_off:
            builder.PrependUOffsetTRelativeSlot(1, loc_off, 0)
        builder.PrependFloat32Slot(2, float(ra), 0.0)
        builder.PrependFloat32Slot(3, float(dec), 0.0)
        builder.PrependInt16Slot(4, int(zhr), 0)
        builder.PrependByteSlot(5, int(peak_m), 0)
        builder.PrependByteSlot(6, int(peak_d), 0)
        if con_off:
            builder.PrependUOffsetTRelativeSlot(7, con_off, 0)
        off = builder.EndObject()
        offsets.append(off)

    builder.StartVector(4, len(offsets), 4)
    for off in reversed(offsets):
        builder.PrependUOffsetTRelative(off)
    return builder.EndVector()

def pack_catalog(stars_data, star_names, constellations_data, dsos_data, meteor_showers_data, output_path):
    """
    Serializes star list, metadata, constellations (including asterisms), DSOs, and Meteor Showers using FlatBuffers.
    
    stars_data: list of (ra_deg, dec_deg, mag)
    star_names: list of (star_index, name_str, body_type, constel, dist_ly)
    constellations_data: list of (name, c_ra, c_dec, lines, loc_name, bright, fam, is_ast, art_res, art_corner_ra, art_corner_dec)
    dsos_data: list of (messier_id, ra, dec, mag, size_arcmin, dso_type, czech_name, english_name, ngc_id, con_abbr)
    meteor_showers_data: list of (id, localized_name, ra, dec, zhr, peak_month, peak_day, constellation_id)
    """
    builder = flatbuffers.Builder(1024 * 1024)

    # 1. Serialize Constellations and Asterisms
    constellation_offsets = []
    for name, c_ra, c_dec, line_indices, loc_name, bright_star, fam, is_ast, art_res, art_corner_ra, art_corner_dec in constellations_data:
        name_off = builder.CreateString(name)
        loc_name_off = builder.CreateString(loc_name) if loc_name else 0
        bright_off = builder.CreateString(bright_star) if bright_star else 0
        fam_off = builder.CreateString(fam) if fam else 0
        art_res_off = builder.CreateString(art_res) if art_res else 0
        
        # Create lines vector: [int]
        builder.StartVector(4, len(line_indices), 4)
        for idx in reversed(line_indices):
            builder.PrependInt32(idx)
        lines_vec = builder.EndVector()

        # Create art corner vectors: [float]
        if art_corner_ra:
            builder.StartVector(4, len(art_corner_ra), 4)
            for r in reversed(art_corner_ra):
                builder.PrependFloat32(r)
            art_corner_ra_vec = builder.EndVector()
        else:
            art_corner_ra_vec = 0

        if art_corner_dec:
            builder.StartVector(4, len(art_corner_dec), 4)
            for d in reversed(art_corner_dec):
                builder.PrependFloat32(d)
            art_corner_dec_vec = builder.EndVector()
        else:
            art_corner_dec_vec = 0
        
        # Start Constellation table (11 fields)
        builder.StartObject(11)
        builder.PrependUOffsetTRelativeSlot(0, name_off, 0)
        builder.PrependFloat32Slot(1, float(c_ra), 0.0)
        builder.PrependFloat32Slot(2, float(c_dec), 0.0)
        builder.PrependUOffsetTRelativeSlot(3, lines_vec, 0)
        if loc_name_off:
            builder.PrependUOffsetTRelativeSlot(4, loc_name_off, 0)
        if bright_off:
            builder.PrependUOffsetTRelativeSlot(5, bright_off, 0)
        if fam_off:
            builder.PrependUOffsetTRelativeSlot(6, fam_off, 0)
        builder.PrependBoolSlot(7, bool(is_ast), False)
        if art_res_off:
            builder.PrependUOffsetTRelativeSlot(8, art_res_off, 0)
        if art_corner_ra_vec:
            builder.PrependUOffsetTRelativeSlot(9, art_corner_ra_vec, 0)
        if art_corner_dec_vec:
            builder.PrependUOffsetTRelativeSlot(10, art_corner_dec_vec, 0)
        const_off = builder.EndObject()
        constellation_offsets.append(const_off)

    builder.StartVector(4, len(constellation_offsets), 4)
    for c_off in reversed(constellation_offsets):
        builder.PrependUOffsetTRelative(c_off)
    constellations_vec = builder.EndVector()

    # 2. Serialize StarMetadata
    meta_offsets = []
    for star_idx, name, body_type, constel, dist_ly in star_names:
        name_off = builder.CreateString(name)
        type_off = builder.CreateString(body_type) if body_type else 0
        const_off = builder.CreateString(constel) if constel else 0
        builder.StartObject(5)
        builder.PrependInt32Slot(0, star_idx, 0)
        builder.PrependUOffsetTRelativeSlot(1, name_off, 0)
        if type_off:
            builder.PrependUOffsetTRelativeSlot(2, type_off, 0)
        if const_off:
            builder.PrependUOffsetTRelativeSlot(3, const_off, 0)
        builder.PrependFloat32Slot(4, float(dist_ly), 0.0)
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

    # 4. Serialize DSOs Vector
    dsos_vec = serialize_dsos(builder, dsos_data)

    # 5. Serialize Meteor Showers Vector
    meteor_showers_vec = serialize_meteor_showers(builder, meteor_showers_data)

    # 6. Serialize Root NightSkyCatalog Table (5 fields)
    builder.StartObject(5)
    builder.PrependUOffsetTRelativeSlot(0, stars_vec, 0)
    builder.PrependUOffsetTRelativeSlot(1, metadata_vec, 0)
    builder.PrependUOffsetTRelativeSlot(2, constellations_vec, 0)
    builder.PrependUOffsetTRelativeSlot(3, dsos_vec, 0)
    builder.PrependUOffsetTRelativeSlot(4, meteor_showers_vec, 0)
    root_catalog = builder.EndObject()

    builder.Finish(root_catalog)
    buf = builder.Output()

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(buf)

    print(f"Generated FlatBuffer binary ({len(buf):,} bytes) at {output_path}")
    print(f"  Total Stars: {len(stars_data):,}")
    print(f"  Named Stars Metadata: {len(star_names):,}")
    print(f"  Constellations & Asterisms: {len(constellations_data):,}")
    print(f"  Deep Sky Objects (Messier): {len(dsos_data):,}")
    return buf

# 88 IAU Constellations: Czech Localized Names
CZECH_CONSTELLATION_NAMES = {
    "And": "Andromeda",
    "Ant": "Vývěva",
    "Aps": "Rajka",
    "Aqr": "Vodnář",
    "Aql": "Orel",
    "Ara": "Oltář",
    "Ari": "Beran",
    "Aur": "Vozka",
    "Boo": "Pastýř",
    "Cae": "Rycí rytidlo",
    "Cam": "Žirafa",
    "Cnc": "Rak",
    "CVn": "Honicí psi",
    "CMa": "Velký pes",
    "CMi": "Malý pes",
    "Cap": "Kozoroh",
    "Car": "Lodní kýl",
    "Cas": "Kasiopeja",
    "Cen": "Kentaur",
    "Cep": "Cefeus",
    "Cet": "Velryba",
    "Cha": "Chameleon",
    "Cir": "Kružítko",
    "Col": "Holubice",
    "Com": "Vlasy Bereniky",
    "CrA": "Jižní koruna",
    "CrB": "Severní koruna",
    "Crv": "Havran",
    "Crt": "Pohár",
    "Cru": "Jižní kříž",
    "Cyg": "Labuť",
    "Del": "Delfín",
    "Dor": "Mečoun",
    "Dra": "Drak",
    "Equ": "Koníček",
    "Eri": "Eridanus",
    "For": "Pec",
    "Gem": "Blíženci",
    "Gru": "Jeřáb",
    "Her": "Herkules",
    "Hor": "Hodiny",
    "Hya": "Hydra",
    "Hyi": "Malý vodní had",
    "Ind": "Indián",
    "Lac": "Ještěrka",
    "Leo": "Lev",
    "LMi": "Malý lev",
    "Lep": "Zajíc",
    "Lib": "Váhy",
    "Lup": "Vlk",
    "Lyn": "Rys",
    "Lyr": "Lyra",
    "Men": "Tabulová hora",
    "Mic": "Mikroskop",
    "Mon": "Jednorožec",
    "Mus": "Moucha",
    "Nor": "Pravítko",
    "Oct": "Oktant",
    "Oph": "Hadonoš",
    "Ori": "Orion",
    "Pav": "Páv",
    "Peg": "Pegas",
    "Per": "Perseus",
    "Phe": "Fénix",
    "Pic": "Malíř",
    "Psc": "Ryby",
    "PsA": "Jižní ryba",
    "Pup": "Kormidlo",
    "Pyx": "Kompas",
    "Ret": "Síť",
    "Sge": "Šíp",
    "Sgr": "Střelec",
    "Sco": "Štír",
    "Scl": "Sochař",
    "Sct": "Štít",
    "Ser": "Had",
    "Sex": "Sextant",
    "Tau": "Býk",
    "Tel": "Dalekohled",
    "Tri": "Trojúhelník",
    "TrA": "Jižní trojúhelník",
    "Tuc": "Tukan",
    "UMa": "Velká medvědice",
    "UMi": "Malý medvěd",
    "Vel": "Plachty",
    "Vir": "Panna",
    "Vol": "Létající ryba",
    "Vul": "Lištička",
}

# 88 IAU Constellations: Constellation Families
CONSTELLATION_FAMILIES = {
    "UMa": "Ursa Major Family",
    "UMi": "Ursa Major Family",
    "Dra": "Ursa Major Family",
    "CVn": "Ursa Major Family",
    "Boo": "Ursa Major Family",
    "Com": "Ursa Major Family",
    "CrB": "Ursa Major Family",
    "Cam": "Ursa Major Family",
    "Lyn": "Ursa Major Family",
    "LMi": "Ursa Major Family",
    # Zodiac
    "Ari": "Zodiac",
    "Tau": "Zodiac",
    "Gem": "Zodiac",
    "Cnc": "Zodiac",
    "Leo": "Zodiac",
    "Vir": "Zodiac",
    "Lib": "Zodiac",
    "Sco": "Zodiac",
    "Sgr": "Zodiac",
    "Cap": "Zodiac",
    "Aqr": "Zodiac",
    "Psc": "Zodiac",
    # Perseus Family
    "Cas": "Perseus Family",
    "Cep": "Perseus Family",
    "And": "Perseus Family",
    "Per": "Perseus Family",
    "Peg": "Perseus Family",
    "Tri": "Perseus Family",
    "Aur": "Perseus Family",
    "Lac": "Perseus Family",
    "Cet": "Heavenly Waters",
    # Hercules Family
    "Her": "Hercules Family",
    "Sge": "Hercules Family",
    "Aql": "Hercules Family",
    "Vul": "Hercules Family",
    "Lyr": "Hercules Family",
    "Oph": "Hercules Family",
    "Ser": "Hercules Family",
    "Sct": "Hercules Family",
    "CrA": "Hercules Family",
    "Ara": "Hercules Family",
    "Tel": "La Caille Family",
    "Cen": "Hercules Family",
    "Lup": "Hercules Family",
    "Crv": "Hercules Family",
    "Crt": "Hercules Family",
    "Hya": "Hercules Family",
    "Sex": "Hercules Family",
    "Cru": "Heavenly Waters",
    "Cyg": "Hercules Family",
    # Orion Family
    "Ori": "Orion Family",
    "CMa": "Orion Family",
    "CMi": "Orion Family",
    "Mon": "Orion Family",
    "Lep": "Orion Family",
    # Heavenly Waters
    "Del": "Heavenly Waters",
    "Equ": "Heavenly Waters",
    "Eri": "Heavenly Waters",
    "PsA": "Heavenly Waters",
    "Car": "Heavenly Waters",
    "Pup": "Heavenly Waters",
    "Vel": "Heavenly Waters",
    "Pyx": "Heavenly Waters",
    "Col": "Heavenly Waters",
    # Bayer Group
    "Cha": "Bayer Group",
    "Aps": "Bayer Group",
    "Dor": "Bayer Group",
    "Gru": "Bayer Group",
    "Hyi": "Bayer Group",
    "Ind": "Bayer Group",
    "Mus": "Bayer Group",
    "Pav": "Bayer Group",
    "Phe": "Bayer Group",
    "TrA": "Bayer Group",
    "Tuc": "Bayer Group",
    "Vol": "Bayer Group",
    # La Caille Family
    "Ant": "La Caille Family",
    "Cae": "La Caille Family",
    "Cir": "La Caille Family",
    "For": "La Caille Family",
    "Hor": "La Caille Family",
    "Men": "La Caille Family",
    "Mic": "La Caille Family",
    "Nor": "La Caille Family",
    "Oct": "La Caille Family",
    "Pic": "La Caille Family",
    "Ret": "La Caille Family",
    "Scl": "La Caille Family",
}

# Asterisms: Czech Localized Names
CZECH_ASTERISM_NAMES = {
    "Big Dipper (Plough)": "Velký vůz",
    "Big Dipper": "Velký vůz",
    "Little Dipper": "Malý vůz",
    "Summer Triangle": "Letní trojúhelník",
    "Winter Triangle": "Zimní trojúhelník",
    "Winter Hexagon (Winter Circle)": "Zimní šestiúhelník",
    "Great Square of Pegasus": "Pegasův čtverec",
    "Northern Cross": "Severní kříž",
    "Keystone": "Základní kámen",
    "Butterfly": "Motýl",
    "Kite": "Drak",
    "Sail": "Plachta",
    "Coathanger": "Ramínko",
    "Job's Coffin": "Jobova rakev",
    "V of Taurus": "Véčko v Býku",
    "Triangulum Minus": "Malý trojúhelník",
    "Ptolemy's Triangle": "Ptolemaiův trojúhelník",
    "Seven Stars Row": "Řada sedmi hvězd",
    "The Pointers": "Ukazatelé",
    "Dragon Head (Lozenge)": "Hlava draka",
    "Head of Medusa Gorgon": "Hlava Medúzy",
    "Goatlings (Kids)": "Kůzlata",
    "The Y in Aquarius (The Urn or Water Jar)": "Džbán Vodnáře",
    "Orion's Belt": "Orionův pás",
    "Orion's Sword": "Orionův meč",
    "Orion's Shield": "Orionův štít",
    "Orion's Cudgel": "Orionův kyj",
    "Head of Whale": "Hlava Velryby",
    "Head of Serpent": "Hlava Hada",
    "Head of Hydra": "Hlava Hydry",
    "Teapot": "Čajník",
    "Milk Dipper": "Mléčný vůz",
    "Terebellum": "Terebellum",
    "Egyptian Cross": "Egyptský kříž",
    "False Cross": "Nepravý kříž",
    "Sickle": "Srp",
    "Diamond Cross": "Diamantový kříž",
    "Circlet": "Kroužek",
    "The Arc of the King": "Královský oblouk",
    "Head of Centaurus": "Hlava Kentaura",
    "Sudor Ophiuchi": "Sudor Ophiuchi",
    "Venus' Mirror": "Venušino zrcátko",
    "The Butterfly of Orion": "Motýl v Orionu",
    "The Fish Hook": "Rybářský háček",
    "The Bull of Poniatowski": "Býk Poniatowského",
    "The Trapezoid": "Lichoběžník",
    "The Three Patriarchs": "Tři patriarchové",
    "The Northern Fly": "Severní moucha",
    "Great Diamond": "Diamant",
    "Spring Triangle": "Jarní trojúhelník",
    "Segment of Perseus": "Perseův oblouk",
    "Heavenly G": "Nebeské G",
    "W": "W (Kasiopeja)",
    "Bier": "Nosítka",
    "Guardians of the Pole (Guards)": "Strážci pólu",
    "Mini-Cassiopeia": "Malá Kasiopeja",
    "Mini-Dipper": "Malý vůz (Mini)",
    "Davis' Dog": "Davisův pes",
    "Honores Friderici (Frederick's Glory)": "Sláva Fridricha",
    "Christmas Goose": "Vánoční husa",
    "The Arrow": "Šíp",
    "The Flying Minnow": "Létající střevle",
    "Harrington 7": "Harrington 7",
    "Red-Necked Emu": "Emu",
}

# Asterisms: Parent Constellations
ASTERISM_PARENTS = {
    "BDr": "Ursa Major",
    "LDr": "Ursa Minor",
    "STr": "Lyra / Cygnus / Aquila",
    "WTr": "Orion / Canis Major",
    "WHx": "Winter Hexagon",
    "GDi": "Spring Diamond",
    "SpT": "Spring Triangle",
    "GSP": "Pegasus",
    "HeG": "Orion / Taurus / Gemini",
    "SoP": "Perseus",
    "NCr": "Cygnus",
    "WAs": "Cassiopeia",
    "KSt": "Hercules",
    "Bfl": "Hercules",
    "Kit": "Boötes",
    "Sal": "Vela / Carina",
    "CoH": "Vulpecula",
    "JbC": "Delphinus",
    "VTa": "Taurus",
    "TrM": "Triangulum",
    "TrP": "Boötes",
    "Bie": "Ursa Major",
    "GoP": "Ursa Minor",
    "MCs": "Cassiopeia",
    "SSR": "Andromeda / Cassiopeia",
    "Ptr": "Ursa Major",
    "HDr": "Draco",
    "HMG": "Perseus",
    "Goa": "Auriga",
    "Urn": "Aquarius",
    "OrB": "Orion",
    "OrS": "Orion",
    "OSh": "Orion",
    "OrC": "Orion",
    "HWl": "Cetus",
    "HSr": "Serpens",
    "HHy": "Hydra",
    "Tea": "Sagittarius",
    "Mlk": "Sagittarius",
    "Ter": "Sagittarius",
    "ECr": "Canis Major",
    "FCr": "Vela / Carina",
    "Sic": "Leo",
    "DCr": "Carina",
    "Cir": "Pisces",
    "TAK": "Pegasus",
    "HCn": "Centaurus",
    "SuO": "Ophiuchus",
    "VMr": "Orion",
    "OBf": "Orion",
    "FHA": "Scorpius",
    "BPN": "Ophiuchus",
    "TrB": "Boötes",
    "TPT": "Cassiopeia",
    "NFy": "Aries",
    "BA1": "Taurus",
    "BA2": "Andromeda / Lacerta",
    "ChG": "Perseus",
    "Arr": "Centaurus",
    "FMi": "Auriga / Gemini",
    "Hr7": "Corona Borealis",
    "TA7": "Ursa Major",
    "TA10": "Vulpecula",
}

def classify_spectral_type(spect, mag, dist_ly):
    """Provides a human-readable astrophysics body type from spectral class and luminosity."""
    if not spect:
        return "Star"
    s = spect.upper()
    if "IA" in s or "IB" in s or "I" in s and ("O" in s or "B" in s or "A" in s):
        if "M" in s or "K" in s:
            return "Red Supergiant"
        elif "B" in s or "O" in s:
            return "Blue Supergiant"
        else:
            return "Supergiant"
    if "III" in s:
        if "M" in s or "K" in s:
            return "Red Giant"
        elif "B" in s or "A" in s:
            return "Blue Giant"
        else:
            return "Giant Star"
    if "IV" in s:
        return "Subgiant"
    if "V" in s or "D" in s:
        if "M" in s:
            return "Red Dwarf"
        elif "A" in s or "F" in s or "G" in s:
            return "Main Sequence"
    if s.startswith("O") or s.startswith("B"):
        return "Blue Giant"
    if s.startswith("M") or s.startswith("K"):
        return "Orange/Red Star"
    return "Main Sequence"

def download_or_read_cache(url, filename):
    """Downloads a file or returns the cached version from disk."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    cache_path = os.path.join(CACHE_DIR, filename)
    if os.path.exists(cache_path) and os.path.getsize(cache_path) > 0:
        print(f"Reading from cache: {cache_path}")
        with open(cache_path, "rb") as f:
            return f.read()
    
    print(f"Downloading from {url}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (CAELIS/1.0)"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    
    with open(cache_path, "wb") as f:
        f.write(data)
    print(f"Cached {len(data):,} bytes to {cache_path}")
    return data

def parse_stellarium_data():
    """Parses Stellarium constellation lines and official English names."""
    lines_bytes = download_or_read_cache(STELLARIUM_LINES_URL, "constellationship.fab")
    names_bytes = download_or_read_cache(STELLARIUM_NAMES_URL, "constellation_names.eng.fab")
    
    lines_text = lines_bytes.decode("utf-8")
    names_text = names_bytes.decode("utf-8")
    
    # 1. Parse official names
    english_names = {}
    for line in names_text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            abbr = parts[0].strip()
            name = parts[1].strip().strip('"')
            english_names[abbr] = name
    
    # 2. Parse stick figures (lines)
    constellation_lines = {}
    for line in lines_text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 3:
            abbr = parts[0]
            num_pairs = int(parts[1])
            hip_ids = [int(x) for x in parts[2:]]
            constellation_lines[abbr] = hip_ids
            
    print(f"Parsed {len(constellation_lines)} IAU constellation shapes and {len(english_names)} names.")
    return constellation_lines, english_names

def parse_stellarium_asterisms():
    """Parses Stellarium asterism lines and official English names."""
    lines_bytes = download_or_read_cache(STELLARIUM_ASTERISM_LINES_URL, "asterism_lines.fab")
    names_bytes = download_or_read_cache(STELLARIUM_ASTERISM_NAMES_URL, "asterism_names.eng.fab")

    lines_text = lines_bytes.decode("utf-8")
    names_text = names_bytes.decode("utf-8")

    # 1. Parse asterism English names
    english_names = {}
    for line in names_text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r'^(\S+)\s+_?\(\"([^\"]+)\"', line)
        if m:
            abbr = m.group(1).strip()
            name = m.group(2).strip()
            english_names[abbr] = name

    # 2. Parse asterism lines (flag == 1 for standard naked-eye asterisms using HIP pairs)
    asterism_lines = {}
    for line in lines_text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 4:
            abbr = parts[0]
            try:
                flag = int(parts[1])
                num_pairs = int(parts[2])
                if flag == 1:
                    hip_ids = [int(x) for x in parts[3:]]
                    asterism_lines[abbr] = hip_ids
            except Exception:
                continue

    print(f"Parsed {len(asterism_lines)} modern asterism shapes and {len(english_names)} asterism names.")
    return asterism_lines, english_names

def load_hyg_database():
    """Downloads and parses the HYG v4.1 database."""
    hyg_gz_bytes = download_or_read_cache(HYG_V41_URL, "hygdata_v41.csv.gz")
    
    stars_by_hip = {}
    all_hyg_stars = []
    
    with gzip.GzipFile(fileobj=io.BytesIO(hyg_gz_bytes)) as gz:
        reader = csv.DictReader(io.TextIOWrapper(gz, encoding="utf-8"))
        for row in reader:
            if row.get("id") == "0":  # Sol
                continue
            try:
                hip_str = row.get("hip", "").strip()
                hip_id = int(hip_str) if hip_str else None
                ra_hours = float(row.get("ra", 0.0))
                dec_deg = float(row.get("dec", 0.0))
                mag = float(row.get("mag", 99.0))
                dist_pc = float(row.get("dist", 0.0)) if row.get("dist") else 0.0
                proper = row.get("proper", "").strip()
                bayer = row.get("bayer", "").strip()
                flam = row.get("flam", "").strip()
                con = row.get("con", "").strip()
                spect = row.get("spect", "").strip()
                
                ra_deg = (ra_hours * 15.0) % 360.0
                dist_ly = dist_pc * 3.262
                
                # Determine standard display name
                if proper:
                    display_name = proper
                elif bayer and con:
                    display_name = f"{bayer} {con}"
                elif flam and con:
                    display_name = f"{flam} {con}"
                elif hip_id:
                    display_name = f"HIP {hip_id}"
                else:
                    display_name = "Star"
                    
                body_type = classify_spectral_type(spect, mag, dist_ly)
                constel_full = CZECH_CONSTELLATION_NAMES.get(con, con) if con else ""
                
                star_entry = {
                    "hip": hip_id,
                    "ra": ra_deg,
                    "dec": dec_deg,
                    "mag": mag,
                    "dist_ly": dist_ly,
                    "proper": proper,
                    "display_name": display_name,
                    "con": con,
                    "body_type": body_type,
                    "spect": spect,
                }
                
                if hip_id:
                    stars_by_hip[hip_id] = star_entry
                all_hyg_stars.append(star_entry)
            except Exception:
                continue
                
    print(f"Loaded {len(all_hyg_stars):,} stars from HYG database ({len(stars_by_hip):,} with HIP ID).")
    return all_hyg_stars, stars_by_hip

def build_astronomical_catalog():
    print("=== Step 1: Parsing Stellarium IAU stick figures, Asterisms & names ===")
    constellation_lines, english_names = parse_stellarium_data()
    asterism_lines, asterism_names = parse_stellarium_asterisms()

    print("=== Step 2: Loading authentic HYG Database v4.1 ===")
    all_hyg_stars, stars_by_hip = load_hyg_database()

    print("=== Step 3: Filtering & Cross-Referencing Dataset ===")
    # Collect all HIP IDs referenced by constellation lines and asterism lines (mandatory inclusion)
    mandatory_hip_ids = set()
    for abbr, hip_list in constellation_lines.items():
        for hip_id in hip_list:
            mandatory_hip_ids.add(hip_id)

    for abbr, hip_list in asterism_lines.items():
        for hip_id in hip_list:
            mandatory_hip_ids.add(hip_id)
            
    print(f"Found {len(mandatory_hip_ids)} unique stars referenced across all 88 IAU constellations and {len(asterism_lines)} asterisms.")

    # Select stars: mag <= 9.0 OR mandatory constellation/asterism star
    selected_stars = []
    selected_hips = set()
    
    for star in all_hyg_stars:
        hip_id = star["hip"]
        if (star["mag"] <= 9.0) or (hip_id is not None and hip_id in mandatory_hip_ids):
            selected_stars.append(star)
            if hip_id is not None:
                selected_hips.add(hip_id)
                
    # Sort selected stars by brightness (mag ascending: Sirius first)
    selected_stars.sort(key=lambda s: s["mag"])
    
    # Map HIP ID to array index in sorted stars
    hip_to_catalog_index = {}
    stars_data = []
    star_metadata = []
    
    for idx, star in enumerate(selected_stars):
        hip_id = star["hip"]
        if hip_id is not None:
            hip_to_catalog_index[hip_id] = idx
            
        stars_data.append((star["ra"], star["dec"], star["mag"]))
        
        # If star has proper name or is prominent (mag <= 3.5), store rich metadata
        if star["proper"] or star["mag"] <= 3.5:
            name = star["display_name"]
            body_type = star["body_type"]
            constel_name = CZECH_CONSTELLATION_NAMES.get(star["con"], star["con"]) if star["con"] else ""
            dist_ly = star["dist_ly"]
            star_metadata.append((idx, name, body_type, constel_name, dist_ly))

    print(f"Selected {len(stars_data):,} catalog stars and {len(star_metadata):,} rich metadata records.")

    print("=== Step 4: Parsing Stellarium Constellation Artwork Mapping ===")
    art_data = parse_stellarium_art(stars_by_hip)

    print("=== Step 5: Constructing 88 IAU Constellations with Authentic Geometry & Artwork ===")
    constellations_data = []
    
    for abbr in sorted(constellation_lines.keys()):
        hip_list = constellation_lines[abbr]
        eng_name = english_names.get(abbr, abbr)
        czech_name = CZECH_CONSTELLATION_NAMES.get(abbr, eng_name)
        family = CONSTELLATION_FAMILIES.get(abbr, "General")
        
        # Build line indices pairs
        line_indices = []
        member_ras = []
        member_decs = []
        member_stars = []
        
        for i in range(0, len(hip_list), 2):
            if i + 1 < len(hip_list):
                hip1 = hip_list[i]
                hip2 = hip_list[i + 1]
                idx1 = hip_to_catalog_index.get(hip1)
                idx2 = hip_to_catalog_index.get(hip2)
                if idx1 is not None and idx2 is not None:
                    line_indices.extend([idx1, idx2])
                    star1 = selected_stars[idx1]
                    star2 = selected_stars[idx2]
                    member_ras.extend([star1["ra"], star2["ra"]])
                    member_decs.extend([star1["dec"], star2["dec"]])
                    member_stars.extend([star1, star2])
        
        # Calculate spherical centroid
        if member_ras and member_decs:
            vx = sum(math.cos(math.radians(d)) * math.cos(math.radians(r)) for r, d in zip(member_ras, member_decs)) / len(member_ras)
            vy = sum(math.cos(math.radians(d)) * math.sin(math.radians(r)) for r, d in zip(member_ras, member_decs)) / len(member_ras)
            vz = sum(math.sin(math.radians(d)) for d in member_decs) / len(member_decs)
            
            centroid_ra = (math.degrees(math.atan2(vy, vx))) % 360.0
            centroid_dec = math.degrees(math.asin(max(-1.0, min(1.0, vz / max(1e-6, math.sqrt(vx*vx + vy*vy + vz*vz))))))
            
            # Find brightest star in constellation
            brightest = min(member_stars, key=lambda s: s["mag"])
            brightest_star_name = brightest["proper"] if brightest["proper"] else brightest["display_name"]
        else:
            centroid_ra = 0.0
            centroid_dec = 0.0
            brightest_star_name = ""

        # Artwork placement
        art_info = art_data.get(abbr)
        if art_info:
            art_res_name, art_corner_ra, art_corner_dec = art_info
        else:
            art_res_name = ""
            art_corner_ra, art_corner_dec = [], []

        constellations_data.append((
            eng_name,
            centroid_ra,
            centroid_dec,
            line_indices,
            czech_name,
            brightest_star_name,
            family,
            False, # is_asterism = False
            art_res_name,
            art_corner_ra,
            art_corner_dec
        ))

    print(f"Processed {len(constellations_data)} IAU constellations.")

    print("=== Step 6: Constructing Modern Asterisms with Geometry & Localization ===")
    asterisms_data = []

    for abbr in sorted(asterism_lines.keys()):
        hip_list = asterism_lines[abbr]
        eng_name = asterism_names.get(abbr, abbr)
        czech_name = CZECH_ASTERISM_NAMES.get(eng_name, CZECH_ASTERISM_NAMES.get(abbr, eng_name))
        family = ASTERISM_PARENTS.get(abbr, "General")

        line_indices = []
        member_ras = []
        member_decs = []
        member_stars = []

        for i in range(0, len(hip_list), 2):
            if i + 1 < len(hip_list):
                hip1 = hip_list[i]
                hip2 = hip_list[i + 1]
                idx1 = hip_to_catalog_index.get(hip1)
                idx2 = hip_to_catalog_index.get(hip2)
                if idx1 is not None and idx2 is not None:
                    line_indices.extend([idx1, idx2])
                    star1 = selected_stars[idx1]
                    star2 = selected_stars[idx2]
                    member_ras.extend([star1["ra"], star2["ra"]])
                    member_decs.extend([star1["dec"], star2["dec"]])
                    member_stars.extend([star1, star2])

        if member_ras and member_decs:
            vx = sum(math.cos(math.radians(d)) * math.cos(math.radians(r)) for r, d in zip(member_ras, member_decs)) / len(member_ras)
            vy = sum(math.cos(math.radians(d)) * math.sin(math.radians(r)) for r, d in zip(member_ras, member_decs)) / len(member_ras)
            vz = sum(math.sin(math.radians(d)) for d in member_decs) / len(member_decs)

            centroid_ra = (math.degrees(math.atan2(vy, vx))) % 360.0
            centroid_dec = math.degrees(math.asin(max(-1.0, min(1.0, vz / max(1e-6, math.sqrt(vx*vx + vy*vy + vz*vz))))))

            brightest = min(member_stars, key=lambda s: s["mag"])
            brightest_star_name = brightest["proper"] if brightest["proper"] else brightest["display_name"]
        else:
            centroid_ra = 0.0
            centroid_dec = 0.0
            brightest_star_name = ""

        asterisms_data.append((
            eng_name,
            centroid_ra,
            centroid_dec,
            line_indices,
            czech_name,
            brightest_star_name,
            family,
            True, # is_asterism = True
            "",   # Asterisms have no separate artwork illustration
            [],
            []
        ))

    print(f"Processed {len(asterisms_data)} Asterisms.")
    all_constellations_and_asterisms = constellations_data + asterisms_data

    return stars_data, star_metadata, all_constellations_and_asterisms

MESSIER_OBJECTS = [
    (1, 83.633, 22.017, 8.4, 6.0, 3, "Krabí mlhovina", "Crab Nebula", "NGC 1952", "Tau", 124.0, 0.67, "", 0.0, 0.0),
    (2, 323.363, -0.823, 6.5, 16.0, 1, "Kulová hvězdokupa M2", "Globular Cluster M2", "NGC 7089", "Aqr", 0.0, 1.0, "", 0.0, 0.0),
    (3, 205.546, 28.377, 6.2, 18.0, 1, "Kulová hvězdokupa M3", "Globular Cluster M3", "NGC 5272", "CVn", 0.0, 1.0, "", 0.0, 0.0),
    (4, 245.897, -26.526, 5.9, 36.0, 1, "Kulová hvězdokupa M4", "Globular Cluster M4", "NGC 6121", "Sco", 0.0, 1.0, "", 0.0, 0.0),
    (5, 229.638, 2.082, 5.6, 23.0, 1, "Kulová hvězdokupa M5", "Rose Cluster", "NGC 5904", "Ser", 0.0, 1.0, "", 0.0, 0.0),
    (6, 265.025, -32.217, 4.2, 25.0, 2, "Motýlí hvězdokupa", "Butterfly Cluster", "NGC 6405", "Sco", 0.0, 1.0, "", 0.0, 0.0),
    (7, 268.463, -34.806, 3.3, 80.0, 2, "Ptolemaiova hvězdokupa", "Ptolemy's Cluster", "NGC 6475", "Sco", 0.0, 1.0, "", 0.0, 0.0),
    (8, 270.900, -24.383, 6.0, 90.0, 3, "Mlhovina Laguna", "Lagoon Nebula", "NGC 6523", "Sgr", 0.0, 0.60, "dso_m8", 1.5, 0.0),
    (9, 259.796, -18.516, 7.7, 12.0, 1, "Kulová hvězdokupa M9", "Globular Cluster M9", "NGC 6333", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (10, 254.288, -4.100, 6.6, 20.0, 1, "Kulová hvězdokupa M10", "Globular Cluster M10", "NGC 6254", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (11, 282.763, -6.267, 5.8, 14.0, 2, "Hvězdokupa Divoká kachna", "Wild Duck Cluster", "NGC 6705", "Sct", 0.0, 1.0, "", 0.0, 0.0),
    (12, 251.813, -1.948, 6.7, 16.0, 1, "Kulová hvězdokupa M12", "Gung-Ho Cluster", "NGC 6218", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (13, 250.423, 36.460, 5.8, 20.0, 1, "Velká kulová hvězdokupa v Herkulovi", "Great Hercules Cluster", "NGC 6205", "Her", 0.0, 1.0, "dso_m13", 0.45, 0.0),
    (14, 264.400, -3.247, 7.6, 11.0, 1, "Kulová hvězdokupa M14", "Globular Cluster M14", "NGC 6402", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (15, 322.493, 12.172, 6.2, 18.0, 1, "Pegasova kulová hvězdokupa", "Great Pegasus Cluster", "NGC 7078", "Peg", 0.0, 1.0, "", 0.0, 0.0),
    (16, 274.700, -13.790, 6.0, 70.0, 3, "Orlí mlhovina (Sloupy stvoření)", "Eagle Nebula", "NGC 6611", "Ser", 0.0, 1.0, "", 0.0, 0.0),
    (17, 275.196, -16.177, 6.0, 11.0, 3, "Mlhovina Labuť / Omega", "Omega / Swan Nebula", "NGC 6618", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (18, 274.983, -17.135, 7.5, 9.0, 2, "Černá labuť", "Black Swan Cluster", "NGC 6613", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (19, 255.658, -26.268, 6.8, 17.0, 1, "Kulová hvězdokupa M19", "Globular Cluster M19", "NGC 6273", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (20, 270.575, -23.030, 6.3, 28.0, 3, "Mlhovina Trifid", "Trifid Nebula", "NGC 6514", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (21, 271.163, -22.500, 6.5, 13.0, 2, "Hvězdokupa Webb's Cross", "Crosshairs Cluster", "NGC 6531", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (22, 279.100, -23.903, 5.1, 32.0, 1, "Velká hvězdokupa ve Střelci", "Sagittarius Cluster", "NGC 6656", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (23, 269.204, -19.017, 5.5, 27.0, 2, "Otevřená hvězdokupa M23", "Open Cluster M23", "NGC 6494", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (24, 274.238, -18.483, 4.6, 90.0, 2, "Hvězdné mračno ve Střelci", "Small Sagittarius Star Cloud", "IC 4715", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (25, 277.900, -19.250, 4.6, 40.0, 2, "Otevřená hvězdokupa M25", "Open Cluster M25", "IC 4725", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (26, 281.300, -9.400, 8.0, 15.0, 2, "Otevřená hvězdokupa M26", "Open Cluster M26", "NGC 6694", "Sct", 0.0, 1.0, "", 0.0, 0.0),
    (27, 299.901, 22.721, 7.4, 8.0, 4, "Mlhovina Činka", "Dumbbell Nebula", "NGC 6853", "Vul", 125.0, 0.70, "dso_m27", 0.25, 0.0),
    (28, 276.138, -24.870, 6.8, 11.2, 1, "Kulová hvězdokupa M28", "Globular Cluster M28", "NGC 6626", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (29, 305.983, 38.533, 7.1, 7.0, 2, "Chladicí věž", "Cooling Tower", "NGC 6913", "Cyg", 0.0, 1.0, "", 0.0, 0.0),
    (30, 325.092, -23.180, 7.2, 12.0, 1, "Kulová hvězdokupa M30", "Jellyfish Cluster", "NGC 7099", "Cap", 0.0, 1.0, "", 0.0, 0.0),
    (31, 10.685, 41.269, 3.44, 180.0, 0, "Galaxie v Andromedě", "Andromeda Galaxy", "NGC 224", "And", 35.0, 0.32, "dso_m31", 3.2, 0.0),
    (32, 10.674, 40.865, 8.1, 8.7, 0, "Satelitní galaxie M32", "Le Gentil", "NGC 221", "And", 150.0, 0.73, "", 0.0, 0.0),
    (33, 23.462, 30.660, 5.7, 73.0, 0, "Galaxie v Trojúhelníku", "Triangulum Galaxy", "NGC 598", "Tri", 23.0, 0.62, "", 0.0, 0.0),
    (34, 40.500, 42.783, 5.5, 35.0, 2, "Spirála", "Spiral Cluster", "NGC 1039", "Per", 0.0, 1.0, "", 0.0, 0.0),
    (35, 92.225, 24.333, 5.3, 28.0, 2, "Hvězdokupa v Blížencích", "Shoe-Buckle Cluster", "NGC 2168", "Gem", 0.0, 1.0, "", 0.0, 0.0),
    (36, 84.025, 34.133, 6.3, 12.0, 2, "Větrník ve Vozkovi", "Pinwheel Cluster", "NGC 1960", "Aur", 0.0, 1.0, "", 0.0, 0.0),
    (37, 88.100, 32.550, 6.2, 24.0, 2, "Zlatá hvězdokupa", "Auriga Salt and Pepper", "NGC 2099", "Aur", 0.0, 1.0, "", 0.0, 0.0),
    (38, 82.175, 35.833, 7.4, 21.0, 2, "Hvězdokupa Hvězdice", "Starfish Cluster", "NGC 1912", "Aur", 0.0, 1.0, "", 0.0, 0.0),
    (39, 323.050, 48.433, 4.6, 32.0, 2, "Otevřená hvězdokupa M39", "Open Cluster M39", "NGC 7092", "Cyg", 0.0, 1.0, "", 0.0, 0.0),
    (40, 185.600, 58.083, 8.4, 0.8, 2, "Dvojhvězda Winnecke 4", "Winnecke 4", "WNC 4", "UMa", 0.0, 1.0, "", 0.0, 0.0),
    (41, 101.500, -20.733, 4.5, 38.0, 2, "Malý včelí úl", "Little Beehive Cluster", "NGC 2287", "CMa", 0.0, 1.0, "", 0.0, 0.0),
    (42, 83.822, -5.391, 4.0, 65.0, 3, "Velká mlhovina v Orionu", "Great Orion Nebula", "NGC 1976", "Ori", 0.0, 0.90, "dso_m42", 1.2, 0.0),
    (43, 83.896, -5.267, 9.0, 20.0, 3, "De Mairanovo křídlo", "De Mairan's Nebula", "NGC 1982", "Ori", 0.0, 1.0, "", 0.0, 0.0),
    (44, 130.025, 19.983, 3.7, 95.0, 2, "Jesličky / Včelí úl", "Beehive Cluster / Praesepe", "NGC 2632", "Cnc", 0.0, 1.0, "", 0.0, 0.0),
    (45, 56.850, 24.117, 1.6, 110.0, 2, "Plejády / Kuřátka", "Pleiades / Seven Sisters", "Mel 22", "Tau", 0.0, 1.0, "dso_m45", 2.0, 0.0),
    (46, 115.450, -14.817, 6.1, 27.0, 2, "Otevřená hvězdokupa M46", "Open Cluster M46", "NGC 2437", "Pup", 0.0, 1.0, "", 0.0, 0.0),
    (47, 114.150, -14.500, 4.2, 30.0, 2, "Otevřená hvězdokupa M47", "Open Cluster M47", "NGC 2422", "Pup", 0.0, 1.0, "", 0.0, 0.0),
    (48, 123.450, -5.800, 5.5, 54.0, 2, "Otevřená hvězdokupa M48", "Open Cluster M48", "NGC 2548", "Hya", 0.0, 1.0, "", 0.0, 0.0),
    (49, 187.446, 8.000, 8.4, 10.2, 0, "Eliptická galaxie M49", "Elliptical Galaxy M49", "NGC 4472", "Vir", 155.0, 0.83, "", 0.0, 0.0),
    (50, 105.800, -8.333, 5.9, 16.0, 2, "Hvězdokupa ve tvaru srdce", "Heart-Shaped Cluster", "NGC 2323", "Mon", 0.0, 1.0, "", 0.0, 0.0),
    (51, 202.469, 47.195, 8.4, 11.2, 0, "Vírová galaxie", "Whirlpool Galaxy", "NGC 5194", "CVn", 0.0, 0.85, "dso_m51", 0.35, 0.0),
    (52, 351.050, 61.583, 7.3, 13.0, 2, "Hvězdokupa Štír", "Scorpion Cluster", "NGC 7654", "Cas", 0.0, 1.0, "", 0.0, 0.0),
    (53, 198.230, 18.169, 7.6, 13.0, 1, "Kulová hvězdokupa M53", "Globular Cluster M53", "NGC 5024", "Com", 0.0, 1.0, "", 0.0, 0.0),
    (54, 283.763, -30.479, 7.6, 12.0, 1, "Kulová hvězdokupa M54", "Globular Cluster M54", "NGC 6715", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (55, 294.998, -30.964, 6.3, 19.0, 1, "Kulová hvězdokupa M55", "Specter Cluster", "NGC 6809", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (56, 289.148, 30.184, 8.3, 8.8, 1, "Kulová hvězdokupa M56", "Globular Cluster M56", "NGC 6779", "Lyr", 0.0, 1.0, "", 0.0, 0.0),
    (57, 283.396, 33.029, 8.8, 3.0, 4, "Prstencová mlhovina", "Ring Nebula", "NGC 6720", "Lyr", 60.0, 0.80, "dso_m57", 0.12, 0.0),
    (58, 189.431, 11.818, 9.7, 5.9, 0, "Spirála s příčkou M58", "Barred Spiral M58", "NGC 4579", "Vir", 95.0, 0.76, "", 0.0, 0.0),
    (59, 190.509, 11.647, 9.6, 5.4, 0, "Eliptická galaxie M59", "Elliptical Galaxy M59", "NGC 4621", "Vir", 165.0, 0.69, "", 0.0, 0.0),
    (60, 190.917, 11.553, 8.8, 7.6, 0, "Eliptická galaxie M60", "Elliptical Galaxy M60", "NGC 4649", "Vir", 100.0, 0.84, "", 0.0, 0.0),
    (61, 185.477, 4.474, 9.7, 6.5, 0, "Galaxie Oteklé prsty", "Swelling Spiral Galaxy", "NGC 4303", "Vir", 0.0, 0.90, "", 0.0, 0.0),
    (62, 255.300, -30.112, 6.5, 15.0, 1, "Deformovaná hvězdokupa", "Flickering Globular", "NGC 6266", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (63, 198.955, 42.029, 8.6, 12.6, 0, "Galaxie Slunečnice", "Sunflower Galaxy", "NGC 5055", "CVn", 105.0, 0.57, "", 0.0, 0.0),
    (64, 194.182, 21.683, 8.5, 10.7, 0, "Galaxie Černé oko", "Black Eye Galaxy", "NGC 4826", "Com", 115.0, 0.52, "", 0.0, 0.0),
    (65, 169.733, 13.090, 9.3, 9.8, 0, "Galaxie ze Lvího tripletu", "Leo Triplet Galaxy M65", "NGC 3623", "Leo", 170.0, 0.30, "", 0.0, 0.0),
    (66, 170.063, 12.990, 8.9, 9.1, 0, "Galaxie ze Lvího tripletu", "Leo Triplet Galaxy M66", "NGC 3627", "Leo", 173.0, 0.42, "", 0.0, 0.0),
    (67, 132.825, 11.817, 6.1, 30.0, 2, "Hvězdokupa Král Kobra", "King Cobra Cluster", "NGC 2682", "Cnc", 0.0, 1.0, "", 0.0, 0.0),
    (68, 189.867, -26.745, 7.8, 11.0, 1, "Kulová hvězdokupa M68", "Globular Cluster M68", "NGC 4590", "Hya", 0.0, 1.0, "", 0.0, 0.0),
    (69, 277.846, -32.348, 7.6, 9.8, 1, "Kulová hvězdokupa M69", "Globular Cluster M69", "NGC 6637", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (70, 280.803, -32.292, 7.9, 8.0, 1, "Kulová hvězdokupa M70", "Globular Cluster M70", "NGC 6681", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (71, 298.442, 18.779, 8.2, 7.2, 1, "Hvězdokupa Andělíček", "Angelfish Cluster", "NGC 6838", "Sge", 0.0, 1.0, "", 0.0, 0.0),
    (72, 313.366, -12.538, 9.3, 6.6, 1, "Kulová hvězdokupa M72", "Globular Cluster M72", "NGC 6981", "Aqr", 0.0, 1.0, "", 0.0, 0.0),
    (73, 314.725, -12.633, 9.0, 2.8, 2, "Asterismus čtyř hvězd M73", "Y-Group Asterism", "NGC 6994", "Aqr", 0.0, 1.0, "", 0.0, 0.0),
    (74, 24.174, 15.783, 9.4, 10.5, 0, "Galaxie Fantom", "Phantom Galaxy", "NGC 628", "Psc", 0.0, 0.95, "", 0.0, 0.0),
    (75, 301.520, -21.922, 8.5, 6.8, 1, "Kulová hvězdokupa M75", "Globular Cluster M75", "NGC 6864", "Sgr", 0.0, 1.0, "", 0.0, 0.0),
    (76, 25.599, 51.575, 10.1, 2.7, 4, "Malá činka / Motýlek", "Little Dumbbell Nebula", "NGC 650", "Per", 130.0, 0.55, "", 0.0, 0.0),
    (77, 40.670, -0.013, 8.9, 7.1, 0, "Galaxie Chobotnice", "Squid Galaxy / Cetus A", "NGC 1068", "Cet", 70.0, 0.85, "", 0.0, 0.0),
    (78, 86.685, 0.050, 8.3, 8.0, 3, "Reflexní mlhovina M78", "Casper the Friendly Ghost", "NGC 2068", "Ori", 0.0, 1.0, "", 0.0, 0.0),
    (79, 81.138, -24.524, 7.7, 9.6, 1, "Kulová hvězdokupa M79", "Globular Cluster M79", "NGC 1904", "Lep", 0.0, 1.0, "", 0.0, 0.0),
    (80, 244.260, -22.976, 7.3, 10.0, 1, "Kulová hvězdokupa M80", "Globular Cluster M80", "NGC 6093", "Sco", 0.0, 1.0, "", 0.0, 0.0),
    (81, 148.888, 69.065, 6.9, 26.9, 0, "Bodeova galaxie", "Bode's Galaxy", "NGC 3031", "UMa", 157.0, 0.52, "", 0.0, 0.0),
    (82, 148.968, 69.680, 8.4, 11.2, 0, "Doutníková galaxie", "Cigar Galaxy", "NGC 3034", "UMa", 65.0, 0.25, "", 0.0, 0.0),
    (83, 204.254, -29.866, 7.5, 12.9, 0, "Jižní větrník", "Southern Pinwheel Galaxy", "NGC 5236", "Hya", 45.0, 0.88, "", 0.0, 0.0),
    (84, 186.266, 12.887, 9.1, 6.5, 0, "Eliptická galaxie M84", "Elliptical Galaxy M84", "NGC 4374", "Vir", 135.0, 0.86, "", 0.0, 0.0),
    (85, 186.350, 18.190, 9.1, 7.1, 0, "Čočková galaxie M85", "Lenticular Galaxy M85", "NGC 4382", "Com", 15.0, 0.77, "", 0.0, 0.0),
    (86, 186.550, 12.946, 8.9, 8.9, 0, "Eliptická galaxie M86", "Elliptical Galaxy M86", "NGC 4406", "Vir", 125.0, 0.65, "", 0.0, 0.0),
    (87, 187.706, 12.391, 8.6, 8.3, 0, "Obří eliptická galaxie Virgo A", "Virgo A / Smoking Gun", "NGC 4486", "Vir", 0.0, 0.95, "", 0.0, 0.0),
    (88, 187.997, 14.419, 9.6, 6.8, 0, "Spirální galaxie M88", "Spiral Galaxy M88", "NGC 4501", "Com", 140.0, 0.54, "", 0.0, 0.0),
    (89, 188.916, 12.556, 9.8, 5.1, 0, "Eliptická galaxie M89", "Elliptical Galaxy M89", "NGC 4552", "Vir", 0.0, 0.95, "", 0.0, 0.0),
    (90, 189.208, 13.164, 9.5, 9.5, 0, "Spirální galaxie M90", "Spiral Galaxy M90", "NGC 4569", "Vir", 25.0, 0.46, "", 0.0, 0.0),
    (91, 188.860, 14.496, 10.2, 5.4, 0, "Spirála s příčkou M91", "Barred Spiral M91", "NGC 4548", "Com", 65.0, 0.79, "", 0.0, 0.0),
    (92, 259.281, 43.136, 6.3, 14.0, 1, "Kulová hvězdokupa M92", "Globular Cluster M92", "NGC 6341", "Her", 0.0, 1.0, "", 0.0, 0.0),
    (93, 116.150, -23.867, 6.0, 22.0, 2, "Hvězdokupa Motýlek / Hvězdná loď", "Butterfly / Starfish", "NGC 2447", "Pup", 0.0, 1.0, "", 0.0, 0.0),
    (94, 192.721, 41.120, 8.2, 11.2, 0, "Krokodýlí oko / Kočičí oko", "Cat's Eye / Croc's Eye", "NGC 4736", "CVn", 110.0, 0.81, "", 0.0, 0.0),
    (95, 160.990, 11.704, 9.7, 7.3, 0, "Spirála s příčkou M95", "Barred Spiral M95", "NGC 3351", "Leo", 15.0, 0.60, "", 0.0, 0.0),
    (96, 161.691, 11.820, 9.2, 7.8, 0, "Dvojitá spirála M96", "Spiral Galaxy M96", "NGC 3368", "Leo", 175.0, 0.67, "", 0.0, 0.0),
    (97, 168.699, 55.018, 9.9, 3.4, 4, "Soví mlhovina", "Owl Nebula", "NGC 3587", "UMa", 0.0, 0.95, "", 0.0, 0.0),
    (98, 183.451, 14.900, 10.1, 9.8, 0, "Spirální galaxie M98", "Spiral Galaxy M98", "NGC 4192", "Com", 150.0, 0.29, "", 0.0, 0.0),
    (99, 184.707, 14.417, 9.9, 5.4, 0, "Galaxie Větrník v Panně", "Coma Pinwheel Galaxy", "NGC 4254", "Com", 0.0, 0.88, "", 0.0, 0.0),
    (100, 185.729, 15.823, 9.3, 7.4, 0, "Zrcadlová galaxie M100", "Mirror Galaxy", "NGC 4321", "Com", 30.0, 0.84, "", 0.0, 0.0),
    (101, 210.802, 54.349, 7.9, 28.8, 0, "Galaxie Větrník", "Pinwheel Galaxy", "NGC 5457", "UMa", 0.0, 0.95, "", 0.0, 0.0),
    (102, 226.623, 55.763, 9.9, 5.2, 0, "Galaxie Vřeteno", "Spindle Galaxy", "NGC 5866", "Dra", 128.0, 0.44, "", 0.0, 0.0),
    (103, 23.300, 60.700, 7.4, 6.0, 2, "Otevřená hvězdokupa M103", "Open Cluster M103", "NGC 581", "Cas", 0.0, 1.0, "", 0.0, 0.0),
    (104, 189.998, -11.623, 8.0, 8.7, 0, "Galaxie Sombrero", "Sombrero Galaxy", "NGC 4594", "Vir", 90.0, 0.40, "", 0.0, 0.0),
    (105, 161.957, 12.582, 9.3, 5.4, 0, "Eliptická galaxie M105", "Elliptical Galaxy M105", "NGC 3379", "Leo", 70.0, 0.90, "", 0.0, 0.0),
    (106, 184.740, 47.304, 8.4, 18.6, 0, "Spirální galaxie M106", "Spiral Galaxy M106", "NGC 4258", "CVn", 150.0, 0.39, "", 0.0, 0.0),
    (107, 248.133, -13.056, 7.9, 13.0, 1, "Kulová hvězdokupa M107", "Crucifix Globular", "NGC 6171", "Oph", 0.0, 1.0, "", 0.0, 0.0),
    (108, 167.879, 55.674, 10.0, 8.6, 0, "Surfovací prkno", "Surfboard Galaxy", "NGC 3556", "UMa", 80.0, 0.28, "", 0.0, 0.0),
    (109, 179.399, 53.375, 9.8, 7.6, 0, "Vysavačová galaxie", "Vacuum Cleaner Galaxy", "NGC 3992", "UMa", 65.0, 0.58, "", 0.0, 0.0),
    (110, 10.092, 41.685, 8.1, 21.9, 0, "Satelitní galaxie M110", "Edward Young Star", "NGC 205", "And", 170.0, 0.55, "", 0.0, 0.0),
]

METEOR_SHOWERS = [
    (1, "Kvadrantidy", 230.1, 49.5, 120, 1, 4, "Boo"),
    (2, "Lyridy", 271.4, 34.3, 18, 4, 22, "Lyr"),
    (3, "Eta Akvaridy", 338.0, -1.0, 50, 5, 6, "Aqr"),
    (4, "Jižní Delta Akvaridy", 339.8, -16.4, 25, 7, 30, "Aqr"),
    (5, "Alfa Kaprikornidy", 307.0, -10.2, 5, 7, 30, "Cap"),
    (6, "Perseidy", 46.2, 57.8, 100, 8, 12, "Per"),
    (7, "Aurigidy", 91.0, 39.0, 6, 9, 1, "Aur"),
    (8, "Zářijové Epsilon Perseidy", 48.0, 40.0, 5, 9, 9, "Per"),
    (9, "Drakonidy", 262.5, 54.0, 10, 10, 8, "Dra"),
    (10, "Orionidy", 94.5, 15.8, 20, 10, 21, "Ori"),
    (11, "Jižní Tauridy", 50.0, 14.0, 5, 11, 5, "Tau"),
    (12, "Severní Tauridy", 58.5, 22.0, 5, 11, 12, "Tau"),
    (13, "Leonidy", 152.0, 21.6, 15, 11, 17, "Leo"),
    (14, "Geminidy", 112.5, 32.5, 150, 12, 14, "Gem"),
    (15, "Ursidy", 217.5, 76.0, 10, 12, 22, "UMi"),
    (16, "Puppidy-Velidy", 123.0, -45.0, 10, 12, 7, "Pup"),
    (17, "Kentauridy", 211.0, -59.0, 6, 2, 8, "Cen"),
    (18, "Gama Normidy", 239.0, -50.0, 6, 3, 14, "Nor"),
    (19, "Pí Puppidy", 110.0, -45.0, 10, 4, 23, "Pup"),
    (20, "Kamelopardalidy", 124.0, 79.0, 5, 5, 24, "Cam"),
    (21, "Denní Arietidy", 44.0, 24.0, 30, 6, 7, "Ari"),
    (22, "Červnové Bootidy", 224.0, 48.0, 5, 6, 27, "Boo"),
    (23, "Piscis Austrinidy", 341.0, -30.0, 5, 7, 28, "PsA"),
    (24, "Kappa Cygnidy", 286.0, 59.0, 3, 8, 17, "Cyg"),
    (25, "Andromedidy", 26.0, 37.0, 5, 11, 27, "And"),
    (26, "Fénixidy", 18.0, -42.0, 5, 12, 2, "Phe"),
    (27, "Monocerotidy", 100.0, 8.0, 3, 12, 9, "Mon"),
    (28, "Sigma Hydridy", 127.0, 2.0, 3, 12, 12, "Hya"),
]

def main():
    stars_data, star_metadata, constellations_data = build_astronomical_catalog()
    dsos_data = MESSIER_OBJECTS
    meteor_showers_data = METEOR_SHOWERS
    
    print("=== Step 6: Packaging into FlatBuffers binary ===")
    pack_catalog(stars_data, star_metadata, constellations_data, dsos_data, meteor_showers_data, OUTPUT_RAW_BIN)
    pack_catalog(stars_data, star_metadata, constellations_data, dsos_data, meteor_showers_data, OUTPUT_PIPELINE_BIN)
    print("Catalog generation completed successfully!")

if __name__ == "__main__":
    main()




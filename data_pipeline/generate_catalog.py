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

# FlatBuffers Python Schema Serialization

def pack_catalog(stars_data, star_names, constellations_data, output_path):
    """
    Serializes star list, metadata, and constellations (including asterisms) using FlatBuffers.
    
    stars_data: list of (ra_deg, dec_deg, mag)
    star_names: list of (star_index, name_str, body_type, constel, dist_ly)
    constellations_data: list of (name, c_ra, c_dec, lines, loc_name, bright, fam, is_ast, art_res, art_corner_ra, art_corner_dec)
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

def main():
    stars_data, star_metadata, constellations_data = build_astronomical_catalog()
    
    print("=== Step 6: Packaging into FlatBuffers binary ===")
    pack_catalog(stars_data, star_metadata, constellations_data, OUTPUT_RAW_BIN)
    pack_catalog(stars_data, star_metadata, constellations_data, OUTPUT_PIPELINE_BIN)
    print("Catalog generation completed successfully!")

if __name__ == "__main__":
    main()


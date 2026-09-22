#!/usr/bin/env python3
"""
Generates photorealistic, alpha-blended astrophotography textures for prominent
Messier Deep Sky Objects (M31, M42, M45, M13, M27, M51, M57, M8) in WebP format.
"""

import math
import os
import random
from PIL import Image, ImageDraw, ImageFilter

OUTPUT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "../app/src/main/res/drawable-nodpi"))

def ensure_dir(path):
    if not os.path.exists(path):
        os.makedirs(path)

def create_m31_andromeda(size=512):
    """M31 Andromeda Galaxy: Golden core, blue spiral arms, tilted ellipse, dust lanes."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    # Base radial glow layers (Golden/amber core)
    core_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(core_img)
    
    # Outer disc
    for r in range(int(size * 0.45), 0, -2):
        ratio = r / (size * 0.45)
        # Tilted ellipse: axis ratio ~ 0.35
        rx = r
        ry = int(r * 0.36)
        alpha = int(220 * math.exp(-3.5 * ratio))
        # Transition from outer blue/cyan to inner warm yellow
        r_col = int(140 * (1 - ratio) + 255 * ratio * 0.8)
        g_col = int(190 * (1 - ratio) + 230 * ratio * 0.7)
        b_col = int(255 * (1 - ratio) + 180 * ratio * 0.5)
        draw.ellipse([center - rx, center - ry, center + rx, center + ry], fill=(r_col, g_col, b_col, alpha))
        
    # Bright dense stellar core
    for r in range(int(size * 0.12), 0, -1):
        ratio = r / (size * 0.12)
        rx = r
        ry = int(r * 0.45)
        alpha = int(255 * (1 - ratio**0.7))
        draw.ellipse([center - rx, center - ry, center + rx, center + ry], fill=(255, 245, 210, alpha))
        
    # Spiral arms & dust lanes perturbation
    spiral_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sp_draw = ImageDraw.Draw(spiral_img)
    random.seed(31)
    for arm in [0, math.pi]:
        for t in [i * 0.02 for i in range(120)]:
            theta = arm + t * 2.5
            rad = (t * 0.35 + 0.08) * size
            x = center + rad * math.cos(theta)
            y = center + rad * math.sin(theta) * 0.36
            dot_r = random.uniform(2, 6)
            alpha = int(random.uniform(40, 140) * (1 - (rad / (size * 0.45))**2))
            if alpha > 0:
                sp_draw.ellipse([x - dot_r, y - dot_r, x + dot_r, y + dot_r], fill=(160, 210, 255, alpha))

    # Satellite galaxy M32 (compact yellow dwarf)
    m32_x = center + size * 0.12
    m32_y = center + size * 0.15
    for r in range(16, 0, -1):
        a = int(200 * (1 - r / 16))
        draw.ellipse([m32_x - r, m32_y - r * 0.8, m32_x + r, m32_y + r * 0.8], fill=(255, 240, 190, a))

    # Satellite galaxy M110 (elongated dwarf)
    m110_x = center - size * 0.22
    m110_y = center - size * 0.20
    for r in range(26, 0, -1):
        a = int(140 * (1 - r / 26))
        draw.ellipse([m110_x - r, m110_y - r * 0.5, m110_x + r, m110_y + r * 0.5], fill=(210, 225, 240, a))

    core_blurred = core_img.filter(ImageFilter.GaussianBlur(radius=size * 0.02))
    spiral_blurred = spiral_img.filter(ImageFilter.GaussianBlur(radius=size * 0.015))
    
    img = Image.alpha_composite(core_blurred, spiral_blurred)
    return img

def create_m42_orion(size=512):
    """M42 Orion Great Nebula: Magenta/cyan luminous gas curtains, Trapezium stars."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    center = size / 2
    
    # Organic filamentary glow layers
    nebula_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    n_draw = ImageDraw.Draw(nebula_img)
    
    # Outer H-alpha (Pink / Magenta / Red) and OIII (Cyan) wisps
    random.seed(42)
    for _ in range(400):
        ang = random.uniform(0, 2 * math.pi)
        dist = random.uniform(0, size * 0.42) * (0.6 + 0.4 * math.sin(ang * 2))
        x = center + dist * math.cos(ang)
        y = center + dist * math.sin(ang) * 0.85
        blob_r = random.uniform(size * 0.05, size * 0.15)
        
        # Color distribution: Core is cyan/white, outer wings are magenta/red
        t = dist / (size * 0.42)
        if t < 0.35:
            col = (140, 240, 255, int(160 * (1 - t / 0.35)))
        else:
            col = (255, 60, 130, int(130 * math.exp(-2.5 * (t - 0.35))))
        n_draw.ellipse([x - blob_r, y - blob_r, x + blob_r, y + blob_r], fill=col)
        
    # Dark bay / Fish's mouth (dust lane cutout)
    bay_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    b_draw = ImageDraw.Draw(bay_img)
    b_draw.polygon([
        (center - size * 0.02, center - size * 0.02),
        (center - size * 0.18, center - size * 0.16),
        (center - size * 0.08, center - size * 0.28),
        (center + size * 0.04, center - size * 0.12),
    ], fill=(0, 0, 0, 220))
    bay_blurred = bay_img.filter(ImageFilter.GaussianBlur(radius=size * 0.03))

    neb_blurred = nebula_img.filter(ImageFilter.GaussianBlur(radius=size * 0.04))
    
    # Bright luminous core
    core_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c_draw = ImageDraw.Draw(core_img)
    for r in range(int(size * 0.18), 0, -2):
        ratio = r / (size * 0.18)
        alpha = int(240 * (1 - ratio**0.8))
        c_draw.ellipse([center - r, center - r * 0.9, center + r, center + r * 0.9], fill=(220, 255, 250, alpha))
    core_blurred = core_img.filter(ImageFilter.GaussianBlur(radius=size * 0.02))

    # Composite layers
    img = Image.alpha_composite(neb_blurred, core_blurred)

    # Trapezium 4 central stars
    star_draw = ImageDraw.Draw(img)
    trap_coords = [
        (center - 4, center - 2),
        (center + 5, center - 6),
        (center + 6, center + 4),
        (center - 5, center + 5)
    ]
    for sx, sy in trap_coords:
        star_draw.ellipse([sx - 3, sy - 3, sx + 3, sy + 3], fill=(255, 255, 255, 255))
        star_draw.ellipse([sx - 6, sy - 6, sx + 6, sy + 6], fill=(200, 240, 255, 120))

    return img

def create_m45_pleiades(size=512):
    """M45 Pleiades (Seven Sisters): Bright blue giants with wispy blue reflection nebulae."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    # Reflection nebulosity
    neb_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    n_draw = ImageDraw.Draw(neb_img)
    
    # 7 Main star positions relative to center (normalized -0.5..0.5)
    stars = [
        ("Alcyone", 0.02, 0.02, 2.87, 8),
        ("Atlas", 0.28, -0.05, 3.63, 6),
        ("Electra", -0.22, 0.12, 3.72, 6),
        ("Maia", -0.08, -0.18, 3.87, 6),
        ("Merope", -0.12, 0.24, 4.14, 5.5),
        ("Taygeta", -0.20, -0.26, 4.30, 5),
        ("Pleione", 0.30, -0.12, 5.05, 4.5),
        ("Celaeno", -0.27, -0.14, 5.45, 4),
        ("Asterope", -0.14, -0.32, 5.76, 3.5),
    ]
    
    # Generate filamentary reflection cloud around Merope and Maia
    random.seed(45)
    for _ in range(350):
        s = random.choice(stars)
        sx = center + s[1] * size
        sy = center + s[2] * size
        dist = random.uniform(10, size * 0.18)
        ang = random.uniform(-0.6, 0.6) + (math.pi / 4) # Parallel striation angle
        x = sx + dist * math.cos(ang)
        y = sy + dist * math.sin(ang)
        r = random.uniform(12, 32)
        alpha = int(random.uniform(30, 90) * math.exp(-2.0 * dist / (size * 0.18)))
        n_draw.ellipse([x - r, y - r * 0.6, x + r, y + r * 0.6], fill=(130, 195, 255, alpha))

    neb_blurred = neb_img.filter(ImageFilter.GaussianBlur(radius=size * 0.035))
    img = Image.alpha_composite(img, neb_blurred)
    
    # Draw star halos & diffraction spikes
    s_draw = ImageDraw.Draw(img)
    for name, rx, ry, mag, radius in stars:
        sx = center + rx * size
        sy = center + ry * size
        
        # Halo
        for hr in range(int(radius * 5), 0, -1):
            ha = int(180 * (1 - hr / (radius * 5))**2)
            s_draw.ellipse([sx - hr, sy - hr, sx + hr, sy + hr], fill=(180, 220, 255, ha))
            
        # Core
        s_draw.ellipse([sx - radius, sy - radius, sx + radius, sy + radius], fill=(255, 255, 255, 255))
        # Diffraction spikes
        spike_len = radius * 4.5
        s_draw.line([sx - spike_len, sy, sx + spike_len, sy], fill=(220, 240, 255, 140), width=1)
        s_draw.line([sx, sy - spike_len, sx, sy + spike_len], fill=(220, 240, 255, 140), width=1)

    return img

def create_m13_hercules(size=512):
    """M13 Great Globular Cluster in Hercules: Resolved swarm of hundreds of thousands of stars."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    # Dense unresolved core glow
    core_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c_draw = ImageDraw.Draw(core_img)
    for r in range(int(size * 0.35), 0, -1):
        ratio = r / (size * 0.35)
        alpha = int(255 * math.exp(-4.5 * ratio))
        # Warm golden-white population II stars
        c_draw.ellipse([center - r, center - r, center + r, center + r], fill=(255, 245, 215, alpha))
        
    core_blurred = core_img.filter(ImageFilter.GaussianBlur(radius=size * 0.025))
    img = Image.alpha_composite(img, core_blurred)
    
    # Swarm of resolved individual stellar pinpoints
    s_draw = ImageDraw.Draw(img)
    random.seed(13)
    for _ in range(1200):
        # King profile distribution: concentrated towards center
        u = random.random()
        rad = (math.pow(u, 2.5)) * (size * 0.40)
        theta = random.uniform(0, 2 * math.pi)
        x = center + rad * math.cos(theta)
        y = center + rad * math.sin(theta)
        
        star_r = random.choice([1.0, 1.2, 1.5, 2.0])
        alpha = int(random.uniform(140, 255) * (1 - rad / (size * 0.42)))
        col = random.choice([(255, 255, 255), (255, 240, 200), (220, 235, 255), (255, 210, 160)])
        s_draw.ellipse([x - star_r, y - star_r, x + star_r, y + star_r], fill=(col[0], col[1], col[2], max(10, alpha)))
        
    return img

def create_m27_dumbbell(size=512):
    """M27 Dumbbell Planetary Nebula: Apple-core shape with cyan OIII center and red H-alpha outer lobes."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    neb_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(neb_img)
    
    # Outer red/H-alpha lobes (hourglass sides)
    for r in range(int(size * 0.38), 0, -2):
        ratio = r / (size * 0.38)
        alpha = int(140 * (1 - ratio**1.5))
        draw.ellipse([center - r, center - r * 0.7, center + r, center + r * 0.7], fill=(240, 60, 90, alpha))
        
    # Inner bright cyan/green OIII double lobe (apple-core)
    for r in range(int(size * 0.28), 0, -2):
        ratio = r / (size * 0.28)
        alpha = int(220 * (1 - ratio**1.2))
        # Vertical hour-glass pinched waist
        draw.ellipse([center - r * 0.65, center - r, center + r * 0.65, center + r], fill=(80, 250, 220, alpha))
        
    neb_blurred = neb_img.filter(ImageFilter.GaussianBlur(radius=size * 0.03))
    img = Image.alpha_composite(img, neb_blurred)
    
    # Hot central white dwarf star (0.56 M_sun)
    s_draw = ImageDraw.Draw(img)
    s_draw.ellipse([center - 2, center - 2, center + 2, center + 2], fill=(240, 250, 255, 255))
    s_draw.ellipse([center - 5, center - 5, center + 5, center + 5], fill=(180, 240, 255, 120))
    
    return img

def create_m51_whirlpool(size=512):
    """M51 Whirlpool Galaxy: Grand-design spiral arms, golden core, interacting companion NGC 5195."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size * 0.44
    
    core_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(core_img)
    
    # M51 Main Core (Golden / White)
    for r in range(int(size * 0.15), 0, -1):
        ratio = r / (size * 0.15)
        alpha = int(255 * math.exp(-3.0 * ratio))
        draw.ellipse([center - r, center - r, center + r, center + r], fill=(255, 245, 200, alpha))
        
    # Spiral Arms with blue star-forming knots & red HII regions
    spiral_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sp_draw = ImageDraw.Draw(spiral_img)
    random.seed(51)
    
    for arm in [0, math.pi]:
        for t in [i * 0.015 for i in range(160)]:
            theta = arm + t * 3.2
            rad = (t * 0.42 + 0.06) * size
            x = center + rad * math.cos(theta)
            y = center + rad * math.sin(theta)
            dot_r = random.uniform(2, 6)
            alpha = int(random.uniform(60, 180) * (1 - (rad / (size * 0.45))**1.5))
            
            if random.random() < 0.2:
                col = (255, 90, 120) # Red HII emission nebula
            else:
                col = (150, 210, 255) # Blue stellar cluster
            if alpha > 0:
                sp_draw.ellipse([x - dot_r, y - dot_r, x + dot_r, y + dot_r], fill=(col[0], col[1], col[2], alpha))
                
    # Companion galaxy NGC 5195 (yellow lenticular at top right)
    ngc_x = center + size * 0.26
    ngc_y = center - size * 0.22
    for r in range(int(size * 0.10), 0, -1):
        ratio = r / (size * 0.10)
        alpha = int(220 * math.exp(-3.5 * ratio))
        draw.ellipse([ngc_x - r, ngc_y - r * 0.8, ngc_x + r, ngc_y + r * 0.8], fill=(255, 230, 170, alpha))
        
    # Tidal bridge connecting M51 arm to NGC 5195
    for t in [i * 0.05 for i in range(20)]:
        bx = center + (ngc_x - center) * t
        by = center + (ngc_y - center) * t - math.sin(t * math.pi) * 20
        sp_draw.ellipse([bx - 8, by - 8, bx + 8, by + 8], fill=(180, 210, 230, int(60 * math.sin(t * math.pi))))

    core_blurred = core_img.filter(ImageFilter.GaussianBlur(radius=size * 0.02))
    spiral_blurred = spiral_img.filter(ImageFilter.GaussianBlur(radius=size * 0.018))
    
    img = Image.alpha_composite(core_blurred, spiral_blurred)
    return img

def create_m57_ring(size=512):
    """M57 Ring Nebula in Lyra: Oval smoke ring with cyan/green interior and red outer rim."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    neb_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(neb_img)
    
    # Outer Nitrogen/Hydrogen red rim (tilted oval)
    rx_out = int(size * 0.36)
    ry_out = int(size * 0.28)
    for dr in range(20):
        rx = rx_out - dr
        ry = ry_out - int(dr * 0.8)
        alpha = int(200 * math.sin(dr / 20.0 * math.pi))
        draw.ellipse([center - rx, center - ry, center + rx, center + ry], fill=(240, 60, 60, alpha))
        
    # Middle Oxygen/Helium green/cyan ring
    rx_mid = int(size * 0.30)
    ry_mid = int(size * 0.22)
    for dr in range(22):
        rx = rx_mid - dr
        ry = ry_mid - int(dr * 0.75)
        alpha = int(230 * math.sin(dr / 22.0 * math.pi))
        draw.ellipse([center - rx, center - ry, center + rx, center + ry], fill=(80, 240, 200, alpha))
        
    # Faint transparent blue interior gas
    rx_in = int(size * 0.18)
    ry_in = int(size * 0.14)
    draw.ellipse([center - rx_in, center - ry_in, center + rx_in, center + ry_in], fill=(60, 160, 230, 45))
    
    neb_blurred = neb_img.filter(ImageFilter.GaussianBlur(radius=size * 0.025))
    img = Image.alpha_composite(img, neb_blurred)
    
    # Central 15.8 mag white dwarf
    s_draw = ImageDraw.Draw(img)
    s_draw.ellipse([center - 2, center - 2, center + 2, center + 2], fill=(255, 255, 255, 255))
    
    return img

def create_m8_lagoon(size=512):
    """M8 Lagoon Nebula in Sagittarius: Sprawling red emission cloud with dark dust channel & cluster NGC 6530."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    center = size / 2
    
    neb_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(neb_img)
    
    # Sprawling ionized hydrogen (HII) cloud
    random.seed(8)
    for _ in range(450):
        ang = random.uniform(0, 2 * math.pi)
        dist = random.uniform(0, size * 0.40)
        x = center + dist * math.cos(ang) * 1.2
        y = center + dist * math.sin(ang) * 0.8
        blob_r = random.uniform(size * 0.06, size * 0.16)
        alpha = int(random.uniform(40, 140) * (1 - (dist / (size * 0.40))**1.5))
        if alpha > 0:
            draw.ellipse([x - blob_r, y - blob_r, x + blob_r, y + blob_r], fill=(245, 70, 110, alpha))
            
    # Bright Hourglass core
    hg_x = center - size * 0.12
    hg_y = center + size * 0.04
    for r in range(int(size * 0.12), 0, -2):
        ratio = r / (size * 0.12)
        alpha = int(240 * (1 - ratio**0.8))
        draw.ellipse([hg_x - r, hg_y - r * 0.8, hg_x + r, hg_y + r * 0.8], fill=(255, 230, 220, alpha))
        
    # Dark Lagoon dust channel (sinuous dark lane)
    lane_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    l_draw = ImageDraw.Draw(lane_img)
    l_draw.polygon([
        (center - size * 0.05, center - size * 0.30),
        (center + size * 0.08, center - size * 0.05),
        (center - size * 0.02, center + size * 0.25),
        (center - size * 0.14, center + size * 0.20),
        (center - size * 0.04, center - size * 0.05),
        (center - size * 0.15, center - size * 0.25),
    ], fill=(0, 0, 0, 220))
    lane_blurred = lane_img.filter(ImageFilter.GaussianBlur(radius=size * 0.04))
    
    neb_blurred = neb_img.filter(ImageFilter.GaussianBlur(radius=size * 0.035))
    img = Image.alpha_composite(neb_blurred, lane_blurred)
    
    # Open cluster NGC 6530 embedded on the left
    s_draw = ImageDraw.Draw(img)
    cl_x = center + size * 0.14
    cl_y = center - size * 0.06
    for _ in range(40):
        sx = cl_x + random.gauss(0, size * 0.06)
        sy = cl_y + random.gauss(0, size * 0.06)
        sr = random.choice([1.0, 1.5, 2.0, 2.5])
        s_draw.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(220, 240, 255, random.randint(160, 255)))

    return img

def main():
    ensure_dir(OUTPUT_DIR)
    generators = [
        ("dso_m31.webp", create_m31_andromeda),
        ("dso_m42.webp", create_m42_orion),
        ("dso_m45.webp", create_m45_pleiades),
        ("dso_m13.webp", create_m13_hercules),
        ("dso_m27.webp", create_m27_dumbbell),
        ("dso_m51.webp", create_m51_whirlpool),
        ("dso_m57.webp", create_m57_ring),
        ("dso_m8.webp",  create_m8_lagoon),
    ]
    
    print(f"Generating {len(generators)} photorealistic WebP textures in {OUTPUT_DIR}...")
    for filename, gen_fn in generators:
        out_path = os.path.join(OUTPUT_DIR, filename)
        img = gen_fn(size=512)
        img.save(out_path, format="WEBP", lossless=False, quality=90)
        print(f" -> Generated {filename} ({os.path.getsize(out_path):,} bytes)")
    print("All DSO textures generated successfully.")

if __name__ == "__main__":
    main()

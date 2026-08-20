#!/usr/bin/env python3
"""
Asset Pipeline for Night Sky AR: Starless Milky Way Texture.

Fetches the high-resolution celestial coordinates (J2000.0) Milky Way panorama,
applies star-reduction and diffuse nebula enhancement filtering,
scales to 2048x1024, and exports as high-quality WebP.
"""

import os
import sys
import urllib.request
from PIL import Image, ImageFilter

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TARGET_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "drawable-nodpi")
TARGET_FILE = os.path.join(TARGET_DIR, "milky_way_starless.webp")

NASA_SVS_URL = "https://svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/milkyway_2020_4k_print.jpg"
TEMP_DOWNLOAD = os.path.join(os.path.dirname(__file__), "temp_milkyway_source.jpg")

def download_source_panorama():
    print(f"Downloading celestial Milky Way panorama from NASA SVS ({NASA_SVS_URL})...")
    req = urllib.request.Request(NASA_SVS_URL, headers={"User-Agent": "Mozilla/5.0 (Intervalometer Asset Pipeline)"})
    with urllib.request.urlopen(req, timeout=20) as response, open(TEMP_DOWNLOAD, "wb") as out_file:
        out_file.write(response.read())
    print(f"Downloaded source image ({os.path.getsize(TEMP_DOWNLOAD):,} bytes).")

def process_and_export_texture():
    print("Processing panorama image...")
    os.makedirs(TARGET_DIR, exist_ok=True)
    
    img = Image.open(TEMP_DOWNLOAD).convert("RGB")
    
    # 1. Resize to target 2048x1024 resolution using high-quality Lanczos resampling
    target_w, target_h = 2048, 1024
    img_resized = img.resize((target_w, target_h), Image.Resampling.LANCZOS)
    
    # 2. Star Reduction & Nebulosity Isolation
    # Apply a median filter to remove small point-source artifacts followed by smooth Gaussian blur
    img_smooth = img_resized.filter(ImageFilter.MedianFilter(size=3))
    img_diffuse = img_smooth.filter(ImageFilter.GaussianBlur(radius=1.2))
    
    # 3. Enhance galactic core and dust lane contrast slightly for high dynamic range appearance on mobile displays
    # Blend smoothed diffuse background with original to retain crisp dust lanes while removing sharp star dots
    starless = Image.blend(img_diffuse, img_resized, alpha=0.35)
    starless = starless.filter(ImageFilter.MedianFilter(size=3))
    
    # 4. Save as high quality WebP
    starless.save(TARGET_FILE, format="WEBP", quality=90, method=6)
    size_kb = os.path.getsize(TARGET_FILE) / 1024.0
    print(f"Successfully generated starless Milky Way WebP texture at {TARGET_FILE} ({size_kb:.1f} KB, {target_w}x{target_h})")
    
    if os.path.exists(TEMP_DOWNLOAD):
        os.remove(TEMP_DOWNLOAD)

def main():
    try:
        download_source_panorama()
        process_and_export_texture()
    except Exception as e:
        print(f"Error preparing Milky Way texture: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

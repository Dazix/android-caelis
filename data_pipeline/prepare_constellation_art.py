#!/usr/bin/env python3
"""
Downloads and converts Stellarium constellation illustrations to transparent,
high-efficiency WebP drawables with strict zero-cropping and RGBA preservation.
"""

import os
import sys
import urllib.request
from PIL import Image

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
RES_DRAWABLE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "drawable-nodpi")
CACHE_DIR = os.path.join(SCRIPT_DIR, "cache", "art_pngs")

STELLARIUM_BASE_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/master/skycultures/modern/illustrations/"
FAB_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/constellationsart.fab"

def download_file(url, target_path):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 0:
        return
    print(f"Downloading {url}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Intervalometer/1.0)"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    with open(target_path, "wb") as f:
        f.write(data)

def convert_to_transparent_webp(png_path, webp_path, max_dim=512):
    """
    Converts a Stellarium illustration PNG to a transparent WebP.
    Stellarium illustrations use black backgrounds with luminance shading.
    This function converts black to 100% transparent alpha (A=0) while keeping
    white line-art/shading intact with smooth alpha gradients and ZERO cropping.
    """
    src_img = Image.open(png_path)
    
    # If the image already has an alpha channel with actual transparency, use it directly
    if src_img.mode in ("RGBA", "LA") and "A" in src_img.getbands():
        alpha_band = src_img.getchannel("A")
        if alpha_band.getextrema()[0] == 0:
            rgba = src_img.convert("RGBA")
        else:
            rgba = None
    else:
        rgba = None

    if rgba is None:
        # Convert grayscale/RGB luminance to alpha channel
        lum_img = src_img.convert("L")
        w, h = lum_img.size
        rgba = Image.new("RGBA", (w, h))
        
        lum_pixels = lum_img.load()
        rgba_pixels = rgba.load()
        
        max_lum = max(1, lum_img.getextrema()[1])
        scale_factor = 255.0 / max_lum
        
        for y in range(h):
            for x in range(w):
                v = lum_pixels[x, y]
                if v == 0:
                    rgba_pixels[x, y] = (0, 0, 0, 0)
                else:
                    alpha = int(min(255, round(v * scale_factor)))
                    rgba_pixels[x, y] = (255, 255, 255, alpha)

    # Proportionally resize without cropping or shifting geometric center
    if rgba.width > max_dim or rgba.height > max_dim:
        rgba.thumbnail((max_dim, max_dim), Image.Resampling.LANCZOS)
    
    # Save as WebP with lossless alpha preservation and exact RGB
    rgba.save(webp_path, "WEBP", quality=80, lossless=False, exact=True)

def main():
    os.makedirs(CACHE_DIR, exist_ok=True)
    os.makedirs(RES_DRAWABLE_DIR, exist_ok=True)

    fab_cache_path = os.path.join(SCRIPT_DIR, "cache", "constellationsart.fab")
    download_file(FAB_URL, fab_cache_path)

    with open(fab_cache_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    total_converted = 0
    total_original_bytes = 0
    total_webp_bytes = 0

    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 11:
            continue

        abbr = parts[0]
        png_filename = parts[1]
        resource_name = f"art_{abbr.lower()}"

        png_url = STELLARIUM_BASE_URL + png_filename
        local_png_path = os.path.join(CACHE_DIR, png_filename)
        
        try:
            download_file(png_url, local_png_path)
        except Exception as e:
            # Fallback to v24.1 URL if master has filename differences
            fallback_url = f"https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/{png_filename}"
            download_file(fallback_url, local_png_path)

        webp_path = os.path.join(RES_DRAWABLE_DIR, f"{resource_name}.webp")

        convert_to_transparent_webp(local_png_path, webp_path, max_dim=512)

        orig_size = os.path.getsize(local_png_path)
        webp_size = os.path.getsize(webp_path)
        total_original_bytes += orig_size
        total_webp_bytes += webp_size
        total_converted += 1
        print(f"Converted {abbr:3s} ({png_filename}) -> {resource_name}.webp ({orig_size:,} B -> {webp_size:,} B)")

    print(f"\nSuccessfully converted {total_converted} constellation artworks with transparent backgrounds!")
    print(f"Total original PNG size: {total_original_bytes / (1024*1024):.2f} MB")
    print(f"Total WebP drawable size: {total_webp_bytes / (1024*1024):.2f} MB (saved {100 - (total_webp_bytes / total_original_bytes * 100):.1f}%)")

if __name__ == "__main__":
    main()

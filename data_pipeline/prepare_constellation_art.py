#!/usr/bin/env python3
"""
Downloads and converts Stellarium constellation illustrations to high-efficiency WebP drawables.
"""

import os
import sys
import urllib.request
import subprocess
import json

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
RES_DRAWABLE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "drawable-nodpi")
CACHE_DIR = os.path.join(SCRIPT_DIR, "cache", "art_pngs")

STELLARIUM_BASE_URL = "https://raw.githubusercontent.com/Stellarium/stellarium/v24.1/skycultures/modern/"
FAB_URL = STELLARIUM_BASE_URL + "constellationsart.fab"

def download_file(url, target_path):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 0:
        return
    print(f"Downloading {url}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Intervalometer/1.0)"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    with open(target_path, "wb") as f:
        f.write(data)

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
        download_file(png_url, local_png_path)

        webp_path = os.path.join(RES_DRAWABLE_DIR, f"{resource_name}.webp")

        # Convert to WebP using ffmpeg with max dimension 512px and 70% lossy quality
        # scale='if(gt(iw,512),512,iw):-1' preserves aspect ratio
        cmd = [
            "ffmpeg", "-y",
            "-i", local_png_path,
            "-vf", "scale='if(gt(iw,512),512,iw)':-1",
            "-c:v", "libwebp",
            "-quality", "70",
            webp_path
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if res.returncode != 0:
            print(f"Failed to convert {png_filename}: {res.stderr.decode('utf-8')}")
            continue

        orig_size = os.path.getsize(local_png_path)
        webp_size = os.path.getsize(webp_path)
        total_original_bytes += orig_size
        total_webp_bytes += webp_size
        total_converted += 1
        print(f"Converted {abbr} ({png_filename}) -> {resource_name}.webp ({orig_size:,} B -> {webp_size:,} B)")

    print(f"\nSuccessfully converted {total_converted} constellation artworks!")
    print(f"Total original PNG size: {total_original_bytes / (1024*1024):.2f} MB")
    print(f"Total WebP drawable size: {total_webp_bytes / (1024*1024):.2f} MB (saved {100 - (total_webp_bytes / total_original_bytes * 100):.1f}%)")

if __name__ == "__main__":
    main()

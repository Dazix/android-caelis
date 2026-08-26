#!/usr/bin/env python3
import os
import sys
import time
import subprocess
import io
from PIL import Image

def get_env():
    project_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    cmd = "source scripts/env.sh && echo \"$ADB\" && echo \"$ANDROID_USER_ID\""
    res = subprocess.run(["bash", "-c", cmd], cwd=project_dir, capture_output=True, text=True)
    if res.returncode != 0:
        print(res.stderr, file=sys.stderr)
        raise RuntimeError("Failed to resolve environment from scripts/env.sh")
    lines = res.stdout.strip().splitlines()
    adb = lines[0]
    user_id = lines[1]
    return project_dir, adb, user_id

def run(cmd):
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.stdout:
        print(res.stdout.strip())
    if res.stderr:
        print("STDERR:", res.stderr.strip(), file=sys.stderr)
    return res

def main():
    project_dir, adb, user_id = get_env()
    output_dir = os.path.join(project_dir, "screenshots")
    os.makedirs(output_dir, exist_ok=True)

    print("Building debug APK...")
    subprocess.run(["bash", "-c", "source scripts/env.sh && JAVA_HOME=\"$JAVA_HOME\" ./gradlew :app:assembleDebug"], cwd=project_dir, check=True)

    apk = os.path.join(project_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    print(f"Installing {apk} for user {user_id}...")
    run([adb, "install", "-r", "--user", user_id, apk])

    print("Granting Camera and Location permissions...")
    run([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.CAMERA"])
    run([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.ACCESS_FINE_LOCATION"])
    run([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.ACCESS_COARSE_LOCATION"])

    print("Waking device...")
    subprocess.run([os.path.join(project_dir, "scripts", "wake_device.sh")])

    print("Stopping previous instance and launching updated MainActivity...")
    run([adb, "shell", "am", "force-stop", "--user", user_id, "com.dazix.intervalometer"])
    time.sleep(0.5)
    run([adb, "shell", "am", "start", "--user", user_id, "-n", "com.dazix.intervalometer/.ui.MainActivity"])
    time.sleep(1.5)

    # Click on "Night Sky" tab in navigation (3rd item in bottom bar or navigation rail)
    # Bottom bar has 3 items: Item 1 (Session), Item 2 (Sun AR), Item 3 (Night Sky AR)
    # Screen width is 1440 on Pixel 7 Pro (or 1080 scaled): 3rd item is around x = 1440 * 5/6 = 1200, y = 2950 (or ~900, ~2200)
    # Let's find bounds with uiautomator
    print("Finding Night Sky navigation item...")
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"], capture_output=True)
    res = subprocess.run([ADB, "shell", "cat", "/sdcard/window_dump.xml"], capture_output=True, text=True)
    xml_str = res.stdout

    import xml.etree.ElementTree as ET
    import re
    target_bounds = None
    try:
        root = ET.fromstring(xml_str)
        for elem in root.iter("node"):
            text = elem.attrib.get("text", "")
            desc = elem.attrib.get("content-desc", "")
            if "Noční obloha" in text or "Night Sky" in text or "Noční obloha" in desc or "Night Sky" in desc:
                bounds = elem.attrib.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                if m:
                    target_bounds = list(map(int, m.groups()))
                    break
    except Exception as e:
        print("XML error:", e)

    if target_bounds:
        cx = (target_bounds[0] + target_bounds[2]) // 2
        cy = (target_bounds[1] + target_bounds[3]) // 2
        print(f"Tapping Night Sky tab at ({cx}, {cy})...")
        subprocess.run([ADB, "shell", "input", "tap", str(cx), str(cy)])
    else:
        print("Fallback: tapping coordinate for 3rd navigation destination...")
        subprocess.run([ADB, "shell", "input", "tap", "1200", "2950"])

    time.sleep(2.5) # Allow CameraX preview, orientation sensor, and star field to render

    print("Capturing screenshot of Night Sky AR...")
    proc = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True)
    if proc.stdout:
        img = Image.open(io.BytesIO(proc.stdout)).convert("RGB")
        out_file = os.path.join(OUTPUT_DIR, "08_night_sky_ar_screen.png")
        img.save(out_file)
        print(f"Saved Night Sky AR screenshot to {out_file} ({img.size[0]}x{img.size[1]})")

    print("Returning home...")
    subprocess.run([ADB, "shell", "input", "keyevent", "4"]) # KEYCODE_BACK
    time.sleep(0.5)

if __name__ == "__main__":
    main()

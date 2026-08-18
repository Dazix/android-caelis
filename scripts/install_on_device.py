#!/usr/bin/env python3
"""
Builds, installs, and launches the app on the connected Android device for user 11.
"""

import os
import sys
import subprocess
import time

ADB = "/home/dazix/Android/Sdk/platform-tools/adb"
USER_ID = "11"
PROJECT_DIR = "/home/dazix/projects/intervalometer"
APK_PATH = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

def run_cmd(cmd, check=True):
    print(f"Running: {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    res = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, cwd=PROJECT_DIR)
    if res.stdout:
        print(res.stdout.strip())
    if res.stderr:
        print("STDERR:", res.stderr.strip(), file=sys.stderr)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed with code {res.returncode}")
    return res

def main():
    print("1. Building debug APK with Gradle...")
    run_cmd("bash -c 'source scripts/env.local.sh && JAVA_HOME=\"$JAVA_HOME\" ./gradlew :app:assembleDebug'")

    if not os.path.exists(APK_PATH):
        raise FileNotFoundError(f"APK not found at {APK_PATH}")

    print(f"2. Installing APK on device for user {USER_ID}...")
    run_cmd([ADB, "install", "-r", "--user", USER_ID, APK_PATH])

    print("3. Granting runtime permissions (CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)...")
    run_cmd([ADB, "shell", "pm", "grant", "--user", USER_ID, "com.dazix.intervalometer", "android.permission.CAMERA"], check=False)
    run_cmd([ADB, "shell", "pm", "grant", "--user", USER_ID, "com.dazix.intervalometer", "android.permission.ACCESS_FINE_LOCATION"], check=False)
    run_cmd([ADB, "shell", "pm", "grant", "--user", USER_ID, "com.dazix.intervalometer", "android.permission.ACCESS_COARSE_LOCATION"], check=False)

    print("4. Waking device screen...")
    subprocess.run([os.path.join(PROJECT_DIR, "scripts/wake_device.sh")])

    print("5. Launching MainActivity...")
    run_cmd([ADB, "shell", "am", "force-stop", "--user", USER_ID, "com.dazix.intervalometer"], check=False)
    time.sleep(0.5)
    run_cmd([ADB, "shell", "am", "start", "--user", USER_ID, "-n", "com.dazix.intervalometer/.ui.MainActivity"])

    print("\nSUCCESS: Application successfully installed and launched on Pixel 7 Pro!")

if __name__ == "__main__":
    main()

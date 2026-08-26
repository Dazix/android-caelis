#!/usr/bin/env python3
"""
Builds, installs, and launches the app on the connected Android device for the configured user.
"""

import os
import sys
import subprocess
import time

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

def run_cmd(cmd, cwd, check=True):
    print(f"Running: {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    res = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, cwd=cwd)
    if res.stdout:
        print(res.stdout.strip())
    if res.stderr:
        print("STDERR:", res.stderr.strip(), file=sys.stderr)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed with code {res.returncode}")
    return res

def main():
    project_dir, adb, user_id = get_env()
    apk_path = os.path.join(project_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

    print("1. Building debug APK with Gradle...")
    run_cmd("source scripts/env.sh && JAVA_HOME=\"$JAVA_HOME\" ./gradlew :app:assembleDebug", cwd=project_dir)

    if not os.path.exists(apk_path):
        raise FileNotFoundError(f"APK not found at {apk_path}")

    print(f"2. Installing APK on device for user {user_id}...")
    run_cmd([adb, "install", "-r", "--user", user_id, apk_path], cwd=project_dir)

    print("3. Granting runtime permissions (CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)...")
    run_cmd([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.CAMERA"], cwd=project_dir, check=False)
    run_cmd([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.ACCESS_FINE_LOCATION"], cwd=project_dir, check=False)
    run_cmd([adb, "shell", "pm", "grant", "--user", user_id, "com.dazix.intervalometer", "android.permission.ACCESS_COARSE_LOCATION"], cwd=project_dir, check=False)

    print("4. Waking device screen...")
    subprocess.run([os.path.join(project_dir, "scripts", "wake_device.sh")])

    print("5. Launching MainActivity...")
    run_cmd([adb, "shell", "am", "force-stop", "--user", user_id, "com.dazix.intervalometer"], cwd=project_dir, check=False)
    time.sleep(0.5)
    run_cmd([adb, "shell", "am", "start", "--user", user_id, "-n", "com.dazix.intervalometer/.ui.MainActivity"], cwd=project_dir)

    print(f"\nSUCCESS: Application successfully installed and launched for user {user_id}!")

if __name__ == "__main__":
    main()

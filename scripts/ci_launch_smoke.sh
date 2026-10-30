#!/usr/bin/env bash
#
# Installs a debug APK on the running emulator, opens the app and fails if it crashed.
#
# CI runs this on the oldest Android the app supports (minSdk 30, Android 11). Lint catches calls to a
# newer API; it does not catch a theme, a library or a manifest entry that only breaks at runtime there,
# and nobody on the team owns a phone that old.
#
# Usage:
#   scripts/ci_launch_smoke.sh <path/to/app-debug.apk>
#
# Uses `adb` from PATH and the emulator's primary user: this is for a throwaway CI emulator only, never
# for a person's phone (see AGENTS.md for how device work is done there).

set -eu

APK="${1:?usage: ci_launch_smoke.sh <apk>}"
PACKAGE="com.dazix.caelis"
ACTIVITY="$PACKAGE/.ui.MainActivity"
SETTLE_SECONDS=15

adb wait-for-device
echo "device API level: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

adb install -r "$APK"
adb logcat -c
adb logcat -b crash -c

adb shell am start -W -n "$ACTIVITY"
sleep "$SETTLE_SECONDS"

crashes="$(adb logcat -b crash -d)"
if [ -n "$crashes" ]; then
    echo "$crashes" >&2
    echo "the app crashed after launch" >&2
    exit 1
fi

if [ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]; then
    adb logcat -d -t 300 >&2
    echo "the app is not running ${SETTLE_SECONDS}s after launch" >&2
    exit 1
fi

resumed="$(adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true)"
echo "$resumed"
case "$resumed" in
    *"$PACKAGE"*) ;;
    *)
        echo "MainActivity is not in front ${SETTLE_SECONDS}s after launch" >&2
        exit 1
        ;;
esac

echo "launch smoke passed"

#!/usr/bin/env bash
#
# Brings the device out of Doze and off the lock screen, then proves it: a run that starts with
# mWakefulness=Dozing and isKeyguardShowing=true launches the camera behind the keyguard, where it
# never gets a usable shutter - which looks exactly like a broken app.
#
# Nothing here is user-scoped: power state and the keyguard are global, and `input` / `wm` have no
# --user flag.
#
# Usage: scripts/wake_device.sh

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

ATTEMPTS=20

die() {
    printf 'wake_device: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'wake_device: %s\n' "$1"
}

[ -x "$ADB" ] || die "adb not found at $ADB"

wakefulness() {
    "$ADB" shell dumpsys power |
        sed -n 's/^[[:space:]]*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -n 1 | tr -d '\r\n'
}

keyguard_showing() {
    "$ADB" shell dumpsys window |
        sed -n 's/.*isKeyguardShowing=\([a-z]*\).*/\1/p' | head -n 1 | tr -d '\r\n'
}

attempt=0
while [ "$attempt" -lt "$ATTEMPTS" ]; do
    STATE=$(wakefulness)
    KEYGUARD=$(keyguard_showing)
    if [ "$STATE" = "Awake" ] && [ "$KEYGUARD" != "true" ]; then
        info "device is Awake with the keyguard down"
        exit 0
    fi
    [ "$STATE" = "Awake" ] || "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null
    [ "$KEYGUARD" != "true" ] || "$ADB" shell wm dismiss-keyguard >/dev/null
    attempt=$((attempt + 1))
    sleep 0.5
done

die "device is still mWakefulness=$(wakefulness) / isKeyguardShowing=$(keyguard_showing) after $((ATTEMPTS / 2))s. A secured lock screen cannot be dismissed from adb - unlock it by hand and re-run."

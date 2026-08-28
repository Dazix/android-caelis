#!/usr/bin/env bash
set -eu
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.caelis

die() {
    printf 'install_debug: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'install_debug: %s\n' "$1"
}

[ -x "$ADB" ] || die "adb not found at $ADB"
[ -x "${PROJECT_DIR}/gradlew" ] || die "gradlew not found at ${PROJECT_DIR}/gradlew"

CURRENT_USER=$("$ADB" shell am get-current-user | tr -d '\r\n')
if [ "$CURRENT_USER" != "$USER_ID" ]; then
    die "device current user is '${CURRENT_USER}', expected ${USER_ID}. The harness only ever touches user ${USER_ID}."
fi
info "current user is ${USER_ID}"

info "building the debug APK"
(cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug)

APK="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "no debug APK at ${APK} after assembleDebug"

info "installing ${APK} for user ${USER_ID} only"
"$ADB" install -r --user "$USER_ID" "$APK"

if ! "$ADB" shell pm list packages --user "$USER_ID" "$PKG" | grep -q "package:${PKG}"; then
    die "${PKG} is not installed for user ${USER_ID} after 'adb install -r --user ${USER_ID}'."
fi

info "Successfully installed ${PKG} for user ${USER_ID}"

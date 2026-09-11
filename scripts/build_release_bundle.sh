#!/usr/bin/env bash
#
# Builds and signs the release Android App Bundle (.aab) locally.
# Safe for interactive use: passwords can be prompted securely without writing them to disk or shell history.
#
set -eu

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

die() {
    printf 'build_release_bundle: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'build_release_bundle: %s\n' "$1"
}

KEYSTORE_PATH="${SIGNING_KEY_STORE_PATH:-${PROJECT_DIR}/release.keystore}"
KEY_ALIAS="${SIGNING_KEY_ALIAS:-caelis_release}"

if [ ! -f "$KEYSTORE_PATH" ]; then
    die "Keystore file not found at: ${KEYSTORE_PATH}. Please provide a valid keystore or set SIGNING_KEY_STORE_PATH."
fi

STORE_PASSWORD="${SIGNING_STORE_PASSWORD:-}"
if [ -z "$STORE_PASSWORD" ]; then
    printf 'Enter Keystore Password: '
    read -r -s STORE_PASSWORD
    printf '\n'
fi

[ -n "$STORE_PASSWORD" ] || die "Keystore password cannot be empty."

KEY_PASSWORD="${SIGNING_KEY_PASSWORD:-$STORE_PASSWORD}"

info "Keystore: ${KEYSTORE_PATH}"
info "Key Alias: ${KEY_ALIAS}"
info "Building signed release bundle..."

export SIGNING_KEY_STORE_PATH="$KEYSTORE_PATH"
export SIGNING_STORE_PASSWORD="$STORE_PASSWORD"
export SIGNING_KEY_ALIAS="$KEY_ALIAS"
export SIGNING_KEY_PASSWORD="$KEY_PASSWORD"

(cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:bundleRelease --no-daemon)

AAB_PATH="${PROJECT_DIR}/app/build/outputs/bundle/release/app-release.aab"

if [ ! -f "$AAB_PATH" ]; then
    die "Build completed, but output bundle not found at: ${AAB_PATH}"
fi

info "SUCCESS! Release bundle created at:"
ls -lh "$AAB_PATH"

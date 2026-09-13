#!/usr/bin/env bash
#
# Decodes base64-encoded keystore from KEYSTORE_BASE64 environment variable into target file.
#
# Usage:
#   KEYSTORE_BASE64="..." ./scripts/decode_keystore.sh [destination_file_path]

set -eu

DEST="${1:-${RUNNER_TEMP:-/tmp}/keystore.jks}"

if [ -z "${KEYSTORE_BASE64:-}" ]; then
    echo "Error: ANDROID_KEYSTORE_BASE64 secret is not configured." >&2
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
echo "$KEYSTORE_BASE64" | base64 --decode > "$DEST"
echo "Keystore decoded successfully to $DEST"

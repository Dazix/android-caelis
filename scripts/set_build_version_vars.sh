#!/usr/bin/env bash
#
# Computes release versioning variables and writes them to $GITHUB_OUTPUT.
#
# Usage:
#   scripts/set_build_version_vars.sh --track <internal|alpha|beta|production> --sha <sha> [--run-number <num>] [--tag <tag>]

set -eu

TRACK=""
SHA=""
RUN_NUMBER=""
TAG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --track)
            TRACK="$2"
            shift 2
            ;;
        --sha)
            SHA="$2"
            shift 2
            ;;
        --run-number)
            RUN_NUMBER="$2"
            shift 2
            ;;
        --tag)
            TAG="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "$TRACK" ] || [ -z "$SHA" ]; then
    echo "Usage: $0 --track <internal|alpha|beta|production> --sha <sha> [--run-number <num>] [--tag <tag>]" >&2
    exit 1
fi

SHORT_SHA=$(echo "$SHA" | cut -c1-7)

emit_var() {
    local key="$1"
    local val="$2"
    echo "${key}=${val}"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "${key}=${val}" >> "$GITHUB_OUTPUT"
    fi
}

emit_var "short_sha" "$SHORT_SHA"
emit_var "track" "$TRACK"

case "$TRACK" in
    internal|alpha)
        if [ -z "$RUN_NUMBER" ]; then
            echo "Error: --run-number is required for $TRACK track" >&2
            exit 1
        fi
        # Play calls `alpha` closed testing; the name testers see says so.
        LABEL="$TRACK"
        [ "$TRACK" = "alpha" ] && LABEL="closed"
        emit_var "version_name" "${LABEL} [${SHORT_SHA}]"
        emit_var "release_name" "Build #${RUN_NUMBER} ${LABEL} [${SHORT_SHA}]"
        ;;
    beta)
        if [ -z "$TAG" ]; then
            echo "Error: --tag is required for beta track" >&2
            exit 1
        fi
        SEMVER=$(echo "$TAG" | sed -E 's/^v//; s/-.*//')
        emit_var "version_name" "${SEMVER} [${SHORT_SHA}]"
        emit_var "release_name" "${TAG} [${SHORT_SHA}]"
        ;;
    production)
        if [ -z "$TAG" ]; then
            echo "Error: --tag is required for production track" >&2
            exit 1
        fi
        emit_var "release_name" "${TAG} [${SHORT_SHA}]"
        ;;
    *)
        echo "Error: Unknown track '$TRACK'. Must be internal, alpha, beta, or production." >&2
        exit 1
        ;;
esac

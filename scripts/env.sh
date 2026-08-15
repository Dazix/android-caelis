#!/usr/bin/env bash
#
# Shared configuration for every script in scripts/. Source it, never execute it:
#
#     . "$(dirname "${BASH_SOURCE[0]}")/env.sh"
#
# It resolves three values that cannot be guessed and must not be hardcoded in the repository:
#
#   ADB              path to the adb binary (nothing Android is assumed to be on PATH)
#   JAVA_HOME        the JDK Gradle runs on
#   ANDROID_USER_ID  the Android user the harness is allowed to touch
#
# They come from the environment, or from scripts/env.local.sh, which is gitignored because those
# values describe one machine and one phone. scripts/env.example.sh is the template.
#
# PROJECT_DIR is NOT configurable: it is derived from this file's own location, so a clone works
# wherever it is put.
#
# This file fails closed. An unset ANDROID_USER_ID used to be a literal `11` in every script; a
# default here would mean a clone on another phone silently rewriting a stranger's secure settings
# and deleting their photos. There is no safe fallback, so there is no fallback.

_env_die() {
    printf 'env.sh: FATAL: %s\n' "$1" >&2
    exit 1
}

# scripts/env.sh -> scripts -> repository root. Resolved through `cd -P` so a symlinked checkout
# still reports the real tree the git commands and .device_snapshot path expect.
PROJECT_DIR=$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Anything already exported wins, so a one-off `ANDROID_USER_ID=12 scripts/...` still works and the
# CI/other-machine case needs no file at all.
if [ -f "${PROJECT_DIR}/scripts/env.local.sh" ]; then
    _ENV_ADB=${ADB:-}
    _ENV_JAVA_HOME=${JAVA_HOME:-}
    _ENV_ANDROID_USER_ID=${ANDROID_USER_ID:-}

    # shellcheck source=/dev/null
    . "${PROJECT_DIR}/scripts/env.local.sh"

    # Written as plain `if`s rather than `[ -n .. ] && ..`, whose non-zero result on the empty case
    # is exactly the kind of thing `set -e` in the sourcing script turns into a silent early exit.
    if [ -n "$_ENV_ADB" ]; then ADB=$_ENV_ADB; fi
    if [ -n "$_ENV_JAVA_HOME" ]; then JAVA_HOME=$_ENV_JAVA_HOME; fi
    if [ -n "$_ENV_ANDROID_USER_ID" ]; then ANDROID_USER_ID=$_ENV_ANDROID_USER_ID; fi
    unset _ENV_ADB _ENV_JAVA_HOME _ENV_ANDROID_USER_ID
fi

for _env_var in ADB JAVA_HOME ANDROID_USER_ID; do
    eval "_env_value=\${${_env_var}:-}"
    if [ -z "$_env_value" ]; then
        _env_die "${_env_var} is not set. Copy scripts/env.example.sh to scripts/env.local.sh and fill it in (it is gitignored), or export ${_env_var} for this run."
    fi
done
unset _env_var _env_value

[ -x "$ADB" ] || _env_die "adb not found or not executable at '${ADB}' (ADB in scripts/env.local.sh)"
[ -x "${JAVA_HOME}/bin/java" ] || _env_die "no java at '${JAVA_HOME}/bin/java' (JAVA_HOME in scripts/env.local.sh)"

case "$ANDROID_USER_ID" in
    ''|*[!0-9]*) _env_die "ANDROID_USER_ID must be a number, got '${ANDROID_USER_ID}'" ;;
esac

export ADB JAVA_HOME ANDROID_USER_ID PROJECT_DIR

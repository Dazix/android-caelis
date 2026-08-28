#!/usr/bin/env bash
#
# Answers one question, and exits non-zero when the answer is no: is the Intervalometer accessibility
# service actually BOUND for the configured ANDROID_USER_ID right now?
#
# Why it is not a grep: `dumpsys accessibility` prints one block per user, and the class name appears
# on the "Enabled services:" line too - which stays true while the service is dead. A run once
# reported "service is bound" next to `Crashed services:{{...ShutterAccessibilityService}}` and
# `Bound services:{}` for exactly that reason. So the block for our user is cut out first, and all of
# these have to hold:
#   * Bound services:{...} is non-empty,
#   * Crashed services:{} is empty,
#   * Enabled services:{...} names our class (the framework really saw the setting),
#   * and, per component, `dumpsys activity services` has a ServiceRecord for it with an
#     `app=ProcessRecord{...}` line, i.e. a live process is hosting it. When that dump has no record
#     for the class at all - which is also what a dump-format change looks like - it only warns, so
#     the probe can strengthen the verdict but never fail it on its own.
#
# Usage:
#   scripts/a11y_assert_bound.sh            # wait up to 30s for the binding
#   scripts/a11y_assert_bound.sh 0          # check once, do not wait

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.caelis
SERVICE_CLASS=ShutterAccessibilityService

# Two attempts per second. A cold first bind after an install takes a couple of seconds.
TIMEOUT_S=${1:-30}
ATTEMPTS=$((TIMEOUT_S * 2 + 1))

die() {
    printf 'a11y_assert_bound: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'a11y_assert_bound: %s\n' "$1"
}

[ -x "$ADB" ] || die "adb not found at $ADB"

# dumpsys accessibility has no --user flag: it reports every user, so the block for ours is cut out by
# its `attributes:{id=<user>,` line. Blocks start at "User state[" and the last one ends at
# "Global Info".
a11y_user_block() {
    "$ADB" shell dumpsys accessibility | awk -v marker="attributes:{id=${USER_ID}," '
        function flush() { if (keep && out == "") out = block }
        /^User state\[/ { flush(); block = ""; keep = 0; inblock = 1; next }
        /^Global Info/ { flush(); inblock = 0; keep = 0; next }
        inblock {
            if (index($0, marker)) keep = 1
            block = block $0 "\n"
        }
        END { flush(); printf "%s", out }
    '
}

# 0 = a live process hosts our service record, 1 = the record exists but has no process,
# 2 = no record at all.
service_record_state() {
    "$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | awk -v cls="$SERVICE_CLASS" -v uid="u${USER_ID}" '
        /\* ServiceRecord\{/ {
            inrec = (index($0, cls) > 0 && index($0, uid) > 0)
            if (inrec) seen = 1
            next
        }
        inrec && /^[[:space:]]*app=ProcessRecord\{/ { hosted = 1 }
        END { exit(hosted ? 0 : (seen ? 1 : 2)) }
    '
}

record_probe_warned=0
attempt=0
while [ "$attempt" -lt "$ATTEMPTS" ]; do
    BLOCK=$(a11y_user_block)
    BOUND_LINE=$(printf '%s\n' "$BLOCK" | grep -m 1 'Bound services:{' || true)
    CRASHED_LINE=$(printf '%s\n' "$BLOCK" | grep -m 1 'Crashed services:{' || true)
    ENABLED_LINE=$(printf '%s\n' "$BLOCK" | grep -m 1 'Enabled services:{' || true)

    a11y_ok=0
    case "$BOUND_LINE" in
        *'Service[label='*)
            case "$CRASHED_LINE" in
                *'Crashed services:{}'*)
                    case "$ENABLED_LINE" in
                        *"$SERVICE_CLASS"*) a11y_ok=1 ;;
                    esac
                    ;;
            esac
            ;;
    esac

    if [ "$a11y_ok" = 1 ]; then
        set +e
        service_record_state
        record_state=$?
        set -e
        case "$record_state" in
            0)
                info "accessibility service is BOUND (${BOUND_LINE#*Bound services:}) and hosted by a live process"
                exit 0
                ;;
            2)
                if [ "$record_probe_warned" = 0 ]; then
                    record_probe_warned=1
                    info "WARNING: no ServiceRecord for ${SERVICE_CLASS} in 'dumpsys activity services ${PKG}'; trusting the accessibility bound set alone"
                fi
                info "accessibility service is BOUND (${BOUND_LINE#*Bound services:})"
                exit 0
                ;;
            *) : ;; # record exists but no process yet - keep waiting
        esac
    fi

    attempt=$((attempt + 1))
    [ "$attempt" -lt "$ATTEMPTS" ] || break
    sleep 0.5
done

printf '%s\n' "$BLOCK" | sed 's/^/a11y_assert_bound:   /' >&2
die "${SERVICE_CLASS} is not bound for user ${USER_ID} after ${TIMEOUT_S}s (see the accessibility block above: a non-empty 'Crashed services' means the process died on connect - check 'adb logcat -b crash'; an empty 'Bound services' with an empty 'Crashed services' usually means something is suppressing accessibility services, e.g. a UiAutomation connection without FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)."

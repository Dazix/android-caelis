#!/usr/bin/env bash
#
# Enables the Intervalometer accessibility service for the configured ANDROID_USER_ID,
# non-destructively, and does not return until the service is really BOUND.
#
# The device is shared with a human profile that has its own accessibility services enabled. So:
#   * the ORIGINAL values are snapshotted to .device_snapshot before anything is touched,
#   * our service is APPENDED to the existing enabled_accessibility_services list, never written
#     over it,
#   * a11y_teardown.sh puts the recorded values back verbatim.
#
# Two things this script must get right, because a fresh install leaves the framework holding a
# CRASHED (never rebound) service:
#   1. it always CYCLES the service off and on. Writing the value the setting already holds is a
#      no-op for the framework, and the crashed set is only cleared when the enabled list actually
#      changes - so "already enabled, leaving it alone" means "stays dead".
#   2. it verifies the binding through scripts/a11y_assert_bound.sh instead of grepping the dump for
#      the class name - that also matches the "Enabled services:" line, which stays true while the
#      service is dead.
# Never binding is fatal here: every downstream step is meaningless without a bound service.
#
# Safe to re-run: an existing .device_snapshot is never overwritten, so re-running cannot capture
# our own modifications as if they were the human's original state.
#
# Usage: scripts/a11y_setup.sh

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.caelis
SERVICE="${PKG}/${PKG}.service.ShutterAccessibilityService"
SNAPSHOT="${PROJECT_DIR}/.device_snapshot"
NULL_SENTINEL=__NULL__

# A cold first bind after an install is a couple of seconds; 30 s is head room, not hope.
BIND_TIMEOUT_S=30
CYCLE_SETTLE_S=2

die() {
    printf 'a11y_setup: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'a11y_setup: %s\n' "$1"
}

# ---------------------------------------------------------------- preconditions

[ -x "$ADB" ] || die "adb not found at $ADB"

# `am get-current-user` has no --user flag by definition; it is what we are checking.
CURRENT_USER=$("$ADB" shell am get-current-user | tr -d '\r\n')
if [ "$CURRENT_USER" != "$USER_ID" ]; then
    die "device current user is '${CURRENT_USER}', expected ${USER_ID}. Switch profiles first; this harness refuses to touch any other user's settings."
fi
info "current user is ${USER_ID}"

package_present() {
    "$ADB" shell pm list packages --user "$USER_ID" "$PKG" | grep -q "package:${PKG}"
}

if package_present; then
    PKG_PRESENT=1
else
    PKG_PRESENT=0
fi

# ------------------------------------------------------------------- read state

# `settings get` prints the literal string "null" for an unset key.
get_secure() {
    "$ADB" shell settings --user "$USER_ID" get secure "$1" | tr -d '\r\n'
}

normalize_null() {
    case "$1" in
        '' | null | NULL) printf '%s' "$NULL_SENTINEL" ;;
        *) printf '%s' "$1" ;;
    esac
}

# `appops get` prints e.g. "SYSTEM_ALERT_WINDOW: allow; time=..." or "No operations.".
get_appop() {
    mode=$("$ADB" shell cmd appops get --user "$USER_ID" "$PKG" "$1" 2>/dev/null |
        sed -n "s/^[[:space:]]*$1: *\([A-Za-z_]*\).*/\1/p" | head -n 1 | tr -d '\r\n')
    if [ -z "$mode" ]; then
        # No explicit entry recorded: the op is at its manifest/platform default.
        printf 'default'
    else
        printf '%s' "$mode"
    fi
}

# granted / revoked / unknown (the last one also covers "package not installed for this user").
get_runtime_permission() {
    state=$("$ADB" shell dumpsys package "$PKG" 2>/dev/null |
        sed -n "/User ${USER_ID}:/,\$p" |
        sed -n "s/^[[:space:]]*$1: granted=\([a-z]*\).*/\1/p" | head -n 1 | tr -d '\r\n')
    case "$state" in
        true) printf 'granted' ;;
        false) printf 'revoked' ;;
        *) printf 'unknown' ;;
    esac
}

ENABLED_SERVICES=$(get_secure enabled_accessibility_services)
ACCESSIBILITY_ENABLED=$(get_secure accessibility_enabled)
if [ "$PKG_PRESENT" = 1 ]; then
    APPOP_SAW=$(get_appop SYSTEM_ALERT_WINDOW)
    APPOP_ARS=$(get_appop ACCESS_RESTRICTED_SETTINGS)
    PERM_POST_NOTIFICATIONS=$(get_runtime_permission android.permission.POST_NOTIFICATIONS)
    PERM_READ_MEDIA_IMAGES=$(get_runtime_permission android.permission.READ_MEDIA_IMAGES)
else
    APPOP_SAW=default
    APPOP_ARS=default
    PERM_POST_NOTIFICATIONS=unknown
    PERM_READ_MEDIA_IMAGES=unknown
fi

# ----------------------------------------------------------------- snapshot

if [ -f "$SNAPSHOT" ]; then
    info "keeping the existing snapshot at ${SNAPSHOT} (re-run detected; the human's original values stay recorded)"
else
    {
        printf 'SNAPSHOT_VERSION=2\n'
        printf 'SNAPSHOT_USER_ID=%s\n' "$USER_ID"
        printf 'SNAPSHOT_PACKAGE=%s\n' "$PKG"
        printf 'SNAPSHOT_TAKEN_AT=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf 'ORIG_SECURE_ENABLED_ACCESSIBILITY_SERVICES=%s\n' "$(normalize_null "$ENABLED_SERVICES")"
        printf 'ORIG_SECURE_ACCESSIBILITY_ENABLED=%s\n' "$(normalize_null "$ACCESSIBILITY_ENABLED")"
        printf 'ORIG_APPOP_SYSTEM_ALERT_WINDOW=%s\n' "$APPOP_SAW"
        printf 'ORIG_APPOP_ACCESS_RESTRICTED_SETTINGS=%s\n' "$APPOP_ARS"
        printf 'ORIG_PERM_POST_NOTIFICATIONS=%s\n' "$PERM_POST_NOTIFICATIONS"
        printf 'ORIG_PERM_READ_MEDIA_IMAGES=%s\n' "$PERM_READ_MEDIA_IMAGES"
    } >"$SNAPSHOT"
    info "snapshotted original state to ${SNAPSHOT}"
    sed 's/^/a11y_setup:   /' "$SNAPSHOT"
fi

# ------------------------------------------------------- app ops and permissions

# Applied before the service is cycled, so it binds with the overlay op already allowed.
if [ "$PKG_PRESENT" = 1 ]; then
    "$ADB" shell cmd appops set --user "$USER_ID" "$PKG" SYSTEM_ALERT_WINDOW allow
    "$ADB" shell cmd appops set --user "$USER_ID" "$PKG" ACCESS_RESTRICTED_SETTINGS allow
    info "allowed SYSTEM_ALERT_WINDOW (was ${APPOP_SAW}) and ACCESS_RESTRICTED_SETTINGS (was ${APPOP_ARS})"

    # Without this the ongoing session notification is enqueued and dropped by the framework, so a
    # scripted run can never observe the countdown a real user would see.
    "$ADB" shell pm grant --user "$USER_ID" "$PKG" android.permission.POST_NOTIFICATIONS
    info "granted POST_NOTIFICATIONS (was ${PERM_POST_NOTIFICATIONS})"

    # CaptureVerifier degrades to a silent no-op without it, so a session asked to verify its
    # captures would report success without ever having looked.
    "$ADB" shell pm grant --user "$USER_ID" "$PKG" android.permission.READ_MEDIA_IMAGES
    info "granted READ_MEDIA_IMAGES (was ${PERM_READ_MEDIA_IMAGES})"
fi

# --------------------------------------------------- cycle the service off / on

# Everything in the list except ours, ':' separated, empty when there is nothing else.
OTHER_SERVICES=$(printf '%s' "$(normalize_null "$ENABLED_SERVICES")" |
    tr ':' '\n' |
    grep -v -x -F "$SERVICE" |
    grep -v -x -F "$NULL_SENTINEL" |
    grep -v -x '' |
    tr '\n' ':' |
    sed 's/:$//' || true)

if [ -n "$OTHER_SERVICES" ]; then
    NEW_LIST="${OTHER_SERVICES}:${SERVICE}"
else
    NEW_LIST="$SERVICE"
fi

put_service_list() {
    if [ -n "$1" ]; then
        # Quoted for the on-device shell: the value contains ':' and '/'.
        "$ADB" shell "settings --user ${USER_ID} put secure enabled_accessibility_services '$1'"
    else
        "$ADB" shell settings --user "$USER_ID" delete secure enabled_accessibility_services >/dev/null
    fi
}

# Off first: a service the framework has in its crashed set is only forgotten when the enabled list
# changes, and it is never rebound while it is remembered there.
"$ADB" shell "settings --user ${USER_ID} put secure accessibility_enabled 0"
put_service_list "$OTHER_SERVICES"
info "cycled accessibility off (other services meanwhile: ${OTHER_SERVICES:-none})"
sleep "$CYCLE_SETTLE_S"

put_service_list "$NEW_LIST"
"$ADB" shell "settings --user ${USER_ID} put secure accessibility_enabled 1"
info "enabled list is now: ${NEW_LIST}"

if [ "$PKG_PRESENT" = 0 ]; then
    info "NOTE: ${PKG} is not installed for user ${USER_ID} yet. The setting is in place; the service will bind once the debug APK is installed. Re-run this script after installing to get a verified binding."
    exit 0
fi

# --------------------------------------------------------- wait for the binding

# Delegated: the same assertion QA can run on its own (scripts/a11y_assert_bound.sh), and it dies
# rather than warns when the service never binds.
exec "${PROJECT_DIR}/scripts/a11y_assert_bound.sh" "$BIND_TIMEOUT_S"

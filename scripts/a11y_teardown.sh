#!/usr/bin/env bash
#
# Restores exactly what a11y_setup.sh recorded in .device_snapshot for the configured
# ANDROID_USER_ID:
#   * enabled_accessibility_services and accessibility_enabled are written back verbatim, or
#     DELETED when the snapshot says the key was unset (__NULL__),
#   * the SYSTEM_ALERT_WINDOW / ACCESS_RESTRICTED_SETTINGS appop modes go back to their recorded
#     modes ('default' means "no explicit entry", which is what `appops set ... default` restores),
#   * POST_NOTIFICATIONS and READ_MEDIA_IMAGES are re-revoked unless the snapshot recorded them as
#     already granted.
#
# The secure settings are restored FIRST and the per-package state after it, because the package can
# legitimately be gone by the time we run: `:app:connectedDebugAndroidTest` uninstalls the app when
# it finishes, and `cmd appops set` / `pm revoke` then fail with "No UID for ...". App ops and
# runtime grants die with the package anyway, so they are skipped when it is absent - what must
# never be skipped is putting the human's secure settings back.
#
# The snapshot file is removed once everything it records is restored, so the next setup takes a
# fresh, honest snapshot; anything that could not be restored keeps the snapshot on disk and exits
# non-zero, so a re-run can finish the job.
#
# Safe to re-run: with no snapshot present it reports that and exits 0, so it can live in a trap.
#
# Usage: scripts/a11y_teardown.sh

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.caelis
SNAPSHOT="${PROJECT_DIR}/.device_snapshot"
NULL_SENTINEL=__NULL__

FAILED=0

die() {
    printf 'a11y_teardown: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'a11y_teardown: %s\n' "$1"
}

warn() {
    printf 'a11y_teardown: WARNING: %s\n' "$1" >&2
    FAILED=1
}

[ -x "$ADB" ] || die "adb not found at $ADB"

if [ ! -f "$SNAPSHOT" ]; then
    info "no snapshot at ${SNAPSHOT}; nothing to restore"
    exit 0
fi

# `am get-current-user` has no --user flag by definition; it is what we are checking.
CURRENT_USER=$("$ADB" shell am get-current-user | tr -d '\r\n')
if [ "$CURRENT_USER" != "$USER_ID" ]; then
    die "device current user is '${CURRENT_USER}', expected ${USER_ID}. Refusing to restore user ${USER_ID}'s snapshot onto a different user; switch profiles and re-run."
fi

read_key() {
    # Everything after the first '=' is the value, so service lists keep their ':' separators.
    sed -n "s/^$1=//p" "$SNAPSHOT" | head -n 1
}

SNAPSHOT_USER=$(read_key SNAPSHOT_USER_ID)
[ "$SNAPSHOT_USER" = "$USER_ID" ] ||
    die "snapshot was taken for user '${SNAPSHOT_USER}', not ${USER_ID}"

ORIG_SERVICES=$(read_key ORIG_SECURE_ENABLED_ACCESSIBILITY_SERVICES)
ORIG_ENABLED=$(read_key ORIG_SECURE_ACCESSIBILITY_ENABLED)
ORIG_APPOP_SAW=$(read_key ORIG_APPOP_SYSTEM_ALERT_WINDOW)
ORIG_APPOP_ARS=$(read_key ORIG_APPOP_ACCESS_RESTRICTED_SETTINGS)
ORIG_PERM_POST=$(read_key ORIG_PERM_POST_NOTIFICATIONS)
ORIG_PERM_MEDIA=$(read_key ORIG_PERM_READ_MEDIA_IMAGES)

restore_secure() {
    key=$1
    value=$2
    if [ -z "$value" ] || [ "$value" = "$NULL_SENTINEL" ]; then
        if "$ADB" shell settings --user "$USER_ID" delete secure "$key" >/dev/null; then
            info "deleted secure ${key} (it was unset before the run)"
        else
            warn "could not delete secure ${key}"
        fi
    else
        # Quoted for the on-device shell: service lists contain ':' and '/'.
        if "$ADB" shell "settings --user ${USER_ID} put secure ${key} '${value}'"; then
            info "restored secure ${key}=${value}"
        else
            warn "could not restore secure ${key}=${value}"
        fi
    fi
}

restore_appop() {
    op=$1
    mode=$2
    [ -n "$mode" ] || mode=default
    if "$ADB" shell cmd appops set --user "$USER_ID" "$PKG" "$op" "$mode"; then
        info "restored appop ${op}=${mode}"
    else
        warn "could not restore appop ${op}=${mode}"
    fi
}

restore_permission() {
    permission=$1
    state=$2
    case "$state" in
        granted)
            if "$ADB" shell pm grant --user "$USER_ID" "$PKG" "$permission"; then
                info "kept ${permission} granted (it was granted before the run)"
            else
                warn "could not re-grant ${permission}"
            fi
            ;;
        revoked | unknown)
            if "$ADB" shell pm revoke --user "$USER_ID" "$PKG" "$permission"; then
                info "revoked ${permission} (recorded as ${state} before the run)"
            else
                warn "could not revoke ${permission}"
            fi
            ;;
        *)
            info "no recorded state for ${permission} (pre-v2 snapshot); leaving it as it is"
            ;;
    esac
}

# Services first, then the master switch, so the framework never sees our service enabled with the
# original (possibly 0) accessibility_enabled value applied afterwards.
restore_secure enabled_accessibility_services "$ORIG_SERVICES"
restore_secure accessibility_enabled "$ORIG_ENABLED"

if "$ADB" shell pm list packages --user "$USER_ID" "$PKG" | grep -q "package:${PKG}"; then
    restore_appop SYSTEM_ALERT_WINDOW "$ORIG_APPOP_SAW"
    restore_appop ACCESS_RESTRICTED_SETTINGS "$ORIG_APPOP_ARS"
    restore_permission android.permission.POST_NOTIFICATIONS "$ORIG_PERM_POST"
    restore_permission android.permission.READ_MEDIA_IMAGES "$ORIG_PERM_MEDIA"
else
    info "${PKG} is not installed for user ${USER_ID} any more (the connected test task uninstalls it); its app ops and runtime grants went with it, nothing to restore"
fi

if [ "$FAILED" = 0 ]; then
    rm -f "$SNAPSHOT"
    info "device restored; snapshot consumed"
    exit 0
fi

printf 'a11y_teardown: FATAL: %s\n' "part of the recorded state could not be restored; keeping ${SNAPSHOT} so a re-run can finish the job" >&2
exit 1

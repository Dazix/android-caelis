#!/usr/bin/env bash
#
# Real end-to-end proof: drive the Pixel camera app with a real session and verify that the number
# of images in the configured ANDROID_USER_ID's MediaStore actually grew.
#
#   wake + unlock -> list photo ids -> launch com.google.android.GoogleCamera -> run a 5 shot / 6 s
#   session -> wait for the rows -> assert the new ones -> delete exactly those -> always teardown
#
# Photos are listed through the MediaStore for that user (`content query --user "$USER_ID"`), not `ls`
# on that profile's DCIM - the adb shell uid has no access to another user's media directory, so a
# directory listing would silently report nothing.
#
# The session runs with `adaptive_long_exposure_wait` on, because that is what a user shooting a real
# Pixel needs: the camera decides by itself to spend seconds on a Night Sight frame and ignores a
# shutter tap that arrives mid-capture.
#
# `verify_captures` IS set, because that is the setting that used to be untested here. Verification
# now polls MediaStore until the row appears or a window (captureLatencyMs + 10 s) expires, instead of
# looking exactly once at captureLatencyMs - measured on this device, Google Camera publishes its row
# about 6.3 s after the tap, so the single check always fired too early and re-tapped every shot
# (measured then: 10 shutter presses for 5 requested shots). The number of re-taps the engine actually
# logged is counted below and recorded as `retaps` in the summary, so a regression shows up as a
# number and not only as extra MediaStore rows.
#
# Waiting for the row makes the interval a minimum while this flag is on: the log shows each shot
# re-anchoring ~0.4 s late at a 6 s interval, which is the verification wait, not drift.
#
# Two things this script must get right:
#   * the device is woken and the keyguard dismissed first. A Dozing, locked device gives the camera
#     no usable shutter and the session captures nothing, which looks like an app bug.
#   * the rows it created are recorded by _id and deleted again in the cleanup trap, so a Developer
#     development profile does not slowly fill up with test frames. Pre-existing media is never
#     touched.
#
# The session is started through the debug-only broadcast receiver
# (com.dazix.intervalometer.debug.DebugSessionReceiver, debug source set only) so no UI scripting is
# involved and the timing is exactly what the engine chose.
#
# Usage:
#   scripts/e2e_pixel_camera.sh
#
# Env:
#   SHOTS         number of frames to capture (default 5)
#   INTERVAL_MS   interval between frames in ms (default 6000; see below)
#   STRICT        1 = fail if MORE images appeared than requested (default 0: warn only, because
#                 Motion Photos and HDR variants can add extra MediaStore rows per shutter press)
#   KEEP_PHOTOS   1 = leave the captured rows in the MediaStore (default 0: delete exactly them)
#   VERIFY        1 = run with verify_captures on (default 1); 0 = off, for comparing the two

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.intervalometer
RECEIVER="${PKG}/${PKG}.debug.DebugSessionReceiver"
CAM_PKG=com.google.android.GoogleCamera
MEDIA_URI=content://media/external/images/media
SCRIPT_DIR="${PROJECT_DIR}/scripts"
OUT_DIR="${PROJECT_DIR}/build/e2e"

SHOTS=${SHOTS:-5}

# 6 s, not 3: in anything but bright light the Pixel camera switches itself to Night Sight and spends
# ~3 s per frame on the exposure alone, on top of the profile's 2.2 s capture latency. A cadence the
# camera physically cannot deliver would fail this test for a reason that has nothing to do with the
# app - measured: 5 taps at 3 s produced 3 files, all 5 taps landing on the shutter node.
INTERVAL_MS=${INTERVAL_MS:-6000}
STRICT=${STRICT:-0}
KEEP_PHOTOS=${KEEP_PHOTOS:-0}
VERIFY=${VERIFY:-1}
if [ "$VERIFY" = "0" ]; then VERIFY_FLAG=false; else VERIFY_FLAG=true; fi

# FLAG_INCLUDE_STOPPED_PACKAGES: our app may be in the stopped state right after install.
BROADCAST_FLAGS=0x00000020

# Per shot allowance for a long exposure plus its processing, used only to bound the wait below.
BUSY_ALLOWANCE_S=15
POLL_S=2
SETTLE_S=4
DELETE_ATTEMPTS=6
DELETE_RETRY_S=5

STAMP=$(date '+%Y%m%d-%H%M%S')

die() {
    printf 'e2e: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'e2e: %s\n' "$1"
}

[ -x "$ADB" ] || die "adb not found at $ADB"

# `am get-current-user` has no --user flag by definition; it is what we are checking.
CURRENT_USER=$("$ADB" shell am get-current-user | tr -d '\r\n')
if [ "$CURRENT_USER" != "$USER_ID" ]; then
    die "device current user is '${CURRENT_USER}', expected ${USER_ID}. This test only ever runs against user ${USER_ID}."
fi
info "current user is ${USER_ID}"

mkdir -p "$OUT_DIR"
SUMMARY="${OUT_DIR}/summary-${STAMP}.txt"
BEFORE_IDS="${OUT_DIR}/ids-before-${STAMP}.txt"
AFTER_IDS="${OUT_DIR}/ids-after-${STAMP}.txt"
CREATED_IDS="${OUT_DIR}/ids-created-${STAMP}.txt"
LEFTOVER_IDS="${OUT_DIR}/ids-leftover-${STAMP}.txt"
: >"$CREATED_IDS"

"$ADB" shell pm list packages --user "$USER_ID" "$PKG" | grep -q "package:${PKG}" ||
    die "${PKG} is not installed for user ${USER_ID}; run scripts/run_instrumented.sh, or build and install it for this profile only: ./gradlew :app:assembleDebug && adb install -r --user ${USER_ID} app/build/outputs/apk/debug/app-debug.apk (never :app:installDebug - that installs for every user, user 0 included)"
"$ADB" shell pm list packages --user "$USER_ID" "$CAM_PKG" | grep -q "package:${CAM_PKG}" ||
    die "${CAM_PKG} is not installed for user ${USER_ID}"

# One MediaStore image _id per line. `content query` prints "Row: <n> _id=<id>".
photo_ids() {
    "$ADB" shell content query --user "$USER_ID" --uri "$MEDIA_URI" --projection _id 2>/dev/null |
        sed -n 's/^Row: [0-9]* _id=\([0-9]*\).*/\1/p'
}

# Only the ids in $2 that are absent from $1.
#
# The file is identified by FILENAME rather than by `NR == FNR`, which is the usual idiom and is wrong
# here: when $1 is *empty* - a fresh Developer profile with no photos at all - awk never reads a record
# from it, so the first record of $2 also satisfies NR == FNR and the whole second file is swallowed as
# "already seen". That produced a run reporting `images_before=0, images_after=5, images_delta=0` and
# then leaving all five frames on the phone because the delete list came out empty.
ids_added() {
    awk -v baseline="$1" '
        FILENAME == baseline { seen[$1] = 1; next }
        !($1 in seen) { print $1 }
    ' "$1" "$2"
}

# Removes exactly the rows this run created; anything that was already there is left alone.
delete_created_photos() {
    [ -s "$CREATED_IDS" ] || return 0
    if [ "$KEEP_PHOTOS" = "1" ]; then
        info "KEEP_PHOTOS=1, leaving $(wc -l <"$CREATED_IDS" | tr -d ' ') captured row(s) in place"
        return 0
    fi
    # Retried: a row the camera is still writing (IS_PENDING) belongs to the camera and refuses a
    # shell delete, and HDR+ / Night Sight output stays pending for several seconds after the shutter.
    cp "$CREATED_IDS" "$LEFTOVER_IDS"
    attempt=0
    while [ "$attempt" -lt "$DELETE_ATTEMPTS" ]; do
        while read -r id; do
            [ -n "$id" ] || continue
            "$ADB" shell content delete --user "$USER_ID" --uri "$MEDIA_URI" --where "_id=${id}" \
                >/dev/null 2>&1 || true
        done <"$LEFTOVER_IDS"
        photo_ids >"$LEFTOVER_IDS.all"
        # Which of the ids we tried to remove are still queryable, i.e. were not actually deleted.
        awk 'NR == FNR { want[$1] = 1; next } ($1 in want) { print $1 }' \
            "$CREATED_IDS" "$LEFTOVER_IDS.all" >"$LEFTOVER_IDS"
        rm -f "$LEFTOVER_IDS.all"
        [ -s "$LEFTOVER_IDS" ] || break
        attempt=$((attempt + 1))
        [ "$attempt" -lt "$DELETE_ATTEMPTS" ] || break
        info "$(wc -l <"$LEFTOVER_IDS" | tr -d ' ') row(s) not deletable yet (still being written); retrying in ${DELETE_RETRY_S}s"
        sleep "$DELETE_RETRY_S"
    done
    if [ -s "$LEFTOVER_IDS" ]; then
        printf 'e2e: WARNING: %s\n' "could not delete $(wc -l <"$LEFTOVER_IDS" | tr -d ' ') captured row(s); ids are listed in ${LEFTOVER_IDS}" >&2
    else
        rm -f "$LEFTOVER_IDS"
        info "removed the $(wc -l <"$CREATED_IDS" | tr -d ' ') row(s) this run created"
    fi
}

cleanup() {
    status=$?
    printf 'e2e: cleaning up\n'
    "$ADB" shell am broadcast --user "$USER_ID" -a "${PKG}.debug.STOP_SESSION" \
        -n "$RECEIVER" -f "$BROADCAST_FLAGS" >/dev/null 2>&1 || true
    "$ADB" shell am force-stop --user "$USER_ID" "$CAM_PKG" >/dev/null 2>&1 || true
    delete_created_photos || printf 'e2e: WARNING photo cleanup failed\n' >&2
    "${SCRIPT_DIR}/a11y_teardown.sh" || printf 'e2e: WARNING teardown failed\n' >&2
    exit $status
}

# Trap before setup, so a half-applied setup is still restored.
trap cleanup EXIT INT TERM

"${SCRIPT_DIR}/wake_device.sh"
"${SCRIPT_DIR}/a11y_setup.sh"

# Bring our app up once: it un-stops the package (so the broadcast is delivered) and gives the
# accessibility service a moment to settle on a foreground window.
"$ADB" shell am start --user "$USER_ID" -n "${PKG}/.ui.MainActivity" >/dev/null
sleep 3

"$ADB" shell am broadcast --user "$USER_ID" -a "${PKG}.debug.CLEAR_TAP_LOG" \
    -n "$RECEIVER" -f "$BROADCAST_FLAGS" >/dev/null

photo_ids >"$BEFORE_IDS"
BEFORE=$(wc -l <"$BEFORE_IDS" | tr -d ' ')
info "images in user ${USER_ID} MediaStore before: ${BEFORE}"

CAM_COMPONENT=$("$ADB" shell cmd package resolve-activity --brief --user "$USER_ID" "$CAM_PKG" |
    tail -n 1 | tr -d '\r')
case "$CAM_COMPONENT" in
    "${CAM_PKG}/"*) : ;;
    *) die "could not resolve a launch component for ${CAM_PKG} (got '${CAM_COMPONENT}')" ;;
esac

# Re-asserted here: the app launch and the setup wait above take long enough for a device with a
# short screen timeout to have dozed off again.
"${SCRIPT_DIR}/wake_device.sh"

info "launching ${CAM_COMPONENT}"
"$ADB" shell am start --user "$USER_ID" -a android.media.action.STILL_IMAGE_CAMERA \
    -n "$CAM_COMPONENT" >/dev/null
# The Pixel camera needs a few seconds before its shutter is usable.
sleep 8

# Device-local timestamp, so the retap count below reads only this session's log lines. The buffer is
# never cleared: another agent's crash check reads the same log. Quoted for the *device* shell too -
# `adb shell` concatenates its arguments, so an unquoted format string arrives at `date` as two.
LOG_SINCE=$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
[ -n "$LOG_SINCE" ] || die "could not read the device clock; the retap count below would be a fake 0"

info "starting a ${SHOTS} shot / ${INTERVAL_MS} ms session against ${CAM_PKG} (verify_captures=${VERIFY_FLAG})"
"$ADB" shell am broadcast --user "$USER_ID" -a "${PKG}.debug.START_SESSION" \
    -n "$RECEIVER" -f "$BROADCAST_FLAGS" \
    --es target_package "$CAM_PKG" \
    --el interval_ms "$INTERVAL_MS" \
    --ei max_shots "$SHOTS" \
    --el start_delay_ms 0 \
    --ez adaptive_long_exposure_wait true \
    --ez verify_captures "$VERIFY_FLAG" >/dev/null

# Polled, not slept: with adaptive_long_exposure_wait on, the engine holds a shot back while the
# camera is busy, so the session legitimately runs longer than (SHOTS - 1) gaps. The loop stops the
# moment the requested number of rows is there, and the deadline is a bound, not an expectation.
DEADLINE_S=$(( ((SHOTS - 1) * INTERVAL_MS) / 1000 + SHOTS * BUSY_ALLOWANCE_S + 30 ))
info "waiting up to ${DEADLINE_S}s for ${SHOTS} new image row(s)"
waited=0
DELTA=0
while [ "$waited" -lt "$DEADLINE_S" ]; do
    photo_ids >"$AFTER_IDS"
    ids_added "$BEFORE_IDS" "$AFTER_IDS" >"$CREATED_IDS"
    DELTA=$(wc -l <"$CREATED_IDS" | tr -d ' ')
    [ "$DELTA" -ge "$SHOTS" ] && break
    sleep "$POLL_S"
    waited=$((waited + POLL_S))
done
info "${DELTA} new row(s) after ${waited}s"

# `screencap` renders the display, not a user's files, so there is no --user flag here. exec-out
# keeps the PNG binary-clean instead of routing it through the device filesystem. Taken before the
# camera is touched again, so it shows the state the session ended in - which is the first thing to
# look at when the count is short (a Night Sight badge on the shutter explains a lot).
SHOT_PNG="${OUT_DIR}/pixel-camera-${STAMP}.png"
"$ADB" exec-out screencap -p >"$SHOT_PNG"
info "screenshot: ${SHOT_PNG}"

# A last look after a short grace, so a Motion Photo or HDR variant that lands late is counted (and
# then cleaned up) rather than silently left behind.
sleep "$SETTLE_S"
photo_ids >"$AFTER_IDS"
AFTER=$(wc -l <"$AFTER_IDS" | tr -d ' ')
# Written before any assertion can abort the script: the cleanup trap deletes exactly this list, so a
# failing run does not leave its frames behind either.
ids_added "$BEFORE_IDS" "$AFTER_IDS" >"$CREATED_IDS"
DELTA=$(wc -l <"$CREATED_IDS" | tr -d ' ')
info "images in user ${USER_ID} MediaStore after: ${AFTER} (new rows ${DELTA}, expected ${SHOTS})"

# One line per shot the engine gave up on verifying and pressed the shutter for a second time. With a
# working verification window this is 0; the broken single check produced one per shot.
RETAPS=$("$ADB" shell "logcat -d -t '${LOG_SINCE}'" 2>/dev/null | grep -c 're-tapping once' || true)
info "re-taps logged by the engine: ${RETAPS}"
if [ "$VERIFY" != "0" ] && [ "$RETAPS" != "0" ]; then
    printf 'e2e: WARNING: %s\n' "the engine re-tapped ${RETAPS} time(s): a capture was not visible in the MediaStore within the verification window" >&2
fi

{
    printf 'run=%s\n' "$STAMP"
    printf 'user=%s\n' "$USER_ID"
    printf 'camera=%s\n' "$CAM_COMPONENT"
    printf 'shots_requested=%s\n' "$SHOTS"
    printf 'interval_ms=%s\n' "$INTERVAL_MS"
    printf 'images_before=%s\n' "$BEFORE"
    printf 'images_after=%s\n' "$AFTER"
    printf 'images_delta=%s\n' "$DELTA"
    printf 'adaptive_long_exposure_wait=1\n'
    printf 'verify_captures=%s\n' "$VERIFY"
    printf 'retaps=%s\n' "$RETAPS"
    printf 'created_ids=%s\n' "$(tr '\n' ' ' <"$CREATED_IDS")"
    printf 'screenshot=%s\n' "$SHOT_PNG"
} >"$SUMMARY"
info "summary: ${SUMMARY}"

if [ "$DELTA" -lt "$SHOTS" ]; then
    printf 'result=FAIL\n' >>"$SUMMARY"
    die "only ${DELTA} new image(s) for ${SHOTS} requested shots - the session did not reach the camera shutter"
fi

if [ "$DELTA" -gt "$SHOTS" ]; then
    if [ "$STRICT" = "1" ]; then
        printf 'result=FAIL\n' >>"$SUMMARY"
        die "${DELTA} new images for ${SHOTS} shots (STRICT=1). Likely Motion Photo / HDR variants; re-check the camera app settings."
    fi
    info "WARNING: ${DELTA} new images for ${SHOTS} shots - probably Motion Photo or an HDR variant per press"
fi

printf 'result=PASS\n' >>"$SUMMARY"
info "PASS: ${DELTA} new image(s) in user ${USER_ID} MediaStore for ${SHOTS} requested shots"

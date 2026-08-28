#!/usr/bin/env bash
#
# The instrumented run, with device hygiene guaranteed:
#
#   assembleDebug + adb install --user "$ANDROID_USER_ID"  ->  a11y_setup.sh
#   ->  ./gradlew :app:connectedDebugAndroidTest  ->  a11y_teardown.sh
#
# Teardown is installed as a trap BEFORE setup runs, so the device is restored whether the tests
# pass, fail, crash, or the run is interrupted.
#
# The Gradle exit code alone is NOT the verdict: Gradle exits 0 when every test skipped itself
# (JUnit assumption failures are not failures), which is precisely what a service that never bound
# produces. The XML results are therefore parsed afterwards and a run with skips, or with no tests at
# all, is reported as FAILED.
#
# Usage:
#   scripts/run_instrumented.sh                    # everything
#   scripts/run_instrumented.sh TapAccuracyTest    # one class (or Class#method)
#
# Env (see scripts/env.example.sh; scripts/env.local.sh is where these normally live):
#   ADB, JAVA_HOME, ANDROID_USER_ID   required
#   ANDROID_SERIAL                    passed through to adb/gradle when several devices are attached

set -eu

# ADB, JAVA_HOME, ANDROID_USER_ID and PROJECT_DIR; aborts if the machine is not configured.
# JAVA_HOME is exported there, which is what ./gradlew below runs on.
# shellcheck source=scripts/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
PKG=com.dazix.caelis
SCRIPT_DIR="${PROJECT_DIR}/scripts"
REPORT_ROOT="${PROJECT_DIR}/app/build/reports/androidTests"
RESULTS_DIR="${PROJECT_DIR}/app/build/outputs/androidTest-results/connected"

die() {
    printf 'run_instrumented: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'run_instrumented: %s\n' "$1"
}

[ -x "$ADB" ] || die "adb not found at $ADB"
[ -x "${PROJECT_DIR}/gradlew" ] || die "gradlew not found at ${PROJECT_DIR}/gradlew"

# `am get-current-user` has no --user flag by definition; it is what we are checking.
CURRENT_USER=$("$ADB" shell am get-current-user | tr -d '\r\n')
if [ "$CURRENT_USER" != "$USER_ID" ]; then
    die "device current user is '${CURRENT_USER}', expected ${USER_ID}. The harness only ever touches user ${USER_ID}."
fi
info "current user is ${USER_ID}"

"${SCRIPT_DIR}/wake_device.sh"

# Install first: the accessibility component has to exist before we enable it, otherwise the
# framework has nothing to bind and setup would fail on its bind check.
#
# Built with Gradle but installed with adb, on purpose: `:app:installDebug` runs `pm install` with no
# --user, which installs the app for EVERY user on the device - including user 0, the human's own
# profile. `adb install --user "$USER_ID"` keeps the app inside the development profile, which is the
# whole point of this harness.
info "building the debug APK"
(cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug)

APK="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "no debug APK at ${APK} after assembleDebug"
info "installing ${APK} for user ${USER_ID} only"
"$ADB" install -r --user "$USER_ID" "$APK"

if ! "$ADB" shell pm list packages --user "$USER_ID" "$PKG" | grep -q "package:${PKG}"; then
    die "${PKG} is not installed for user ${USER_ID} after 'adb install -r --user ${USER_ID}'. Check 'adb install' output above and re-run."
fi

# Stale results from an earlier run must not be able to vouch for this one.
rm -rf "$RESULTS_DIR" "${REPORT_ROOT}/connected"

# Trap before setup, so a half-applied setup is still restored.
trap 'status=$?; printf "run_instrumented: restoring device state\n"; "${SCRIPT_DIR}/a11y_teardown.sh" || printf "run_instrumented: WARNING teardown failed\n" >&2; exit $status' EXIT INT TERM

"${SCRIPT_DIR}/a11y_setup.sh"

GRADLE_ARGS=":app:connectedDebugAndroidTest"
if [ "$#" -gt 0 ]; then
    GRADLE_ARGS="${GRADLE_ARGS} -Pandroid.testInstrumentationRunnerArguments.class=${PKG}.$1"
    info "filtering to ${PKG}.$1"
fi

set +e
(cd "$PROJECT_DIR" && ./gradlew $GRADLE_ARGS)
GRADLE_STATUS=$?
set -e

REPORT=$(find "$REPORT_ROOT" -name index.html -print 2>/dev/null | head -n 1 || true)
if [ -n "$REPORT" ]; then
    info "HTML report: ${REPORT}"
    info "open with: xdg-open ${REPORT}"
else
    info "no HTML report found under ${REPORT_ROOT}"
fi

# ------------------------------------------------------- the verdict, from the XML

# -exec cat: the result path contains the device name, spaces included.
XML_COUNT=$(find "$RESULTS_DIR" -name '*.xml' -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$XML_COUNT" = 0 ]; then
    info "instrumented tests FAILED (gradle exit ${GRADLE_STATUS})"
    die "no test result XML under ${RESULTS_DIR}: the instrumentation never produced results, so nothing was actually executed"
fi
XML_TEXT=$(find "$RESULTS_DIR" -name '*.xml' -type f -exec cat {} +)

# Per-class <testsuite ...> elements only; the <testsuites> wrapper needs no space after its name.
sum_attr() {
    printf '%s\n' "$XML_TEXT" |
        grep -o '<testsuite [^>]*>' |
        grep -o " $1=\"[0-9]*\"" |
        sed 's/[^0-9]//g' |
        awk '{ total += $1 } END { print total + 0 }'
}

TESTS=$(sum_attr tests)
SKIPPED=$(sum_attr skipped)
FAILURES=$(sum_attr failures)
ERRORS=$(sum_attr errors)
info "results: ${TESTS} test(s), ${FAILURES} failure(s), ${ERRORS} error(s), ${SKIPPED} skipped (${XML_COUNT} XML file(s))"

VERDICT=0
[ "$GRADLE_STATUS" -eq 0 ] || VERDICT=1
if [ "$TESTS" -eq 0 ]; then
    info "no test ran at all - an empty run cannot pass"
    VERDICT=1
fi
if [ "$SKIPPED" -gt 0 ]; then
    info "${SKIPPED} test(s) skipped. A skip here means the accessibility service was not bound when the test started, so the engine was never exercised; that is a harness failure, not a pass."
    VERDICT=1
fi
if [ "$FAILURES" -gt 0 ] || [ "$ERRORS" -gt 0 ]; then
    VERDICT=1
fi

if [ "$VERDICT" -eq 0 ]; then
    info "instrumented tests PASSED (${TESTS} executed, none skipped)"
else
    info "instrumented tests FAILED (gradle exit ${GRADLE_STATUS}; ${TESTS} executed, ${FAILURES} failed, ${ERRORS} errored, ${SKIPPED} skipped)"
fi

exit "$VERDICT"

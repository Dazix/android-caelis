#!/usr/bin/env bash
set -eu
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

USER_ID=$ANDROID_USER_ID
SCRIPT_DIR="${PROJECT_DIR}/scripts"

die() {
    printf 'run_all_validation: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'run_all_validation: %s\n' "$1"
}

info "1. Running unit tests and verification gates"
(cd "$PROJECT_DIR" && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest)

info "2. Checking unit test results XML"
RESULTS_DIR="${PROJECT_DIR}/app/build/test-results/testDebugUnitTest"
python3 "${SCRIPT_DIR}/check_test_results.py" "$RESULTS_DIR"
info "All unit tests passed with 0 failures, 0 errors, 0 skipped."

info "3. Installing to device for dedicated development user ($USER_ID)"
"${SCRIPT_DIR}/install_debug.sh"

info "VALIDATION COMPLETE: TOP QUALITY ACHIEVED"

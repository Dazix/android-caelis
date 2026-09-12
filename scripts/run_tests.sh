#!/usr/bin/env bash
# Runs scoped unit tests based on functional area:
#   scripts/run_tests.sh intervalometer  - Intervalometer core, service, overlay, session planner, calibration
#   scripts/run_tests.sh solar           - Sun & Moon trajectory, solar positioning, ephemeris, AR projection
#   scripts/run_tests.sh nightsky        - Night sky AR, Meeus celestial math, FlatBuffers catalog, star renderer
#   scripts/run_tests.sh all             - All test suites and lint check

set -eu
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

TARGET="${1:-all}"
RESULTS_DIR="${PROJECT_DIR}/app/build/test-results/testDebugUnitTest"

die() {
    printf 'run_tests: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'run_tests: %s\n' "$1"
}

check_xml_results() {
    [ -d "$RESULTS_DIR" ] || die "No test results directory at $RESULTS_DIR"

    # Count using Python for accurate totals and strict assertion
    python3 -c '
import xml.etree.ElementTree as ET
import glob, sys

files = glob.glob("app/build/test-results/testDebugUnitTest/*.xml")
if not files:
    print("ERROR: No test result XML files found!", file=sys.stderr)
    sys.exit(1)

total_tests = 0
total_failures = 0
total_errors = 0
total_skipped = 0

for f in files:
    tree = ET.parse(f)
    root = tree.getroot()
    total_tests += int(root.attrib.get("tests", 0))
    total_failures += int(root.attrib.get("failures", 0))
    total_errors += int(root.attrib.get("errors", 0))
    total_skipped += int(root.attrib.get("skipped", 0))

print(f"Test XML Summary: {total_tests} test(s) executed, {total_failures} failure(s), {total_errors} error(s), {total_skipped} skipped across {len(files)} test file(s).")
if total_failures > 0 or total_errors > 0 or total_skipped > 0:
    print(f"FAILED: Regressions detected!", file=sys.stderr)
    sys.exit(1)
if total_tests == 0:
    print("FAILED: 0 tests were executed!", file=sys.stderr)
    sys.exit(1)
'
}

case "$TARGET" in
    caelis|intervalometer|core)
        info "Running Caelis core test suite (Service, Overlay, Session, Calibration, Plurals, Verify)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.service.*" \
            --tests "com.dazix.caelis.notify.*" \
            --tests "com.dazix.caelis.overlay.*" \
            --tests "com.dazix.caelis.verify.*" \
            --tests "com.dazix.caelis.domain.IntervalScheduleTest" \
            --tests "com.dazix.caelis.domain.SessionPlannerTest" \
            --tests "com.dazix.caelis.data.SettingsMappingTest" \
            --tests "com.dazix.caelis.res.*" \
            --tests "com.dazix.caelis.NetworkIsolationTest" \
            --tests "com.dazix.caelis.ui.CalibrationResolutionTest" \
            --tests "com.dazix.caelis.ui.LaunchModeOptionsTest" \
            --tests "com.dazix.caelis.ui.NavigationDestinationTest" \
            --tests "com.dazix.caelis.ui.SetupUiStateTest" \
            --tests "com.dazix.caelis.ui.WaitProgressTest" \
            --rerun-tasks)
        check_xml_results
        info "Caelis core test suite PASSED."
        ;;

    solar|sun)
        info "Running Sun Trajectory test suite (Solar & Lunar Ephemeris, Trajectory Planning, AR Projection, Camera FOV, Terrain Horizon)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.domain.solar.*" \
            --tests "com.dazix.caelis.domain.horizon.*" \
            --tests "com.dazix.caelis.data.horizon.*" \
            --tests "com.dazix.caelis.ar.*" \
            --rerun-tasks)
        check_xml_results
        info "Sun Trajectory test suite PASSED."
        ;;

    nightsky|stars)
        info "Running Night Sky test suite (Celestial Math, FlatBuffers Catalog, Frustum Culling, Optics, Renderer Zero-Alloc)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.domain.nightsky.*" \
            --tests "com.dazix.caelis.ui.nightsky.*" \
            --rerun-tasks)
        check_xml_results
        info "Night Sky test suite PASSED."
        ;;

    all)
        info "Running ALL test suites, assemble, and Android Lint..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew \
            :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug \
            --rerun-tasks)
        check_xml_results
        info "Full validation PASSED."
        ;;

    *)
        die "Unknown test target: '$TARGET'. Supported targets: caelis (or intervalometer), solar, nightsky, all"
        ;;
esac

# Working in this repository

Private Android intervalometer. It presses the shutter inside another app through an
AccessibilityService. Read `README.md` first for what the app is; this file is how to work on it
without breaking it.

## Non-negotiables

1. **The attached phone is a person's daily driver, not a lab device.** Every setting you change over
   adb must be snapshotted before and restored after — including when the run fails halfway, so the
   restore belongs in a `trap`/`finally`. Use `scripts/a11y_setup.sh` + `scripts/a11y_teardown.sh`
   rather than hand-rolling `settings put`.
2. **All device work happens in the dedicated development user, never the phone's primary profile.**
   Its id is `ANDROID_USER_ID` from `scripts/env.local.sh`. Pass `--user "$ANDROID_USER_ID"` on every
   adb command that takes it, and assert `adb shell am get-current-user` returns that id before
   starting instead of assuming. Install with `adb install -r --user "$ANDROID_USER_ID" …`, never
   `:app:installDebug` — that installs for every user.
3. **Delete the photos a test run created.** Retry the deletes: a frame the camera has not finished
   writing refuses a shell delete for several seconds and is invisible to `content query` while
   pending, so "the query no longer lists it" is not proof it is gone.
4. **Never weaken a test to get green.** No `@Ignore`, no deleted assertions, no lint baselines, no
   `tools:ignore` to silence a warning. If a test is wrong, fix it to be *stricter* and say why.
5. **Evidence before claims.** Do not say something passes without having run it and seen the output.
   Gradle exits 0 on skipped tests — read the result XML.

## Toolchain

Nothing Android is on `PATH`. The paths live in `scripts/env.local.sh` (gitignored; template in
`scripts/env.example.sh`), which is where `ADB`, `JAVA_HOME` and `ANDROID_USER_ID` come from:

```bash
. scripts/env.local.sh          # ADB, JAVA_HOME, ANDROID_USER_ID for this machine
JAVA_HOME="$JAVA_HOME" ./gradlew <tasks>
"$ADB" …
```

The `scripts/*.sh` harness sources `scripts/env.sh` itself, so it needs none of that done by hand —
and it aborts rather than guessing when a value is missing.

AGP 9.3.1 · Gradle 9.5.0 · Kotlin 2.3.20 · compileSdk 37 (`compileSdkMinor = 1`, platform
`android-37.1`) · minSdk 33 · targetSdk 37 · JVM target 17.

Two AGP 9 facts that each cost a build cycle: the `org.jetbrains.kotlin.android` plugin must **not** be
applied (Kotlin support is built into AGP 9), and current AndroidX releases refuse AGP below 9.1.

`androidx.compose.material3` is pinned to `1.5.0-alpha26` in `gradle/libs.versions.toml`, ahead of the
Compose BOM. This is deliberate: the BOM's 1.4.0 ships the Material 3 Expressive tokens but keeps
`MaterialShapes`, `LoadingIndicator`, `ButtonGroup`, `FloatingToolbar`, the flexible app bars and
`MotionScheme.expressive()` out of its public API, so an app cannot actually build in the expressive
system with it. Do not "fix" the pin back to the BOM.

## The gates and scoped test suites

To keep iteration fast (6-7s), unit test suites are partitioned by functional domain. **Always run only the test suite(s) relevant to the current changes during development:**

```bash
# 1. Scoped domain test runners (fast JVM execution):
scripts/run_tests.sh intervalometer # Intervalometer core, service, overlay, session planner, calibration (260 tests)
scripts/run_tests.sh solar          # Sun & Moon trajectory, solar positioning, ephemeris, AR projection (78 tests)
scripts/run_tests.sh nightsky       # Night sky AR, Meeus celestial math, FlatBuffers catalog, star renderer (91 tests)

# 2. Full gate before final commit (all suites + assemble + Android Lint):
scripts/run_tests.sh all
# or directly:
# JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks

# 3. Instrumented device tests on dedicated dev user (when testing device behaviour):
scripts/run_instrumented.sh                    # optional filter: scripts/run_instrumented.sh ScreenOffTest
scripts/run_instrumented.sh StarCatalogDeviceTest

# 4. The end-to-end proof: real Pixel camera, real photos
scripts/e2e_pixel_camera.sh
```

Read the totals from `app/build/test-results/testDebugUnitTest/*.xml` (`tests` / `failures` / `errors` /
`skipped`), not from the console. Current baseline: **429 JVM tests (260 intervalometer, 78 solar, 91 night sky), 20 instrumented tests, 0 failures, 0 skips, lint 0 errors.** Anything less is a regression.

`scripts/README.md` documents the whole harness: the snapshot keys, why the accessibility list has to be
*cycled* rather than written, why the instrumented tests re-enable the service themselves, the debug
fake camera (`--ez unlabeled true` forces the coordinate path) and the broadcast entry points that start
a session from the shell.

## Layout and who owns what

| Package | Rule |
| --- | --- |
| `domain/` | Pure Kotlin, no Android imports. Everything here is unit-tested; keep it that way. |
| `domain/solar/` | Pure Kotlin astronomical & ephemeris calculations (PSA solar positioning, sunrise/sunset, golden hour, twilight, day trajectories, lunar position & ephemeris). |
| `domain/nightsky/` | Pure Kotlin celestial astronomy (Meeus GMST/LST, J2000.0 RA/Dec to Az/Alt, vector frustum culling, star optics & B-V color temperature, planetary ephemeris, constellation tables, search index). |
| `ar/` | AR projection math (`CameraProjection`), aspect-ratio crop aware FOV calculator (`CameraFovCalculator`), rotation vector tracker with True-North declination & user calibration (`OrientationTracker`), GPS location provider (`SolarLocationProvider`), CameraX dynamic preview (`CameraPreview`), and zero-allocation 3D celestial grid renderer (`SkyDomeRenderer`). |
| `data/` | DataStore settings (`SettingsStore`), advanced settings, and the process-wide `SessionRepository` snapshot. |
| `data/nightsky/` | FlatBuffers zero-copy binary catalog schemas and generated accessors (`NightSkyCatalog`, `Star`, `Constellation`, `StarMetadata`). |
| `service/` | The accessibility service owns the session. `SessionEngine` is the state machine, `ShutterDispatcher` is the only thing that taps, `CameraProfiles` is data, `RunStateStore` is the durable run. |
| `overlay/` | The floating panel and the calibration overlay. Timing/placement decisions live in `OverlayPolicy` and `PanelPlacement`, which are framework-free and unit-tested — put new decisions there, not in the view code. |
| `notify/` | The ongoing notification. It re-arms its own tick; it must never post while unattached. |
| `verify/` | The optional MediaStore check. It owns the question of *whose* row counts. |
| `monitor/` | Battery percentage and available storage monitoring (`BatteryMonitor`, `StorageMonitor`). |
| `ui/` | One activity (`MainActivity`), Compose screens (Home, Setup, Ramp, Advanced, Permissions, Settings), Material 3 Expressive theme (`IntervalometerTheme`). |
| `ui/solar/` | Sun AR Compose screen (`SunArScreen`) and ViewModel (`SunArViewModel`). |
| `ui/nightsky/` | Night Sky AR screen (`NightSkyArScreen`), ViewModel (`NightSkyArViewModel`), 60 FPS zero-allocation batch renderer (`NightSkyRenderer`), search bar, bottom sheets, and custom AGSL shaders (Milky Way, atmosphere, landscape). |

`ShutterAccessibilityService` marks its UI wiring with an `==== INTEGRATION POINT ====` block: those are
the only calls into the notification and the panel.

## Invariants that were bugs once

Each of these was found by review or on the device and is now covered by a test. Do not undo them.

- **AR world-to-device mapping is $R^T$ (Transpose).** Android's `SensorManager` rotation matrix $R$
  maps device vectors to world coordinates ($V_{\text{world}} = R \cdot V_{\text{dev}}$). Transforming
  world points (sun, horizon, celestial grid) onto the camera viewport requires the orthogonal inverse
  $V_{\text{dev}} = R^T \cdot V_{\text{world}}$. Direct row multiplication inverts camera motion and
  breaks spatial locking.
- **World frame rotation is pre-multiplied.** True-North magnetic declination and user calibration
  rotate the ENU world frame around world $Z$ (Up): $R_{\text{true}} = R_z(\text{declination} + \text{offset}) \cdot R_{\text{sensor}}$.
  The back camera optical axis in true world coordinates is $[-R_{\text{true}}[2], -R_{\text{true}}[6], -R_{\text{true}}[10]]^T$.
- **AR FOV must account for viewfinder aspect crop.** CameraX `FILL_CENTER` crops the sensor frame to
  fit tall screens (20:9, 19.5:9), altering the horizontal FOV:
  $\text{cropRatio} = \text{viewportAspect} / \text{sensorAspect}$,
  $\text{effectiveFovH} = 2 \arctan(\tan(\text{rawFovH} / 2) \cdot \text{cropRatio})$.
- **Zero allocations on the AR render loop.** `SkyDomeRenderer` uses pre-allocated flat FloatArrays
  and in-place `CameraProjection.projectFast` to ensure zero GC allocations per frame at 60 FPS.
- **Timing is absolute.** Shot times come from `IntervalSchedule.targetElapsedFor`, never "now +
  interval". A late shot must not push the series.
- **A tap is not a shot.** With verification armed, `shotsTaken` moves only when verification resolves
  (`PendingShot` / `VerifyStep.resolvesShot`). A retry never increments it; a shot whose every gesture
  the platform refused is not counted at all; a shot is never counted twice on stop/pause.
- **"Unknown" always means "do not tap."** Screen state, keyguard and the foreground package are checked
  before every gesture with strict equality; an unreadable answer is a pause, not a tap.
- **The calibrated point outranks the region guess.** The bottom-centre "largest clickable node"
  heuristic is consulted last and discarded unless its bounds contain the calibrated point.
- **Calibrations are rotation-independent.** They are stored per package *and* rotation, but read with
  `SettingsStore.anyTarget` — any stored slot is usable, and a read-back miss must never erase one.
  A failed *read* is not "nothing stored": one-shot reads propagate the failure on purpose.
- **PeriodicTap is not a camera.** It runs `CameraProfiles.passthrough`; no camera profile's floor,
  latency or mode aliases may apply to it. Anything gating on a profile must use
  `CameraProfiles.forTrigger(mode)`, not `forPackage(package)`.
- **The panel must never eat a shutter tap, and must never become unreachable.** `PanelPlacement` keeps
  it off every pixel a tap can target (raw display pixels; the window uses `FLAG_LAYOUT_NO_LIMITS` and a
  separate `safe` box for the system bars), and the cloak window is scaled to the interval — at a fast
  periodic tap it is switched off entirely rather than holding `FLAG_NOT_TOUCHABLE` forever.
- **The panel stands down over our own screens** (`OverlayPolicy.suppressForOwnUi`), fed by both the
  accessibility events and our own activity lifecycle. The remembered flag survives hide/show.
- **Stop conditions are evaluated on a guard tick**, not only after a completed shot, so a session that
  is waiting or paused still stops on battery, storage or duration.
- **`FLAG_KEEP_SCREEN_ON` belongs to a session that is actually shooting**, never to a paused or waiting
  one.
- **No `startForeground` on the accessibility service.** It has no foreground service type and the
  manifest declares no `FOREGROUND_SERVICE`; the notification goes through `NotificationManager`.

## Conventions

- **Comments explain *why*, and especially why not.** Most comments in this codebase record a failure
  mode that was actually observed. If you change such code, update the comment to match; if you remove
  the guard, remove the comment and say what replaced it.
- **Strings**: every user-visible string is a resource with an English (`values/`) and a Czech
  (`values-cs/`) entry. Countable nouns are `<plurals>` — Czech needs `one` / `few` / `many` / `other`,
  and the quantity argument for "x of y" is the **total**, not the running count.
- **Compose**: read colour, shape, type and motion off the theme (`MaterialTheme.*`, `ExpressiveMotion`,
  `ExpressiveType`). Inside a card use `LocalContentColor` rather than naming a colour role, unless the
  colour carries meaning (an error really is `colorScheme.error`). Icon-only controls always carry a
  content description; touch targets are at least 48dp; no state is signalled by colour alone.
- **Tests**: a new decision that can be expressed as a pure function goes in `domain/` or a policy object
  and gets a JVM test. Device behaviour gets an instrumented test that fails loudly rather than skipping.
- **Git Commits & Signatures**: Every commit created by an agent must follow conventional commits
  (`feat(...)`, `fix(...)`, `refactor(...)`, `test(...)`), list clear bullet points of changes, and
  always end with the co-author signature trailer:
  ```text
  Co-authored-by: Antigravity <antigravity@google.com>
  ```

## Style of work

- **Scoped test runs during development**: Run only the domain test suite corresponding to your changes (`scripts/run_tests.sh intervalometer|solar|nightsky`). Run the full suite (`scripts/run_tests.sh all`) and device tests only for final verification before commit.
- Do the whole task and report what actually happened, including what failed and what you left out.
- When a subagent reports success, verify it — several "green" reports in this repo's history were not.
- Keep the device restored at the end of every session, and say so with the values, not with a claim.

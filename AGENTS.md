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
6. **No tests during planning or documentation.** When planning (`/plan`), designing implementation
   plans, answering questions, or creating/editing Markdown and documentation files, NEVER run unit tests,
   instrumented tests, or build commands. Test suites belong strictly in the verification step AFTER code implementation.
7. **Commit only on explicit user request.** Never run git commit proactively or automatically. Commits
   must be performed strictly when explicitly requested by the user.
8. **Every commit must include Co-authored-by for the active agent.** When creating a commit upon
   explicit user request, always include the trailer `Co-authored-by: <AgentName> <agent-email>` (e.g.
   `Co-authored-by: Antigravity <antigravity@google.com>`) at the end of the commit message body.

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

**Never run tests during planning, research, or documentation/markdown tasks.** Tests belong exclusively to the verification phase AFTER code changes are made.

To keep iteration fast (6-7s), unit test suites are partitioned by functional domain. **Always run only the test suite(s) relevant to the current changes during development:**

```bash
# 1. Scoped domain test runners (fast JVM execution):
scripts/run_tests.sh caelis # Intervalometer core, service, overlay, session planner, calibration, network isolation (295 tests)
scripts/run_tests.sh solar          # Sun & Moon trajectory, ephemeris, AR projection, terrain horizon, skyline matching (232 tests)
scripts/run_tests.sh nightsky       # Night sky AR, Meeus celestial math, FlatBuffers catalog, star/DSO renderer (129 tests)

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
`skipped`), not from the console. Current baseline: **677 JVM tests (295 intervalometer, 232 solar & horizon, 129 night sky, plus docs/UI), 31
instrumented tests, 0 failures, 0 skips, lint 0 errors.** Anything less is a regression.

`run_tests.sh` filters by package, and a new `domain/` package is picked up by **no** suite until it is added
to one. `domain.horizon.*` and `data.horizon.*` are in `solar`; `NetworkIsolationTest` is in `caelis`.

`scripts/README.md` documents the whole harness: the snapshot keys, why the accessibility list has to be
*cycled* rather than written, why the instrumented tests re-enable the service themselves, the debug
fake camera (`--ez unlabeled true` forces the coordinate path) and the broadcast entry points that start
a session from the shell.

## Layout and who owns what

| Package | Rule |
| --- | --- |
| `domain/` | Pure Kotlin, no Android imports. Everything here is unit-tested; keep it that way. |
| `domain/solar/` | Pure Kotlin astronomical & ephemeris calculations (PSA solar positioning, sunrise/sunset, golden hour, twilight, day trajectories, lunar position & ephemeris, terrain rise/set). |
| `domain/horizon/` | Pure Kotlin terrain horizon: `.hgt` grid decoder, ray-marched skyline with Earth curvature and refraction, the 1440-sample `HorizonProfile`, the automatic search-radius estimate (`HorizonRangeEstimator`), and the sky/terrain boundary finder and fitter behind automatic alignment (`SkylineExtractor`, `SkylineMatcher`). No Android imports, no network. |
| `domain/nightsky/` | Pure Kotlin celestial astronomy (Meeus GMST/LST, J2000.0 RA/Dec to Az/Alt, vector frustum culling, star optics & B-V color temperature, planetary ephemeris, constellation tables, DSOs, meteor showers, visibility schedule `CelestialVisibilityCalculator`, search index). |
| `ar/` | AR projection math (`CameraProjection`), aspect-ratio crop aware FOV calculator (`CameraFovCalculator`), rotation vector tracker with True-North declination & user calibration (`OrientationTracker`), timestamp ring buffer (`SensorTimestampRingBuffer`), quaternion interpolation (`QuaternionSlerp`), dynamic matrix calculation (`DynamicMatrixCalculator`), GPS location provider (`SolarLocationProvider`), CameraX dynamic preview with measured FOV and single-frame analysis (`CameraPreview`), the camera-geometry half of automatic horizon alignment (`SkylineAligner`), and zero-allocation 3D celestial grid renderer (`SkyDomeRenderer`). |
| `data/` | DataStore settings (`SettingsStore`), advanced settings, and the process-wide `SessionRepository` snapshot. |
| `data/horizon/` | Elevation tile download (`DemTileDownloader` — **the only file in the app that touches the network**), hand import (`DemTileImporter`), the mmap tile cache (`DemTileCache`), the profile codec, and `HorizonRepository` which orchestrates the three. |
| `data/nightsky/` | FlatBuffers zero-copy binary catalog schemas and generated accessors (`NightSkyCatalog`, `Star`, `Constellation`, `Dso`, `MeteorShower`, `StarMetadata`). |
| `service/` | The accessibility service owns the session. `SessionEngine` is the state machine, `ShutterDispatcher` is the only thing that taps, `CameraProfiles` is data, `RunStateStore` is the durable run. |
| `overlay/` | The floating panel and the calibration overlay. Timing/placement decisions live in `OverlayPolicy` and `PanelPlacement`, which are framework-free and unit-tested — put new decisions there, not in the view code. |
| `notify/` | The ongoing session notification (`SessionNotifications`, re-arms its own tick, must never post while unattached) and the terrain download progress notification (`TerrainNotifications`, its own channel and id). |
| `verify/` | The optional MediaStore check. It owns the question of *whose* row counts. |
| `monitor/` | Battery percentage and available storage monitoring (`BatteryMonitor`, `StorageMonitor`). |
| `ui/` | One activity (`MainActivity`), Compose screens (Home, Setup, Ramp, Advanced, Permissions, Settings), Material 3 Expressive theme (`CaelisTheme`). |
| `ui/solar/` | Sun AR Compose screen (`SunArScreen`) and ViewModel (`SunArViewModel`). |
| `ui/horizon/` | Terrain AR screen (`TerrainArScreen`), ViewModel (`HorizonViewModel`), AGSL hardware shader canvas (`HardwareTerrainShaderCanvas`), lock indicator (`HorizonLockIndicator`). |
| `ui/nightsky/` | Night Sky AR screen (`NightSkyArScreen`), ViewModel (`NightSkyArViewModel`), 60 FPS zero-allocation batch renderer (`NightSkyRenderer`), search bar, Virtual Clock, DSO legend, detail bottom sheets with local date override, and custom AGSL shaders (Milky Way, atmosphere, landscape). |

`ShutterAccessibilityService` marks its UI wiring with an `==== INTEGRATION POINT ====` block: those are
the only calls into the notification and the panel.

## Invariants that were bugs once

Each of these was found by review or on the device and is now covered by a test. Do not undo them.

- **Disable OIS and EIS on CameraX preview and analysis during AR tracking.** Optical stabilization
  physically shifts the lens and video stabilization distorts the frame, desynchronizing the camera feed
  from the device's IMU data. `Camera2Interop` forces `CONTROL_VIDEO_STABILIZATION_MODE = OFF` and
  `LENS_OPTICAL_STABILIZATION_MODE = OFF`.
- **Camera frame orientation must use timestamp-interpolated sensor readings (SLERP).** Camera frames
  arrive tens of milliseconds after exposure; using the latest live sensor matrix causes drift during
  camera rotation. `SensorTimestampRingBuffer` + `QuaternionSlerp` queries orientation at the exact
  `imageInfo.timestamp`.
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
- **The AR FOV is measured from the stream, not derived from the lens.** `SENSOR_INFO_PHYSICAL_SIZE`
  describes the whole pixel array; what reaches the screen is a `SCALER_CROP_REGION` inside the
  active array, at the resolution CameraX chose, scaled by `PreviewView` FILL_CENTER.
  `CameraFovCalculator.computeStreamHorizontalFov` takes all three and
  `CameraPreview` reads the crop region out of the capture results. Deriving it from the lens alone
  reported 36.7° where fitting the terrain profile to the camera image measured ~32° — 14 % wide,
  which squeezes the whole overlay towards the centre and cannot be corrected by panning. The
  geometry-only path stays as the fallback for devices that report no crop region.
- **A measured FOV already contains the zoom; an unmeasured one does not.** `CameraPreview` says
  which it is handing over, and the view models divide only by the zoom the camera did *not*
  perform. Dividing a measured FOV by the full zoom ratio counts the zoom twice.
- **Zoom belongs to the camera and the overlay, or to neither.** `cameraFovHorizontal` is
  `baseFov / zoomRatio`, so a pinch that never reaches `CameraControl.setZoomRatio` shrinks the
  overlay while the image stays put. `attachCamera` is how the control gets there. Sun AR has no
  zoom at all — it only added a variable to the alignment — so its FOV *is* the measured one.
- **A drag in Sun AR is a calibration, not a pan.** It moves `calibration*` through
  `nudgeCalibrationBy` and never pauses the sensors: the overlay keeps tracking while the finger
  walks it onto the ridge, and letting go snaps nothing back. The freezing pan stays for Night Sky's
  manual mode. The calibration is clamped to ±20° in both axes — a compass is wrong by degrees, and
  anything larger is a mis-drag that would be indistinguishable from a broken magnetometer next time.
- **One compass correction per phone.** `ar_azimuth_offset` / `ar_elevation_offset` are shared by
  both AR screens; the old `sun_ar_*` and `night_sky_*` keys are read only to migrate from and never
  written again. Aligning against a ridge by day is what makes the stars line up at night.
- **Automatic alignment refuses rather than guesses.** `SkylineMatcher` returns null unless enough
  columns carried a boundary, the photographed skyline has at least half the relief the terrain does,
  the residual minimum stands out from the average candidate, and the fit is under 0.5°. A confident
  wrong alignment is worse than none, because from the outside it looks measured. Its vertical offset
  comes from a low quantile of the differences, never the median: trees and roofs stand above the
  bare-earth model and never below it, so a median folds the average tree height into the calibration.
- **The search radius is estimated before the ring is fetched, not after.** `HorizonRangeEstimator`
  works from the observer's own tile, which is mandatory anyway, so a valley floor never downloads
  eight neighbours to discover they change nothing. The bound is real: past the distance where a peak
  of the highest elevation nearby would sink below the skyline already found, nothing out there can
  change the answer.
- **A stored calibration must never freeze the sensors.** `OrientationTracker.setPanOffsets` pauses
  tracking on purpose — it is the drag gesture. Restoring a saved offset goes through
  `setCalibration`, which folds into the live matrix on every sensor sample and leaves tracking
  alone; restoring it through `setPanOffsets` opened the AR screen with the overlay frozen for
  anyone who had ever calibrated, which made the stored value worse than useless.
- **After an alignment the heading is gyro-held, not compass-held.** `OrientationTracker` still draws
  `TYPE_ROTATION_VECTOR` and also listens to `TYPE_GAME_ROTATION_VECTOR`; ψ between the two
  (`YawOffset`, same-timestamp pairs only) carries every compass excursion. An azimuth alignment
  (drag, nudge, determined auto-align) locks ψ for the session (`HeadingLock`) and from then on the
  correction cancels later changes of ψ; a restored calibration does not lock, and a reset or a new
  session unlocks it. Indoors the compass needed +14° by a window and −7° on the roof, and the
  rotation vector alone pulled an aligned overlay off again as soon as the phone turned towards
  different steel. Do not draw the game vector itself: on a Pixel 7 Pro it visibly trailed a tilt.
- **Sensor matrices are row-major.** `SensorManager` writes them row-major; convert a slerped
  quaternion with `QuaternionSlerp.quaternionToRowMajorMatrix`, never the column-major
  `quaternionToRotationMatrix`. Mixing the two transposed the auto-align's synchronized matrix and
  put the first heading of the gyro-held tracker ~90° out.
- **Dropping a pan is not forgetting the calibration.** `resumeSensorTracking` and `startSensors`
  clear `pan*` and keep `calibration*`; only an explicit reset clears both.
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
- **`data/horizon/DemTileDownloader.kt` is the only file in `app/src/main` allowed to touch the
  network.** `NetworkIsolationTest` walks the sources and fails the build on a second one. The app
  used to prove "nothing leaves the device" through the *absence* of `INTERNET`; that permission is
  `normal`, so once declared it cannot be withheld or revoked, and the guarantee is now the source's
  to keep. Do not add a second network caller, an analytics SDK, or an HTTP client dependency without
  deciding, out loud, to give that guarantee up.
- **Deleting the `INTERNET` line from the manifest must keep working.** It is a documented way to
  build from source without a network. `NetworkAvailability.hasInternetPermission()` is how the UI
  finds out at runtime; never gate that on a build flag, which would drift from the manifest.
- **Consent is read per call, never captured.** `DemTileDownloader` takes a `suspend () -> Boolean`
  and evaluates it on every download, so revoking consent in settings takes effect immediately rather
  than at the next process start.
- **A profile is never computed without the tile the observer is standing in.** Without it every ray
  runs out of data inside two kilometres, gives up, and the profile comes back as a flat ring at the
  geometric dip — which renders exactly like a real skyline. `HorizonRepository` treats that one tile
  as mandatory and `HorizonProfile.coveredSamples` counts how many azimuths were actually backed by
  data; below `MIN_USABLE_SAMPLES` the profile is refused rather than drawn. This was a real device
  bug, caused by the next invariant.
- **`DemTileCache.store` must not trim, and `trim` must never evict the working set.** The cap was
  once smaller than a single 60 km working set and trimming ran after every tile, so tile three was
  reclaimed while tile five was still downloading. Reclaiming space from tiles the current run needs
  does not save a download; it corrupts the answer.
- **`HorizonRepository` is a process singleton**, like `SessionRepository`. Three screens read the
  same state; one instance per view model meant the same 26 MB download started twice and two
  coroutines drove one progress notification. Its scope outlives every view model on purpose — a
  download has to survive leaving the screen that started it.
- **Shared horizon settings live in the app's Settings, not on an AR screen.** Radius, eye height,
  download and cache describe where you are standing, not which screen is open; each AR screen keeps
  only its own show/hide switch.
- **The terrain profile is the artefact; the elevation tiles are cache.** Profiles live in
  `filesDir/horizons/` (5.8 kB each) and are what makes the layer work with no signal. Tiles live in
  `cacheDir/dem/` (up to 26 MB each) and Android may reclaim them at any time. Never move a tile into
  `filesDir`, and never make a drawn horizon depend on one still being there.
- **A tile is stored atomically or not at all.** `DemTileCache.store` writes to `<name>.part` and
  renames only after the length is checked against `HgtTile.isPlausibleGridLength`. A truncated
  transfer is still a mappable blob that would invent a skyline out of whatever the missing rows
  decoded to.
- **A void DEM sample is `null`, never 0 m.** `-32768` marks "no measurement", and those sit in steep
  valleys and on glaciers — exactly where a sea-level reading would invent a horizon dip. The
  calculator skips them; `blendCorners` renormalises around them.
- **Observer height comes from the DEM plus eye height, not from `Location.altitude`.** A phone's GPS
  altitude is routinely tens of metres out, which tilts the entire skyline visibly. The DEM value is
  the same surface the ray march measures against.
- **Terrain crossings are compared against the *apparent* elevation.** The profile already has
  refraction folded into an enlarged Earth radius ($R/(1-k)$, $k = 0.13$); comparing the geometric
  elevation would count it once on one side and not the other.
- **The night sky profile strip is sampled NEAREST and interpolated in the shader.** Elevation is
  packed 16-bit across R and G; hardware filtering would blend the packed bytes, and an interpolated
  R channel across a carry boundary decodes to a wild number, not to a halfway elevation.
- **The profile strip and the landscape panorama do not share a texture coordinate.** The strip
  starts at north (texel 0 is azimuth 0); the panorama has north in the middle of the image, which is
  what its `texU`'s `+ 0.5` is for. Reading the strip with `texU` drew the whole terrain horizon half
  a turn out — a ridge due north appeared due south — and it looked entirely plausible, because a
  horizon is a horizon whichever way you face. `NightSkyHorizonOrientationTest` renders the real
  shader against a profile with terrain in one direction only and checks all four cardinal points, so
  a quarter-turn is caught as well as a half-turn.
- **Terrain download notifications use `IMPORTANCE_DEFAULT` with muted sound and vibration.**
  Android's `IMPORTANCE_LOW` categorises notifications into the Silent section, where Android
  SystemUI suppresses the status bar icon by default. `IMPORTANCE_DEFAULT` with `setSound(null, null)`
  and `enableVibration(false)` guarantees visibility in the status bar next to the clock while keeping
  the download and compute operations completely silent.
- **The notification permission prompt on first launch is one-shot.** On Android 13+ (API 33+),
  `POST_NOTIFICATIONS` is prompted once on first launch and remembered in DataStore
  (`notification_permission_prompted`). The app never nags the user again on subsequent opens
  regardless of whether they accepted or declined.
- **AR projections scale immediately on zoom without waiting for camera metadata.** CameraX capture
  results and crop regions lag behind `setZoomRatio` by tens of milliseconds. Decoupling the AR geometry
  scaling via `zoomFactor` computed directly from the requested zoom ensures instant 60/120 Hz response
  without overlay tearing or lag.
- **Edge back gestures in AR must not be consumed by pointerInput.** The 28dp horizontal edge margins in
  Sun AR and Night Sky AR allow system predictive back and edge swipes to pass through to the OS without
  triggering accidental pan, calibration, or false tap selections.
- **Contextual back handler in AR screens prioritizes dismissing active overlays.** Pressing back must
  dismiss open bottom sheets (layer settings, object details), search bar, or disambiguation dialogs
  before navigating out of the AR screen.
- **Planetarium mode memorizes and restores prior Milky Way state.** Toggling Planetarium mode (opaque
  background) temporarily turns off the procedural Milky Way for a clean star map, and restoring the
  normal AR camera background restores the Milky Way to its previous state unless explicitly changed by
  the user.
- **Celestial hit-testing scores both brightness and proximity.** In dense star fields, Euclidean distance
  alone selects faint background stars over prominent navigation stars. `StarHitTester` combines
  normalized distance and magnitude weights with a disambiguation popup for close candidate targets.
- **Local date override in celestial detail sheet must not alter global AR time or trigger render loop allocations.**
  `CelestialDetailBottomSheet` calculates visibility asynchronously for the chosen date via `CelestialVisibilityCalculator`
  using an isolated local `Instant`, leaving `NightSkyArViewModel.timeState` and the 60 FPS render loop untouched.
- **DSO rendering uses hybrid homography and vector glyphs.** In close zoom, WebP textures are projected via 4-corner
  `Matrix.setPolyToPoly` homography; in wide field, vector glyphs are rendered with zero GC allocations.
- **Virtual Clock time scrubber preserves stable layout during drag.** The timeline scrubber provides continuous
  $\pm 12\,\text{h}$ simulation without resizing bottom bar containers or triggering recomposition jitter.

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

- **Scoped test runs during development**: Run only the domain test suite corresponding to your changes (`scripts/run_tests.sh caelis|solar|nightsky`). Run the full suite (`scripts/run_tests.sh all`) and device tests only for final verification before commit.
- Do the whole task and report what actually happened, including what failed and what you left out.
- When a subagent reports success, verify it — several "green" reports in this repo's history were not.
- Keep the device restored at the end of every session, and say so with the values, not with a claim.

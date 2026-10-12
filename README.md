# CAELIS

[![CI](https://github.com/Dazix/android-caelis/actions/workflows/ci.yml/badge.svg)](https://github.com/Dazix/android-caelis/actions/workflows/ci.yml)

An Android intervalometer that presses the shutter **inside the camera app you already use**, on a
drift-free schedule, so every frame of a time-lapse keeps that app's full image pipeline — HDR+, Night
Sight, the lot. It can also just tap a point on the screen at a fixed rate in any app at all.

It is a clone of the idea behind *Intervalometer for TimeLapse* (MobilePhoton), built to be **built and
sideloaded from source** for one reason: that kind of app needs the **accessibility permission**, which
is the most powerful permission Android hands out, and this way the source of the app holding it is on
your own disk.

**Network.** The app declares `INTERNET` for exactly one thing: fetching terrain elevation tiles for
the real-horizon layer in the AR views, and only after you say yes. Nothing else in the app opens a
connection, there is no analytics of any kind, and all that is ever sent is the name of a 1°×1° map
square — no coordinates, no identifiers, no request body. (The one optional exception is Google's
ARCore, which is off by default and is not this app's traffic; see the paragraph on it below.)

`INTERNET` is a `normal` permission: Android grants it at install time and gives you no switch to
revoke it, so this is a promise the source keeps rather than one the operating system enforces. Three
things make it checkable instead of merely stated:

- every socket in the app is opened in [`DemTileDownloader.kt`](app/src/main/java/com/dazix/caelis/data/horizon/DemTileDownloader.kt),
  and `NetworkIsolationTest` fails the build if a second place ever appears;
- the consent flag is read on every call, so revoking it in Settings takes effect immediately;
- **deleting the `INTERNET` line from the manifest is a supported way to build this from source.**
  The app notices at runtime, hides the download entirely, and the terrain horizon still works from a
  file you import by hand.

**ARCore is the one exception to "nothing else leaves the device", it is off by default, and it is
Google's traffic rather than this app's.** The AR screens can optionally use Google's ARCore for a
steadier pose. ARCore is not code in this app: it is *Google Play Services for AR*, a separate package
that the `com.google.ar:core` library talks to. With the **Use ARCore** switch in Settings turned on,
that package sends Google device identifiers, usage data and diagnostics while an AR screen is open.
With the additional **Geospatial** switch on, it sends more: camera-derived visual data and your
location, to Google's Visual Positioning Service. This app cannot see, filter or limit any of it, and
the claims above about what the app itself sends do not describe it.

- **Both switches are off by default, and with them off the default path never starts ARCore.** Not a
  session, not an availability probe, not an install prompt: the AR screens run on the phone's own
  sensors and the camera, as they always did. Geospatial can never be on while ARCore is off, and the
  switch is not even shown in a build that has no Geospatial API key.
- **Turning a switch on shows what it sends first**, and turning it off is immediate: the answer is
  read again on every use, so a session that is starting when you withdraw it gives up before it
  opens the camera.
- **A missing Google component is never installed behind your back.** This build does not even offer
  the install; without the component the app simply stays on its own sensors.
- **ARCore gives the camera back as soon as you zoom past 1x**, and hands the pose back to the
  sensors whenever it is not tracking well or has gone quiet.
- **The key for Geospatial is yours.** It is read from `local.properties` (`geospatial.api.key`) or
  the `CAELIS_GEOSPATIAL_API_KEY` environment variable when you build, never committed, and it ends up
  in the APK's manifest, so restrict it by package name and signing certificate in the Google Cloud
  console.

What the network-isolation test now proves is therefore narrower, and worth saying plainly: it covers
**this app's own code** (one file opens sockets; one file may start ARCore, and only behind consent; the
permission set is pinned), not what a Google component does once you have allowed it to run.

---

## What it does

**Two trigger modes**

| Mode | What it does |
| --- | --- |
| **Time-lapse** | Brings the chosen camera app to the front and presses its shutter on schedule. Pauses itself if that app stops being the app in front, and resumes when it comes back. |
| **Periodic tap** | Taps a calibrated point (or a random pixel inside a calibrated rectangle) at a fixed rate, in whatever app is on screen. No camera profile, no capture latency — the floor is one tap. |

**The session**

- Interval, shot count or unlimited, start delay, pause/resume/stop.
- **Ramping**: start interval → end interval, linear or exponential, spread over a number of shots or
  over a duration — a sunset time-lapse that slows down as the light goes.
- **Auto-stop** on shot count, elapsed duration, battery percentage or free storage. All of them are
  checked while the session is waiting or paused as well, not only after a completed shot.
- **Floating panel** over the camera app: shots taken, countdown, pause, stop, and two comfort toggles
  (keep the screen on, dim the panel). It is placed so it can never sit on a pixel a shutter tap will
  land on, it hides itself over the app's own screens, and it drops its touches around each shot.
- **Ongoing notification** with the same live countdown and pause/stop actions.
- **Optional capture verification**: after each shot the app checks MediaStore for a new photo that the
  camera app itself wrote, retries the shutter once if none appeared, and flags the session instead of
  silently shooting nothing. Off by default; the photo permission is only requested when you turn it on.

**Sun & Moon AR (Augmented Reality Solar & Lunar Planning)**

Built-in augmented reality module for planning solar and lunar time-lapses (sunsets, sunrises, golden hour, moon phases, twilight):

- **Static 3D Celestial Dome**: Projects solar and lunar trajectory curves, hourly time markers, and solstices
  locked 1:1 to the real physical landscape using perspective matrix transpose projection ($R^T$).
- **3D Celestial Wireframe Grid**: 12 vertical azimuth meridians from Nadir ($-90^\circ$) to Zenith
  ($+90^\circ$) and 7 altitude parallels ($-30^\circ, -15^\circ, +15^\circ, +30^\circ, +45^\circ, +60^\circ, +75^\circ$)
  with degree elevation tags.
- **24/7 Directional Pointers**: Real-time vector sun glyph and lunar indicator with directional chevron arrows
  and elevation badges (e.g. `☀️ -27°` at night) guiding the user towards celestial bodies in any direction.
- **Dynamic FOV & Aspect Crop Calculation**: Dynamically queries `CameraCharacteristics` (sensor physical size
  and focal length) and calculates effective horizontal FOV compensating for `PreviewView.ScaleType.FILL_CENTER`
  on modern tall displays (20:9, 19.5:9).
- **Magnetic Declination & Calibration**: Real-time True-North geomagnetic correction plus 1:1 pixel-to-angle
  drag gestures to compensate for metallic tripod magnetic distortion, with 1-tap reset.
- **Twilight & Terrain Sunset Continuation**: Precise Civil, Nautical, and Astronomical twilight phases plus solid
  daytime trajectory continuing down to $-15^\circ$ below the visual horizon for framing sunsets behind hills and buildings.
- **Real Horizon Silhouette**: Optional terrain skyline computed from a 1 arcsecond (~30 m) elevation model by
  ray-marching 1440 azimuths with Earth curvature and standard atmospheric refraction ($k = 0.13$), drawn as a
  polyline locked to the landscape. It gives the **sunset that actually happens** - "the Sun goes behind the
  ridge at 18:42", 25 minutes before the almanac's 19:07 - and dims the stretch of the trajectory that is
  hidden, so it is obvious how much of the golden hour the hills take. Off by default; the elevation data is
  either downloaded once with your consent or imported by hand, and the computed profile then works offline.
- **Red Vision Mode**: Astrophotography dark red theme (`#FFFF1744`) preserving night adaptation.
- **Two-Row Floating HUD**: Row 1 with GPS fix status & location, Row 2 with Compass Azimuth and Lens FOV.

**Night Sky AR (Real-Time 60 FPS Astrophotography & Planetarium)**

Augmented reality planetarium for night sky framing, astrophotography alignment, and celestial navigation:

- **Zero-Allocation 60 FPS Star Field**: Directly reads pre-compiled binary FlatBuffers star catalog (`catalog.bin`)
  from memory with zero GC allocations on the render loop, utilizing 3D vector Frustum Culling on the celestial sphere.
- **Physical Star Optics**: Magnitude-to-radius power curve scaling, Johnson $B-V$ color index to accurate RGB
  temperature mapping, and realistic atmospheric scintillation (twinkle).
- **88 IAU Constellations & Asterisms**: Stick-figure line asterisms, meet-in-the-middle animations, focus region
  spatial hysteresis ($0.45 W / 0.55 W$) to eliminate hand jitter, and perspective artwork illustration overlays
  mapped via 4-corner homography (`Matrix.setPolyToPoly`).
- **Messier DSO Catalog & Hybrid Rendering**: 110 Deep Sky Objects (galaxies, nebulae, open/globular clusters) with
  dual-state hybrid rendering: 4-corner homography WebP photographic textures (`Matrix.setPolyToPoly`) in deep zoom and
  vector glyphs in wide view, symbol legend sheet, and landscape occlusion filtering.
- **Meteor Shower Radiants**: Major annual meteor showers (Perseids, Geminids, Quadrantids, etc.) with radiant positions,
  velocity vectors, ZHR (Zenithal Hourly Rate), peak date formatting, and active shower highlighting.
- **Dynamic Planetary Ephemeris**: Real-time Keplerian orbital elements (Simon/Standish & Meeus lunar theory) for
  all 8 planets and the Moon with dual-state rendering (soft optical point light with `RadialGradient` vs photorealistic
  3D textures with deep zoom).
- **Virtual Clock & Time Travel**: Continuous time scrubber ($\pm 12\,\text{h}$ range), custom Date and Time pickers for
  future/past celestial simulation, Live vs. Paused modes, and zero layout shift during time scrubbing.
- **Celestial Visibility Schedule & Local Date Override**: Precise daily Rise, Transit/Culmination, and Set times
  computed against the physical terrain (`HorizonProfile`) with bisection search. Features an isolated local date override
  (stepper and date picker) in the object detail sheet that updates visibility without altering global AR time.
- **Celestial Search & Smart Camera Routing**: Search index with prioritized ranking (Czech / Latin / IAU names / Messier) and
  search history. Features animated camera routing in AR mode (freeze-frame accelerate-decelerate re-calibration) and
  interactive manual virtual planetarium pan mode (FastOutSlowIn S-curve).
- **Adaptive Celestial Hit-Testing & Disambiguation**: Weighted brightness and proximity scoring (`StarHitTester`),
  interactive disambiguation popup card for selecting between close candidate objects, configurable tap radius (16–120 dp),
  and animated touch indicator.
- **Fluid Gestures & Immersive Fullscreen AR**: Touch-slop aware 60/120 Hz pinch zoom and drag pan, 28dp edge exclusion
  zones preserving system back gestures, contextual back handling (dismisses sheets/search first), and immersive mode
  hiding system bars with transient swipe reveal.
- **Procedural GPU AGSL Shaders & Planetarium Mode**: Procedural volumetric Milky Way galaxy shader, Rayleigh/Mie atmospheric
  scattering, horizon landscape silhouette (real 1440x1 terrain strip), and Planetarium mode with automatic Milky Way state memorization and restoration.
- **Layer Controls & Detail Sheets**: Layer toggles logically grouped into cards (objects, constellations, environment),
  visual magnitude cutoff slider, and rich celestial body bottom sheet with astronomical distance, magnitude, rise/transit/set schedule, and symbol legend.

**Universal targeting.** The engine knows nothing about any particular camera. It needs "which package,
which shutter matcher, which calibrated point". Camera-specific knowledge lives in data-only
`CameraProfile` rows, so adding an app is a table entry, not code:

| Profile | For | Capture latency | Interval floor |
| --- | --- | --- | --- |
| `pixelCamera` | Google Camera (`com.google.android.GoogleCamera`, GCam ports) | 2200 ms | 2270 ms |
| `generic` | every other camera app | 900 ms | 970 ms |
| `passthrough` | periodic tap | 0 ms | 70 ms (one tap) |

Unknown camera apps work out of the box through the generic matchers plus your calibration.

**Optional, device-specific extras** — every one off by default, and hidden when the current app or
device cannot support it: launch the camera into a named mode (Photo / Night Sight / Astro…), adaptive
wait while the camera is busy with a long exposure, ensure the self-timer is off, clear the post-capture
chip between shots.

**Languages**: English and Czech, with real Czech plurals.

---

## Requirements

- Android 13 (API 33) or newer. Developed and tested on a Pixel 7 Pro on Android 17 (API 37).
- A camera app. Google Camera has a profile; anything else uses the generic one.
- To build: JDK 21 and an Android SDK with platform **android-37.1** and build-tools 37.

## Build and install

### Debug APK
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a device with more than one user, install for **one** profile explicitly — `:app:installDebug`
installs for every user on the device:

```bash
adb install -r --user <id> app/build/outputs/apk/debug/app-debug.apk
# or run the dedicated helper:
scripts/install_debug.sh
```

### Release Android App Bundle (.aab) & Play Store Deployment
Build a signed release bundle locally with interactive password prompt and keystore auto-discovery:
```bash
./scripts/build_release_bundle.sh
```
The output `.aab` will be generated at `app/build/outputs/bundle/release/app-release.aab`.

**Automated CI/CD Deployment:**
The repository includes a GitHub Actions workflow [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) implementing a *Build Once, Promote Everywhere* release pipeline:
1. **Push to `main`**: Builds a signed release `.aab` bundle with an incremental `versionCode` (`${{ github.run_number }}`) and uploads it to Google Play **Internal Testing** (`track: internal`).
2. **GitHub Pre-Release (`published` / `prereleased`)**: Promotes the active release from `internal` to `beta` (Open Testing) via `scripts/promote_release.py` without re-compilation.
3. **GitHub Full Release (`published` / non-prerelease)**: Promotes the active release from `beta` to `production` via `scripts/promote_release.py` without re-compilation.


## First run

The Permissions screen lists every step with live state and a button for each. In order:

1. **Allow restricted settings** — Android blocks the accessibility switch for sideloaded apps until you
   use App info → ⋮ → *Allow restricted settings*. The app cannot detect this directly; the row reports
   itself as done once the accessibility service actually turns on.
2. **The accessibility service** — the only way an app can press a button in another app.
3. **Draw over other apps** — needed for the calibration overlay and for the floating panel.
4. **Notifications** — the ongoing session notification.
5. **Battery optimisation exemption** — for long unattended sessions.

Then pick the camera app, tap **Calibrate**, and tap the shutter once in the overlay that appears over
the real camera app (or drag a rectangle to tap a random pixel inside it). The calibration is stored as
screen *fractions* per app, so it survives rotation and a different screen size.

While a session runs the screen has to stay on and unlocked, and the target app has to stay in front:
the app presses the shutter the same way your finger does, and neither works on a locked screen. The
session pauses itself and says so rather than tapping into the wrong app.

---

## How to develop

Two files are needed that a fresh clone does not have, because both describe your machine rather than
the project. Both are gitignored.

**1. `local.properties`** — where your Android SDK is. Android Studio writes it the first time it opens
the project; by hand it is one line:

```properties
sdk.dir=/path/to/Android/Sdk
```

**2. `scripts/env.local.sh`** — what the device harness in `scripts/` needs. Nothing Android has to be
on your `PATH`; these three values are the whole configuration:

```bash
cp scripts/env.example.sh scripts/env.local.sh    # then fill in the three values
```

| Variable | What it is |
| --- | --- |
| `ADB` | full path to `adb`, usually `<sdk>/platform-tools/adb` |
| `JAVA_HOME` | the JDK Gradle runs on. `scripts/env.sh` exports it, so `./gradlew` needs nothing from your shell profile. |
| `ANDROID_USER_ID` | the Android user the harness is allowed to modify — see below |

There is no default for any of them. A script that guessed would be a script that modified the wrong
phone, so instead it stops:

```
env.sh: FATAL: ANDROID_USER_ID is not set. Copy scripts/env.example.sh to scripts/env.local.sh and
fill it in (it is gitignored), or export ANDROID_USER_ID for this run.
```

### The development user

The harness does things you do not want done to a profile you actually live in: it enables an
accessibility service, rewrites `enabled_accessibility_services` and `accessibility_enabled`, grants and
revokes app ops and runtime permissions, and deletes rows from MediaStore. So it refuses to run anywhere
except a **secondary Android user you created for development**.

Create one in Settings → System → Multiple users, switch to it, and read its id back:

```bash
adb shell pm list users        # UserInfo{11:Developer:...}  ->  ANDROID_USER_ID=11
```

That user has to be the one in the foreground while a script runs: every script asserts
`adb shell am get-current-user` matches `ANDROID_USER_ID` and aborts otherwise. Install for it
explicitly — `:app:installDebug` installs for *every* user on the device, the one you live in included:

```bash
adb install -r --user "$ANDROID_USER_ID" app/build/outputs/apk/debug/app-debug.apk
```

Everything the harness changes is snapshotted to `.device_snapshot` before it is touched and restored
from a `trap` afterwards, whether the run passes, fails or is interrupted. `scripts/README.md` documents
the snapshot keys, why the accessibility list has to be *cycled* rather than written, the debug fake
camera, and the broadcast entry points that start a session from a shell.

### The gates

```bash
# 1. Scoped domain unit tests (fast JVM execution):
scripts/run_tests.sh caelis # CAELIS core, service, overlay, calibration, network isolation (309 tests)
scripts/run_tests.sh solar          # Sun & Moon trajectory, ephemeris, terrain horizon, skyline matching (438 tests)
scripts/run_tests.sh nightsky       # Night sky AR, Meeus celestial math, FlatBuffers catalog, star/DSO renderer (265 tests)

# 2. Full gate before final commit (all suites + assemble + Android Lint):
scripts/run_tests.sh all
# or directly:
# ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug

# 3. The real engine on the real device (installs, runs, restores)
scripts/run_instrumented.sh

# 4. The proof: real camera, real photos
scripts/e2e_pixel_camera.sh
```

Read the totals from `app/build/test-results/testDebugUnitTest/*.xml` (`tests` / `failures` / `errors` /
`skipped`), **not** from the console: Gradle exits 0 when every test skipped itself, which is exactly
what a dead accessibility service produces. Current baseline: **921 JVM tests (309 intervalometer/caelis, 461 solar, horizon & pose, 272 night sky, with the last two
overlapping on the shared AR frame maths, plus 17 documentation screenshots), 42 instrumented tests, no
failures, no skips, lint clean.**

---

## How it is put together

```
com.dazix.caelis
├── domain/          pure Kotlin, no Android imports — schedule, ramp maths, stop conditions, session state
│   ├── horizon/     pure Kotlin terrain horizon — .hgt decoder, ray-marched skyline with curvature & refraction
│   ├── solar/       pure Kotlin solar & lunar ephemeris, day times, trajectory planner, PSA/Meeus algorithms
│   └── nightsky/    pure Kotlin celestial astronomy (Meeus GMST/LST, J2000.0 RA/Dec to Az/Alt, frustum culling,
│                    star optics, planetary ephemeris, constellation tables, DSOs, meteor showers, visibility schedule, search index)
├── ar/              AR projection (R^T matrix), crop-aware FOV calculator, rotation & location providers, the camera
│   │                picture handed to the GPU (CameraFrameSource), automatic horizon alignment
│   ├── pose/        which pose draws the overlay: the phone's sensors, or optionally ARCore (consent-gated; the only
│   │                place ARCore is started), the hand-over between them and who owns the camera
│   └── scene/       the Filament sky renderer both AR screens draw with, including the camera picture
├── data/            DataStore settings (incl. the ARCore consent), calibrations, and the process-wide session snapshot
│   ├── horizon/     elevation tile download (the only network code), import, mmap cache, profile codec
│   └── nightsky/    FlatBuffers zero-copy binary catalog schemas and generated accessors (Star, Constellation, Dso, MeteorShower)
├── service/         ShutterAccessibilityService (owns the session), SessionEngine, ShutterDispatcher,
│                    CameraProfile(s), RunStateStore (durable run), SessionAlarmReceiver
├── overlay/         the floating panel, its placement/cloak/dim policy, and the calibration overlay
├── notify/          the ongoing session notification, and terrain download progress
├── verify/          the optional MediaStore capture check
├── monitor/         battery and storage
└── ui/              activity, Compose screens (Home, Setup, Ramp, Advanced, Permissions, Settings), Material 3 Expressive theme
    ├── solar/       Sun AR screen, ViewModel, and 60 FPS zero-allocation 3D celestial canvas renderer
    └── nightsky/    Night Sky AR screen, ViewModel, 60 FPS batch star & DSO renderer, search bar, Virtual Clock, bottom sheets, AGSL shaders
```

**The accessibility service owns the session.** Only an accessibility service may call
`dispatchGesture`, so the timing engine lives inside it; the activity is a thin client that reads and
writes state through a repository and can be killed at any time.

**Timing is drift-free.** Every shot time is an absolute `elapsedRealtime` target derived from the
session anchor, never "now + interval", so a late shot cannot push the ones after it. Gaps under 60 s
wait on a `Handler` with a partial wake lock; longer gaps use `AlarmManager.setExactAndAllowWhileIdle`
and release the wake lock in between.

**The shutter is pressed twice over.** First the accessibility node search (resource id, then content
description, then a bottom-centre region guess, and that guess is only accepted if it contains your
calibrated point); if that finds nothing clickable, a dispatched tap on the calibrated point.

**A tap is not a shot.** With verification on, a shot counts only once its verification resolves, and
the single re-tap never counts twice. A shot whose every gesture the platform refused is not counted at
all.

**UI** is Material 3 Expressive: `MaterialExpressiveTheme` with `MotionScheme.expressive()`, dynamic
colour, flexible app bars, tonal toggle groups, a floating toolbar with a docked action button that
morphs between Start and Stop, and the expressive loading indicator for the wait to the next frame.
`androidx.compose.material3` is pinned to `1.5.0-alpha26` ahead of the Compose BOM on purpose — the
BOM's 1.4.0 ships the expressive *tokens* but keeps the components out of its public API.

---

## Testing

```bash
# JVM: schedule and ramp maths, stop conditions, settings round-trips, overlay policy, UI state, astronomical math
scripts/run_tests.sh all               # or scoped: scripts/run_tests.sh caelis|solar|nightsky

# on a connected device: the real engine, the real service, a fake camera, FlatBuffers zero-copy validation
scripts/run_instrumented.sh            # optional filter: scripts/run_instrumented.sh ScreenOffTest
scripts/run_instrumented.sh StarCatalogDeviceTest

# the proof: the real Pixel camera, real photos on disk, counted in MediaStore
scripts/e2e_pixel_camera.sh
```

The instrumented tests cover tap accuracy and drift, both ramp curves, the auto-stop conditions,
capture verification (including the single retry), screen-off and keyguard, the foreground guard, the
guard tick, a periodic tap far below any camera profile's floor, the calibrated point beating the region
guess, and a run left on disk being picked back up.

`scripts/run_instrumented.sh` decides pass/fail from the result XML, not from Gradle's exit code, and
fails on zero tests or any skip — a green report full of skipped tests is how a dead accessibility
service once "passed". See `scripts/README.md` for the whole harness, the debug fake camera and the
broadcast entry points that drive a session from the shell.

### Device hygiene

Every script snapshots the device state it is about to change (accessibility settings, app ops, runtime
grants) and restores it in a `trap`, so the device comes back to exactly what it was even when a test
fails halfway. The accessibility list is only ever appended to and then restored verbatim, so other
services on the device keep working. `e2e_pixel_camera.sh` deletes exactly the photos it created,
retrying while the camera is still writing them.

---

## Trade-offs worth knowing

- **The floating panel is always dark**, even in light mode: it floats over a camera preview, and a
  light panel ruins dark adaptation on a night shoot.
- **The panel and the notification count down as `0:09`; the app's own screen says `9 s`.** Two
  glanceable surfaces against one screen you are actually reading.
- **Capture verification costs time.** The window is the profile's capture latency plus grace, so a shot
  is confirmed seconds after the tap and a `maxShots` session ends that much later. That delay is what
  leaves room for the one honest re-tap.
- **`QUERY_ALL_PACKAGES`** is declared so the picker can list every launchable app. The list never
  leaves the device.
- **The terrain horizon cost the app its "no network permission" guarantee.** That was a real
  argument in an app holding the accessibility permission, and it is now a code-level promise instead
  of a system-enforced one. The compensation is the isolation above, the one-line manifest edit that
  takes it back, and a hand-import path that is a first-class citizen rather than a fallback.
- **The optional ARCore switch weakens that promise further, in a way the source cannot repair.** With
  it on, Google Play Services for AR runs inside your session and reports to Google on its own terms.
  The compensation is that it is off by default, consent-gated on every start, started from exactly one
  file, and that a build without a Geospatial key simply never offers Geospatial. The promise that
  "nothing leaves the device" now holds for this app's own code only.
- **The elevation model is bare earth.** It knows the hills; it does not know the trees or the
  buildings on them. The silhouette is where the ground is, which on a wooded ridge is a few degrees
  below where the sky actually starts.
- **A terrain profile is tied to where you stood.** Move more than about 110 m and it is recomputed;
  the elevation tiles it came from live in the cache and Android may reclaim them at any time, but
  the computed profile is kept and works with the radio off.

---

## Contributing

- **Run all three gates** before saying something works, and read the result XML rather than Gradle's
  exit code.
- **Never weaken a test to get green.** No `@Ignore`, no deleted assertions, no lint baselines. If a
  test is wrong, make it stricter and say why.
- **A new decision that can be a pure function goes in `domain/` or a policy object**, with a JVM test —
  not into a view or a service. Device behaviour gets an instrumented test that fails loudly instead of
  skipping.
- **Comments explain why, and especially why not.** Most of the comments here record a failure mode that
  was actually observed on a device; if you change that code, update the comment to match.
- **Every user-visible string is a resource** with an English (`values/`) and a Czech (`values-cs/`)
  entry. Countable nouns are `<plurals>`; Czech needs `one` / `few` / `many` / `other`.

`CLAUDE.md` is the same material in the form a coding agent needs it.

---

## Licence

Copyright © 2026 David Šanda.

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU
General Public License, version 3** or (at your option) any later version, as published by the Free
Software Foundation. It is distributed in the hope that it will be useful, but **without any warranty**;
without even the implied warranty of merchantability or fitness for a particular purpose. See
[`LICENSE`](LICENSE) for the full text.

# CAELIS — details and development

The [README](../README.md) says what the app is. This file holds the rest: the full feature list, exactly
what touches the network, how to build, test and release it, and how it is put together.

- [Feature details](#feature-details)
- [Network and ARCore](#network-and-arcore)
- [Requirements](#requirements)
- [Build and install](#build-and-install)
- [First run](#first-run)
- [How to develop](#how-to-develop)
- [How it is put together](#how-it-is-put-together)
- [Testing](#testing)
- [Trade-offs worth knowing](#trade-offs-worth-knowing)
- [Contributing](#contributing)

---

## Feature details

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
- **Virtual Places**: Plan a sunrise somewhere you are not. Save a place by name and coordinates (decimal,
  DMS, or a pasted map link, or share it straight from Mapy.com, Google Maps or any `geo:` link) with its time zone, pick it from the map button in Sun AR or Night Sky, and the
  app fetches that place's terrain and shows where and when the Sun and Moon rise and set behind its own
  skyline, in its local time. Turning the phone looks around the place; the camera and automatic alignment
  are off while it is shown, and a neutral backdrop with the place's terrain stands in for the picture. The
  two screens choose their place independently unless you switch on syncing. Places stay on the phone and
  out of the Google account backup.
- **Peak Names**: The peaks that really stand on that skyline are labelled "Sněžka · 1603 m" (in Czech, the
  Czech name; otherwise the English one, then the local one) in both Sun AR and Night Sky, with a switch per
  screen that works only while the terrain is on. A peak is drawn only when it *is* the ridge at its
  azimuth, so a summit hidden behind a nearer hill gets no label; the names come from OpenStreetMap
  (© OpenStreetMap contributors, ODbL) as 1° tiles that are downloaded with the elevation, stored with the
  profile, and drawn with no signal. The tiles cover the world except Antarctica and a few islands
  the mapsforge extracts leave out (the Lesser Antilles, Bermuda, the Falklands), where the skyline
  is drawn without names. If the names cannot be fetched the skyline is drawn without them.
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
- **Celestial Search & Smart Camera Routing**: Search index with prioritized ranking (names in the app's language / English / IAU names / Messier) and
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

**Languages**: English, Czech, Slovak, Polish and German, with each language's real plurals and its own
names for the night sky. Austria gets the German strings; dates and months come from the platform in the
Austrian form.

## Network and ARCore

**Network.** The app declares `INTERNET` for exactly one thing: fetching terrain elevation tiles, and
the names of the peaks on them, for the real-horizon layer in the AR views, and only after you say yes.
Nothing else in the app opens a connection, there is no analytics of any kind, and all that is ever sent
is the name of a 1°×1° map square — no coordinates, no identifiers, no request body. Elevation comes from
the Tilezen tiles on AWS; peak names come from the public
[`Dazix/caelis-landmarks`](https://github.com/Dazix/caelis-landmarks) repository, pinned to the tag `v1`
so an installed app only ever downloads a format it can read. (The one optional exception is Google's
ARCore, which is off by default and is not this app's traffic; see the paragraph on it below.)

`INTERNET` is a `normal` permission: Android grants it at install time and gives you no switch to
revoke it, so this is a promise the source keeps rather than one the operating system enforces. Three
things make it checkable instead of merely stated:

- every socket in the app is opened in [`DemTileDownloader.kt`](../app/src/main/java/com/dazix/caelis/data/horizon/DemTileDownloader.kt),
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

## Requirements

- Android 11 (API 30) or newer. Developed and tested on a Pixel 7 Pro on Android 17 (API 37); CI opens
  the app on an Android 11 emulator, and no physical Android 11 or 12 phone has been tested. Night Sky
  and Sun AR may run below the display refresh rate on the GPUs of those years.
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
The repository includes a GitHub Actions workflow [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) with one stage per Google Play track. Every build is a signed release `.aab` with an incremental `versionCode` (`${{ github.run_number }}`) and its R8 mapping:
1. **Push to the `internal` branch**: builds and uploads to **Internal testing** (`track: internal`).
2. **Push to `main`**: builds and uploads to **Closed testing** (`track: alpha`). This is the track Google counts for the 12-testers-for-14-days rule of new personal developer accounts.
3. **GitHub pre-release** (`prereleased`): builds the tag and uploads it to **Open testing** (`track: beta`). Open testing stays unavailable until the account has production access.
4. **GitHub release** (`released`, including a pre-release turned into a release): promotes the active `beta` release to `production` via `scripts/promote_release.py` without re-compilation.

**Release notes ("What's new" on Play)** live in `release-notes/<locale>.txt`, one plain-language line per change, in every listing language. A pull request with a change users will notice adds a line to each file; nothing is ever renamed or reset for a release. `scripts/release_notes.py` sends the lines added since the latest GitHub release: a build of `main` carries everything not yet released, a pre-release everything merged since the last release, and production keeps beta's notes. The `release notes` CI job fails a pull request whose unreleased notes would exceed Play's 500 characters or miss a translation.


## First run

The Permissions screen lists every step with live state and a button for each. In order:

1. **Allow restricted settings** — Android blocks the accessibility switch for sideloaded apps until you
   use App info → ⋮ → *Allow restricted settings*. The app cannot detect this directly; the row reports
   itself as done once the accessibility service actually turns on.
2. **The accessibility service** — the only way an app can press a button in another app. The button
   first shows what the service reads and does, and opens the accessibility settings only after *Agree*.
3. **Draw over other apps** — needed for the calibration overlay and for the floating panel.
4. **Notifications** — the ongoing session notification.
5. **Battery optimisation exemption** — for long unattended sessions.
6. **Exact alarms** (*Alarms & reminders*, off by default on Android 14+) — without it, intervals of a
   minute or more run on an inexact alarm that may fire late, and the session says so.

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
scripts/run_tests.sh caelis # CAELIS core, service, overlay, calibration, network isolation (379 tests)
scripts/run_tests.sh solar          # Sun & Moon trajectory, ephemeris, terrain horizon, skyline matching (828 tests)
scripts/run_tests.sh nightsky       # Night sky AR, Meeus celestial math, FlatBuffers catalog, star/DSO renderer (495 tests)

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
what a dead accessibility service produces. Current baseline: **1420 JVM tests (379 intervalometer/caelis, 828 solar, horizon & pose, 495 night sky, with the last two
overlapping on the shared AR frame maths, plus 31 documentation tests), 53 instrumented tests, no
failures, no skips, lint clean.**

---

## How it is put together

```
com.dazix.caelis
├── domain/          pure Kotlin, no Android imports — schedule, ramp maths, stop conditions, session state
│   ├── horizon/     pure Kotlin terrain horizon — .hgt decoder, ray-marched skyline with curvature & refraction,
│   │                which named peaks stand on it (`LandmarkVisibility`) and the `.peaks` tile codec
│   ├── solar/       pure Kotlin solar & lunar ephemeris, day times, trajectory planner, PSA/Meeus algorithms
│   └── nightsky/    pure Kotlin celestial astronomy (Meeus GMST/LST, J2000.0 RA/Dec to Az/Alt, frustum culling,
│                    star optics, planetary ephemeris, constellation tables, DSOs, meteor showers, visibility schedule, search index)
├── ar/              AR projection (R^T matrix), crop-aware FOV calculator, rotation & location providers, the camera
│   │                picture handed to the GPU (CameraFrameSource), automatic horizon alignment
│   ├── pose/        which pose draws the overlay: the phone's sensors, or optionally ARCore (consent-gated; the only
│   │                place ARCore is started), the hand-over between them and who owns the camera
│   └── scene/       the Filament sky renderer both AR screens draw with, including the camera picture
├── data/            DataStore settings (incl. the ARCore consent), calibrations, and the process-wide session snapshot
│   ├── horizon/     elevation and peak tile download (the only network code), import, mmap cache, profile codec
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
- **The picker sees launchable apps, not every installed one.** The manifest's `<queries>` asks for
  the apps with a launcher icon, the cameras among them, and ARCore; `QUERY_ALL_PACKAGES` is not
  declared. A service or a widget without a launcher icon cannot be picked as a target.
- **Android's backup leaves your positions out.** Stored horizon profiles are named after where you
  stood, so `data_extraction_rules.xml` keeps them, your saved virtual places, the offline terrain and the debug dumps out of the
  Google account backup. Settings and calibrations are still backed up. A transfer to your own new
  phone keeps the profiles.
- **The terrain horizon cost the app its "no network permission" guarantee.** That was a real
  argument in an app holding the accessibility permission, and it is now a code-level promise instead
  of a system-enforced one. The compensation is the isolation above, the one-line manifest edit that
  takes it back, and a hand-import path that is a first-class citizen rather than a fallback.
- **The optional ARCore switch weakens that promise further, in a way the source cannot repair.** With
  it on, Google Play Services for AR runs inside your session and reports to Google on its own terms.
  The compensation is that it is off by default, consent-gated on every start, started from exactly one
  file, and that a build without a Geospatial key simply never offers Geospatial. The promise that
  "nothing leaves the device" now holds for this app's own code only.
- **Peak names are OpenStreetMap data and can be wrong or missing.** A peak with no `ele` or no name is
  not in the tiles; a name or height is as good as its contributors made it. The labels are drawn only on
  the skyline the elevation model produced, so a summit the model rounds a few metres low can still be
  missing, which is the safe way round. Attribution is in Settings next to the elevation sources.
  Names for an offline region are best effort: its peak tiles go to the cache Android may reclaim, and the
  region's own profile is named later, when you are there and online. Allowing terrain downloads covers the
  peak-name host (`raw.githubusercontent.com`) as well as the elevation host.
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
- **Every user-visible string is a resource** in English (`values/`) and in every shipped language
  (`values-cs/`, `values-sk/`, `values-pl/`, `values-de/`). Countable nouns are `<plurals>`; Czech, Slovak
  and Polish need `one` / `few` / `many` / `other`, German `one` / `other`. Night-sky names are not
  strings but `res/raw-<language>/sky_names.tsv`, keyed by catalog id.
- **Adding a language** is adding files: `values-xx/` (and `app/src/debug/res/values-xx/`),
  `res/raw-xx/sky_names.tsv` and the language's plural forms in `TranslationParityTest`, which then
  names anything still missing. The store listing, `metadata/android/xx-XX/` with the images rendered
  from `design/play-store/*.html?lang=xx`, goes live on merge rather than with a release, so it follows
  in a PR of its own once a release speaks the language.

`CLAUDE.md` is the same material in the form a coding agent needs it.

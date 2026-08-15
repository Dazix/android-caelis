# Intervalometer

[![CI](https://github.com/Dazix/android-intervalometer/actions/workflows/ci.yml/badge.svg)](https://github.com/Dazix/android-intervalometer/actions/workflows/ci.yml)

An Android intervalometer that presses the shutter **inside the camera app you already use**, on a
drift-free schedule, so every frame of a time-lapse keeps that app's full image pipeline — HDR+, Night
Sight, the lot. It can also just tap a point on the screen at a fixed rate in any app at all.

It is a clone of the idea behind *Intervalometer for TimeLapse* (MobilePhoton), built to be **built and
sideloaded from source** for one reason: that kind of app needs the **accessibility permission**, which
is the most powerful permission Android hands out, and this way the source of the app holding it is on
your own disk.

Nothing leaves the device. The app has no network permission at all.

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

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a device with more than one user, install for **one** profile explicitly — `:app:installDebug`
installs for every user on the device:

```bash
adb install -r --user <id> app/build/outputs/apk/debug/app-debug.apk
```

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
# 1. everything that needs no device — also what CI runs
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug

# 2. the real engine on the real device (installs, runs, restores)
scripts/run_instrumented.sh

# 3. the proof: real camera, real photos
scripts/e2e_pixel_camera.sh
```

Read the totals from `app/build/test-results/testDebugUnitTest/*.xml` (`tests` / `failures` / `errors` /
`skipped`), **not** from the console: Gradle exits 0 when every test skipped itself, which is exactly
what a dead accessibility service produces. Current baseline: 225 JVM tests, 18 instrumented tests, no
failures, no skips, lint clean.

---

## How it is put together

```
com.dazix.intervalometer
├── domain/      pure Kotlin, no Android imports — schedule, ramp maths, stop conditions, session state
├── service/     ShutterAccessibilityService (owns the session), SessionEngine, ShutterDispatcher,
│                CameraProfile(s), RunStateStore (durable run), SessionAlarmReceiver
├── overlay/     the floating panel, its placement/cloak/dim policy, and the calibration overlay
├── notify/      the ongoing notification and its actions
├── data/        DataStore settings, calibrations, and the process-wide session snapshot
├── verify/      the optional MediaStore capture check
├── monitor/     battery and storage
└── ui/          one activity, Compose screens, Material 3 Expressive theme
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
# JVM: schedule and ramp maths, stop conditions, settings round-trips, overlay policy, UI state
./gradlew :app:testDebugUnitTest

# on a connected device: the real engine, the real service, a fake camera
scripts/run_instrumented.sh            # optional filter: scripts/run_instrumented.sh ScreenOffTest

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
- **`QUERY_ALL_PACKAGES`** is declared so the picker can list every launchable app. Nothing is sent
  anywhere; the app has no network permission.

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

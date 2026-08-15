#!/usr/bin/env bash
#
# Template. Copy it and fill in the three values for your machine:
#
#     cp scripts/env.example.sh scripts/env.local.sh
#
# scripts/env.local.sh is gitignored - it describes one machine and one phone, not the project.
# Every value here can also just be exported in the environment instead; the environment wins.

# Path to adb. Nothing Android is assumed to be on PATH, so give the full path. It is usually
# <sdk>/platform-tools/adb, and <sdk> is the same directory local.properties points sdk.dir at.
ADB=/path/to/Android/Sdk/platform-tools/adb

# The JDK Gradle runs on. JDK 21; Android Studio ships one under its own directory if you have no
# other. This is exported for ./gradlew, so it does not have to be set in your shell profile.
JAVA_HOME=/path/to/jdk-21

# The Android user id the harness may modify - NOT the phone's primary profile.
#
# These scripts enable an accessibility service, flip secure settings, grant appops and delete rows
# from MediaStore. On a phone somebody actually uses, all of that belongs in a separate user you
# created for development. Read the id off `adb shell pm list users`; it is the number in
# `UserInfo{11:Developer:...}`. That user must be in the foreground while a script runs - every
# script asserts `adb shell am get-current-user` matches this value and aborts otherwise.
ANDROID_USER_ID=

#!/usr/bin/env bash
# Installs the APK given as $1 on the running emulator, opens the app and fails if it crashed.
# Used by .github/workflows/android-launch.yml.
set -euo pipefail

APK="$1"
PKG=dev.southstar21.vectra

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.android.MainActivity"
# Long enough for startup work (graph, preferences, widgets) to run, not just the first frame.
sleep 25

# Read the crash buffer once, then search it: piping adb straight into `grep -q` lets grep exit on the
# first match, adb dies of SIGPIPE, and under pipefail the pipeline reads as "no crash". Only this
# app's crashes count; an unrelated system app crashing on the emulator must not fail the release.
CRASHES="$(adb logcat -d -b crash)"
if grep -q "Process: $PKG," <<<"$CRASHES"; then
  tail -80 <<<"$CRASHES"
  echo "::error::The release build crashed on launch."
  exit 1
fi
if ! adb shell pidof "$PKG" >/dev/null; then
  adb logcat -d | tail -150
  echo "::error::The app isn't running after launch."
  exit 1
fi
echo "The release build launched and is still running."

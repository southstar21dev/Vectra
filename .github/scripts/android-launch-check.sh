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

if adb logcat -d -b crash | grep -q "FATAL EXCEPTION"; then
  adb logcat -d -b crash | tail -80
  echo "::error::The release build crashed on launch."
  exit 1
fi
if ! adb shell pidof "$PKG" >/dev/null; then
  adb logcat -d | tail -150
  echo "::error::The app isn't running after launch."
  exit 1
fi
echo "The release build launched and is still running."

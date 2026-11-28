#!/usr/bin/env bash
# Installs the APK given as $1 on the running emulator, opens the app and fails if it crashed, then
# pokes the widget code paths and fails if that crashes too. Used by .github/workflows/android-launch.yml.
set -euo pipefail

APK="$1"
PKG=dev.southstar21.vectra

# Fails (with the evidence) when this app crashed, stopped running or logged what a missing R8 keep
# rule looks like. Read each buffer once, then search it: piping adb straight into `grep -q` lets grep
# exit on the first match, adb dies of SIGPIPE, and under pipefail the pipeline reads as "no match".
# Only this app's crashes count; an unrelated system app crashing on the emulator must not fail the release.
check_alive() {
  local stage="$1" crashes log
  crashes="$(adb logcat -d -b crash)"
  if grep -q "Process: $PKG," <<<"$crashes"; then
    tail -80 <<<"$crashes"
    echo "::error::The release build crashed ($stage)."
    exit 1
  fi
  if ! adb shell pidof "$PKG" >/dev/null; then
    adb logcat -d | tail -150
    echo "::error::The app isn't running ($stage)."
    exit 1
  fi
  # Something may catch such an error instead of crashing; it is still a stripped class or member.
  log="$(adb logcat -d | grep -E "ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|ANR in $PKG" || true)"
  if grep -q "$PKG" <<<"$log"; then
    tail -20 <<<"$log"
    echo "::error::R8-style errors in the log ($stage)."
    exit 1
  fi
}

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.android.MainActivity"
# Long enough for startup work (graph, preferences, widgets) to run, not just the first frame.
sleep 25
check_alive "on launch"

# The widget code: the refresh that the booked alarm, a clock change or a reboot triggers (reads the
# snapshot, rebuilds it from the cache, redraws). The system broadcasts a launcher sends a placed widget
# are protected and nothing is placed on the emulator's launcher, so this reaches the receiver and the
# code behind it, not a drawn widget.
adb shell am broadcast -n "$PKG/.android.widget.WidgetRefreshReceiver" -a dev.southstar21.vectra.WIDGET_REFRESH
# The refresh has 9 s (WidgetRefreshReceiver's timeout) before it gives up.
sleep 15
check_alive "after the widget broadcasts"
echo "The release build launched, took the widget broadcasts and is still running."

#!/bin/sh
# Same rule as androidApp's versionCode: 1.2.3 -> 1020399, 1.2.3-dev.4 -> 1020304,
# 1.2.3-rc.2 -> 1020352. The short version drops the prerelease suffix (iOS wants x.y.z).
# Debug builds use the newest vX.Y.Z tag instead, like the Gradle builds (native/build.gradle.kts):
# gradle.properties only holds the real version while CI builds a release.
set -e
FULL=$(sed -n 's/^vectra.versionName=//p' "$SRCROOT/../gradle.properties")
if [ "$CONFIGURATION" = "Debug" ]; then
  TAG=$(git -C "$SRCROOT" tag --list 'v*' | sed -n 's/^v\([0-9]*\.[0-9]*\.[0-9]*\)$/\1/p' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
  [ -n "$TAG" ] && FULL=$TAG
fi
NAME=${FULL%%-*}
# Past dev.49 or rc.48 two builds would share a build number: fail instead, as Gradle does.
CODE=$(echo "$FULL" | awk -F'[.-]' '{
  b = 99
  if (($4 == "dev" && $5 > 49) || ($4 == "rc" && $5 > 48)) { print "prerelease number too high for the build number scheme: " $0 > "/dev/stderr"; exit 1 }
  if ($4 == "dev") b = $5
  if ($4 == "rc") b = 50 + $5
  print ($1 * 10000 + $2 * 100 + $3) * 100 + b
}')
mkdir -p "$DERIVED_FILE_DIR"
printf '#define VECTRA_VERSION_NAME %s\n#define VECTRA_VERSION_CODE %s\n' "$NAME" "$CODE" > "$DERIVED_FILE_DIR/VectraVersion.h"

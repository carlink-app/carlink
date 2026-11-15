#!/usr/bin/env bash
# Build spike-server for this machine, the phone (arm64) and the emulator
# (x86_64), plus netsim; write web/config.json with WebTransport cert hashes
# for the next year; assemble dist/ for hosting the page on a public HTTPS site.
set -euo pipefail
cd "$(dirname "$0")"

# Per-checkout WebTransport cert key. Not secret-grade: spike only.
[ -f wt-seed ] || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > wt-seed
LDFLAGS="-s -w -X main.seedHex=$(cat wt-seed)"

go run -ldflags "$LDFLAGS" . hashes > web/config.json
[ -f clip.h264 ] && [ -f clip.hevc ] || ./gen-clip.sh

mkdir -p bin
go build -ldflags "$LDFLAGS" -o bin/spike-server .
go build -o bin/netsim ./netsim
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -ldflags "$LDFLAGS" -o bin/spike-server-android-arm64 .
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -ldflags "$LDFLAGS" -o bin/spike-server-android-x86_64 .  # android/amd64 needs cgo; a static linux binary runs fine under adb shell
cp clip.h264 clip.hevc bin/

# Static-IP hotspot tool (dex for app_process), needs the Android SDK.
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
D8=$(ls -d "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1)
if [ -n "$JAR" ] && [ -n "$D8" ]; then
  T=$(mktemp -d)
  javac --release 17 -cp "$JAR" -d "$T" hotspot/CarHotspot.java
  "$D8" --min-api 30 --lib "$JAR" --output "$T" "$T"/*.class
  cp "$T/classes.dex" bin/carhotspot.dex
  rm -rf "$T"
else
  echo "Android SDK not found: skipping carhotspot.dex"
fi

rm -rf dist && mkdir dist && cp web/* clip.h264 clip.hevc dist/
ls -lh bin

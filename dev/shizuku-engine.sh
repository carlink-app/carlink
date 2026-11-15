#!/usr/bin/env bash
# Start the engine the production way (carlink app -> Shizuku user service) on
# a connected device/emulator: opens the app, taps "Start engine", waits until
# the WebTransport server listens. Shizuku must be running and allowed.
# Emulator: `adb emu redir add udp:24433:4433` and the dev_allow_any switch
# (adb shell run-as app.carlink touch /data/data/app.carlink/files/dev_allow_any).
set -euo pipefail
adb logcat -c
adb shell am start -n app.carlink/.ui.MainActivity >/dev/null
for _ in $(seq 20); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  b=$(adb shell cat /sdcard/ui.xml | grep -oE 'text="Start engine"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
  [ -n "$b" ] && break
  sleep 1
done
[ -n "$b" ] || { echo "Start engine button not found"; exit 1; }
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
# The app's status line: "running on :4433", "already running" (auto-restarted
# by the app), "waiting for car · cert abc123", or an error.
for _ in $(seq 30); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  st=$(adb shell cat /sdcard/ui.xml | grep -oE 'text="Status: [^"]*"' | sed 's/text="Status: //; s/"$//' || true)
  case "$st" in
    *running*|*"waiting for car"*|*"car connected"*) echo "engine: $st"; exit 0 ;;
    *error*|*denied*) echo "engine: $st"; exit 1 ;;
  esac
  sleep 1
done
echo "engine did not start (Shizuku running and allowed?)"; exit 1

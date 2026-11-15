#!/usr/bin/env bash
# Local test rig: phone side in the Android emulator (or on this machine),
# hotspot Wi-Fi simulated by netsim, Tesla-like headless Chromium running the
# benchmark page automatically. Saves the report to rig-reports/ and prints a
# summary.
#
#   ./rig.sh [-p profile] [-s] [-t emulator|host] [-f] [-k]
#     -p  netsim profile: none | wifi5 | wifi24 | wifi24-busy   (default wifi24)
#     -s  emulate Tesla Wi-Fi power-save (beacon wake-up delay)
#     -t  where the server runs: emulator (default) or host
#     -f  full-length tests (default: quick, half duration)
#     -k  keep Chromium visible (not headless) for watching
#
# Tesla browser approximation: Chromium at 1920x1200, 4 CPU cores (Ryzen
# V1000-class), Tesla user agent. Not emulated: its GPU/hardware decoder and
# the private-IP block (the rig uses 127.0.0.2).
set -euo pipefail
cd "$(dirname "$0")"

PROFILE=wifi24; PSM=0; TARGET=emulator; QUICK=1; HEADLESS=1
while getopts "p:st:fk" o; do
  case $o in
    p) PROFILE=$OPTARG ;; s) PSM=1 ;; t) TARGET=$OPTARG ;; f) QUICK=0 ;; k) HEADLESS=0 ;;
    *) sed -n 2,16p "$0"; exit 1 ;;
  esac
done

[ -x bin/netsim ] || ./build.sh
SIM_IP=127.0.0.2
WORK=$(mktemp -d)
PIDS=()
cleanup() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  for p in "${PIDS[@]}"; do wait "$p" 2>/dev/null || true; done
  # keep the logs next to the report
  [ -n "${OUT:-}" ] && mkdir -p "${OUT%.json}.logs" && cp "$WORK"/*.log "${OUT%.json}.logs/" 2>/dev/null || true
  [ "$TARGET" = emulator ] && [ -n "${OUT:-}" ] && adb pull -q /data/local/tmp/carlink/server.log "${OUT%.json}.logs/" >/dev/null 2>&1 || true
  if [ "$TARGET" = emulator ]; then
    adb shell "pkill -f spike-server-[a]ndroid" >/dev/null 2>&1 || true
    adb emu redir del tcp:28080 >/dev/null 2>&1 || true
    adb emu redir del udp:24433 >/dev/null 2>&1 || true
    adb emu redir del udp:24434 >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# ---- phone side ----
if [ "$TARGET" = emulator ]; then
  adb get-state >/dev/null 2>&1 || { echo "no emulator/device (start one: emulator -avd carlink -no-window)"; exit 1; }
  D=/data/local/tmp/carlink
  adb shell "pkill -f spike-server-[a]ndroid" >/dev/null 2>&1 || true
  adb shell "mkdir -p $D && rm -rf $D/reports"
  adb push -q bin/spike-server-android-x86_64 bin/clip.h264 bin/clip.hevc $D/ >/dev/null
  adb shell "chmod 755 $D/spike-server-android-x86_64"
  adb shell "cd $D && nohup ./spike-server-android-x86_64 > server.log 2>&1 &" &
  PIDS+=($!)
  # emulator redirect, not adb forward: adb tunnels TCP through its own protocol and adds ~20 ms
  adb emu redir add tcp:28080:8080 >/dev/null
  adb emu redir add udp:24433:4433 >/dev/null
  adb emu redir add udp:24434:4434 >/dev/null
  UP_HTTP=127.0.0.1:28080; UP_WT=127.0.0.1:24433; UP_WT2=127.0.0.1:24434
else
  rm -rf bin/reports
  bin/spike-server -http 127.0.0.1:8080 -wt-bind 127.0.0.1 > "$WORK/server.log" 2>&1 &
  PIDS+=($!)
  UP_HTTP=127.0.0.1:8080; UP_WT=127.0.0.1:4433; UP_WT2=127.0.0.1:4434
fi

# ---- simulated Wi-Fi ----
PSM_FLAG=(); [ "$PSM" = 1 ] && PSM_FLAG=(-psm)
bin/netsim -profile "$PROFILE" "${PSM_FLAG[@]}" -listen $SIM_IP \
  -tcp 8080=$UP_HTTP -udp 4433=$UP_WT -udp 4434=$UP_WT2 > "$WORK/netsim.log" 2>&1 &
PIDS+=($!)

for _ in $(seq 1 30); do
  curl -sf "http://$SIM_IP:8080/info" >/dev/null && break
  sleep 1
done
curl -sf "http://$SIM_IP:8080/info" >/dev/null || { echo "server not reachable through netsim"; cat "$WORK"/*.log; exit 1; }

# ---- Tesla-like browser ----
CHROME=$(command -v chromium-browser || command -v chromium || command -v google-chrome)
UA="Mozilla/5.0 (X11; GNU/Linux) AppleWebKit/537.36 (KHTML, like Gecko) Chromium/136.0.0.0 Chrome/136.0.0.0 Safari/537.36 Tesla/2025.32"
URL="http://$SIM_IP:8080/?autorun=1&quick=$QUICK&host=$SIM_IP"
HL=(); [ "$HEADLESS" = 1 ] && HL=(--headless=new)
taskset -c 0-3 "$CHROME" "${HL[@]}" --user-data-dir="$WORK/chrome" --no-first-run --no-default-browser-check \
  --window-size=1920,1200 --user-agent="$UA" --autoplay-policy=no-user-gesture-required \
  --enable-logging=stderr --v=0 "$URL" > "$WORK/chrome.log" 2>&1 &
PIDS+=($!)
echo "rig: profile=$PROFILE psm=$PSM target=$TARGET quick=$QUICK, running benchmark..."

# ---- wait for the report ----
mkdir -p rig-reports
OUT="rig-reports/$(date +%Y%m%d-%H%M%S)-$TARGET-$PROFILE$( [ "$PSM" = 1 ] && echo -psm || true).json"
for _ in $(seq 1 600); do
  sleep 2
  if [ "$TARGET" = emulator ]; then
    f=$(adb shell "ls /data/local/tmp/carlink/reports/ 2>/dev/null" | tr -d '\r' | head -1 || true)
    [ -n "$f" ] && { sleep 1; adb pull -q "/data/local/tmp/carlink/reports/$f" "$OUT" >/dev/null; break; }
  else
    f=$(ls bin/reports/ 2>/dev/null | head -1 || true)
    [ -n "$f" ] && { sleep 1; cp "bin/reports/$f" "$OUT"; break; }
  fi
done
[ -f "$OUT" ] || { echo "no report after 20 min"; grep -iE "error|fail" "$WORK/chrome.log" | tail -20; exit 1; }
grep "packets" "$WORK/netsim.log" | tail -1 || true
python3 -I summarize.py "$OUT"

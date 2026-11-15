#!/usr/bin/env bash
# Install the app on the emulator (or a USB phone) and run the engine via
# adb app_process (no Shizuku needed). UDP 4433 in the emulator is reachable
# from this machine at 127.0.0.1:24433.
#   dev/emu-engine.sh [CODE]
set -euo pipefail
cd "$(dirname "$0")/.."
CODE=${1:-TEST-CODE-0001}
export JAVA_HOME=${JAVA_HOME:-$(ls -d /usr/lib/jvm/*temurin-17* | head -1)}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$(ls -d ~/Android/Sdk/ndk/* | sort -V | tail -1)}

(cd core && cargo ndk -t x86_64 -t arm64-v8a --platform 30 -o ../android/app/src/main/jniLibs build --release --locked -q)
(cd android && ./gradlew assembleDebug -q)
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk >/dev/null
APK=$(adb shell pm path app.carlink | tr -d '\r' | sed 's/^package://')
LIB=$(adb shell dumpsys package app.carlink | tr -d '\r' | sed -n 's/^ *legacyNativeLibraryDir=//p' | head -1)
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
case $ABI in x86_64) LIBDIR=$LIB/x86_64 ;; arm64-v8a) LIBDIR=$LIB/arm64 ;; *) LIBDIR=$LIB/$ABI ;; esac
adb shell "pkill -f app.carlink.engine.EngineMai[n]" || true
if adb get-serialno | grep -q emulator; then
  adb emu redir del udp:24433 >/dev/null 2>&1 || true
  adb emu redir add udp:24433:4433 >/dev/null
fi
echo "engine: code=$CODE apk=$APK lib=$LIBDIR/libcarlink_core.so"
exec adb shell CLASSPATH="$APK" app_process / app.carlink.engine.EngineMain --code "$CODE" --port 4433 --allow any --lib "$LIBDIR/libcarlink_core.so"

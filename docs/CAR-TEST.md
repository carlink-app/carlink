# First car test: checklist

Phone: Pixel 9 Pro (Android 16). Car: Model 3 2023 (Ryzen MCU3).
The phone's hotspot carries video to the car browser. All sound goes over the
car's Bluetooth, as usual.

## Before the day (at home, with a laptop)

1. **Car page online.** Push to github.com/tslink-app/tslink (public) and set Settings -> Pages ->
   Source: GitHub Actions. Do this a day early: the deploy runs `npm audit` and
   a new advisory could block it. Then check that
   `https://tslink.app/?debug=1` loads on a desktop.
2. **Install TSLink** (debug build, both ABIs):
   `cd core && cargo ndk -t arm64-v8a -t x86_64 --platform 30 -o ../android/app/src/main/jniLibs build --release --locked`,
   then `cd android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Hotspot settings:**
   - Security: WPA2-Personal (the Tesla may not see WPA2/WPA3 or WPA3)
   - "Turn off hotspot automatically": off
   - Note the band, SSID and password
   - Make other devices (watch, laptop, tablet) forget this hotspot, because
     the car hotspot gives out exactly one address, 9.9.0.2
4. **Shizuku:** install from Play, start it via Wireless debugging (Shizuku shows how).
5. **TSLink:**
   - Start engine, then allow Shizuku. Status shows `running on :4433`, later
     `waiting for car · cert abc123`.
   - Setup & checks (Diagnostics) should show every required step ready.
   - Turn car notifications on (optional; this also gives the car real app icons).
   - Choose car: only if you want Bluetooth auto-start (untested so far).
   - Battery: Unrestricted (Settings -> Apps -> TSLink -> Battery).
6. **Shizuku without Wi-Fi.** Tap "Car hotspot on" (status `ok`), then turn Wi-Fi
   off or walk out of range for 2 minutes. Shizuku must still say running, and
   "Start engine" must answer "already running". If Shizuku died, fix that first:
   in the car you can't restart it without Wi-Fi.
7. Optional: open the car page once in the car on home Wi-Fi. This caches the
   page; it won't connect yet.

## In the car

8. Phone plugged in. In TSLink: Start engine, then "Car hotspot on" (status `ok`).
   Don't use the system hotspot toggle: that one uses a private subnet the car
   browser refuses.
9. Tesla Wi-Fi: join the phone's hotspot (not home Wi-Fi). The car's IP should be 9.9.0.2.
10. Tesla browser: `https://tslink.app/?debug=1`. The page shows a QR code.
    In TSLink tap "Pair a car (scan QR)" and scan it; the page says "Paired"
    and connects within a few seconds. Backup: "Type the code instead" and
    enter the code from the phone (the address 9.9.0.1:4433 is preset).
11. Expect "Connected to Pixel 9 Pro" and the app grid. In the overlay, the
    `cert ... fp` should match the `cert` in the phone's status.
12. Try each of these and note the overlay numbers (codec, fps, decode, rtt, rate):
    - Maps / Waze / ABRP: tap, drag to pan, Back
    - Now playing: start music on the phone; the rail shows it; play/pause,
      next, previous from the card
    - Long-press an app: Pin to rail; search in the launcher
    - the browser's expand button (bigger area; the UI size adapts)
    - leaving the screen untouched for 5+ min (the picture must stay live)
    - locking the phone for 2 min
    - a search box (the keyboard should open in the car)
    - a notification (send yourself a message)
    - minimising the browser and coming back (apps stay up to 30 s)
    - `?codec=h264` instead of the default, to compare
13. Afterwards: "Normal hotspot", then "Stop engine".

## If something fails

If the car picture freezes when nobody touches the screen: check the engine
log for "car display may sleep when idle". Workaround: Developer options ->
Stay awake (keeps the phone screen on while charging).

Capture:
- A photo of the car screen with the debug overlay (its `-- env` and `-- log`
  sections are there for this) and the time.
- In TSLink: the status line, then "Engine log", then Share.
- Shizuku's state; Hotspot -> connected devices.
- If needed: Developer options -> Take bug report.

What the car's message means:

| Car shows | Likely cause |
|---|---|
| This browser has no WebTransport | The Tesla browser can't run TSLink as is; note the user agent from the overlay |
| Timed out (phone unreachable?) | Car on another Wi-Fi, hotspot not in car mode (9.9.0.x), or engine not running |
| Opening handshake failed | Wrong code (compare the cert fingerprints), engine not running, or car IP not 9.9.0.x |
| Connected but black, errors rising | HEVC trouble: the page switches to H.264 by itself; or reload with `?codec=h264` |
| Picture frozen | Note what happened just before (lock, idle, network); share the engine log |

## Known limits

- Tapping a notification opens its app on the car. Android 16 doesn't let
  TSLink jump to the exact conversation.
- Apps with secure screens (banking) show black.
- Settings, Shizuku, Play Store, installers and file managers are not offered
  in the car: the car display works while the phone is locked.
- Bluetooth auto-start has not been tested on hardware yet.

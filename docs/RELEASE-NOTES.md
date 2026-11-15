carlink streams apps from your Android phone to the Tesla browser over the
phone's hotspot, with touch, notifications and an app launcher. Sound stays on
the car's Bluetooth.

**Status: pre-release.** Tested in a 2023 Model 3 with a Pixel 9 Pro XL
(Android 17).

## Files

| File | What it is |
|---|---|
| `carlink-car-page-<version>.zip` | The car page, only if you want to host it yourself |
| `SHA256SUMS.txt` | Checksums |

There is no APK: build the phone app from source and install it over USB
(see the README, "Build from source"). The car page is served at
**https://carlink-app.github.io/carlink/**.

## Setup

1. **Shizuku** (gives carlink the rights it needs, no root):
   [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
   Start it via Wireless debugging or over USB (Shizuku shows how).
2. **Build and install carlink** over USB with `adb install` (Play Protect
   blocks installing it from a browser or file manager).
3. **Open carlink**, tap Start engine, allow Shizuku and notifications, note
   the pairing code. Optional: car notifications, Bluetooth auto-start.
4. **Hotspot:** WPA2-Personal, "Turn off hotspot automatically" off. In
   carlink, Car hotspot on.
5. **In the car:** join the phone's hotspot, open
   https://carlink-app.github.io/carlink/, enter the pairing code (address
   9.9.0.1, port 4433 are filled in), Save & connect.

Checklist and troubleshooting:
[docs/CAR-TEST.md](https://github.com/carlink-app/carlink/blob/main/docs/CAR-TEST.md).

## Known limits

- Tapping a notification opens its app in the car, not always the exact screen (Android restriction).
- Settings, Shizuku, Play Store, installers, file managers and camera apps are
  not offered in the car (the car screen works while the phone is locked).
- Apps with secure screens (banking) show black.
- On phones where the car screen shares the phone's power state (Pixel,
  Android 17), the phone stays awake, locked and dark while the car is
  connected.
- Shizuku must be restarted after a phone reboot (Wi-Fi or USB).

License: PolyForm Noncommercial 1.0.0 (personal and other noncommercial use).

First test build. carlink streams apps from your Android phone to the Tesla
browser over the phone's hotspot, with touch, notifications and an app
launcher. Sound stays on the car's Bluetooth.

**Status: pre-release.** Tested on an Android 16 emulator and in desktop
Chrome (including simulated 2.4/5 GHz Wi-Fi). Not yet tested in a car or on a
real phone.

## Files

| File | What it is |
|---|---|
| `carlink-<version>.apk` | The phone app (Android 11+; arm64 phones, x86_64 emulators) |
| `carlink-car-page-<version>.zip` | The car page, only if you want to host it yourself |
| `SHA256SUMS.txt` | Checksums of the files above |

The car page is served at **https://carlink-app.github.io/carlink/**.
Most people only need the APK.

## Setup

1. **Shizuku** (gives carlink the rights it needs, no root):
   [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
   Start it via Wireless debugging (Shizuku shows how). This needs Wi-Fi.
2. **Install the APK.** Allow installing from your browser or file manager
   when asked. This build is debug-signed: to update to a later release,
   uninstall the previous one first.
3. **Open carlink**, tap Start engine, allow Shizuku, note the pairing code.
   Optional: turn car notifications on (also gives the car real app icons);
   choose your car for Bluetooth auto-start.
4. **Hotspot:** set it to WPA2-Personal and turn off "Turn off hotspot
   automatically". In carlink, tap **Car hotspot on**.
5. **In the car:** join the phone's hotspot, open
   https://carlink-app.github.io/carlink/ in the browser, enter the pairing
   code (address 9.9.0.1 and port 4433 are filled in), and Save & connect.

Full first-test checklist, including what to capture if something fails:
[docs/CAR-TEST.md](https://github.com/carlink-app/carlink/blob/main/docs/CAR-TEST.md).

## Known limits

- Tapping a notification opens its app in the car, not the exact conversation (Android 16 restriction).
- Settings, Shizuku, Play Store, installers and file managers are not offered
  in the car, because the car screen works while the phone is locked.
- Apps with secure screens (banking) show black.
- Bluetooth auto-start is untested on hardware.
- Shizuku can only be started on Wi-Fi: if it stopped, restart it at home.

# TSLink

Use your Android phone's apps on the Tesla screen: Waze, Google Maps, ABRP,
messages, with touch, notifications and an app launcher, like Android Auto
for a car that doesn't have it. Video goes to the car's browser over the
phone's hotspot. Sound stays where it is: on the car's Bluetooth.

**Status: pre-release.** Tested in a 2023 Model 3 (AMD Ryzen) with a Pixel
9 Pro XL (Android 17), plus an Android 16 emulator and desktop Chrome with
simulated Wi-Fi. Any Android 11+ phone and a Tesla with the Chromium browser
should work.

- **Get the app:** build it from source (below); there are no APK downloads.
- **Car page:** https://tslink.app/
- **Setup and first-test checklist:** [docs/CAR-TEST.md](docs/CAR-TEST.md)

## How it works

```
Phone (Android)                                         Tesla browser
  TSLink app ── Shizuku ──> engine (shell rights)
                               virtual display  ──┐
                               H.264/HEVC encoder  ├──>  WebTransport (QUIC)  ──>  car page
                               touch injection  <─┘     over the phone hotspot      WebCodecs decode, touch
```

- **Engine** (Kotlin + Rust, `android/`, `core/`): runs with shell rights via
  [Shizuku](https://shizuku.rikka.app/), no root. Apps run on their own virtual
  display sized for the car, not mirrored from the phone screen, so the
  phone stays usable and can be locked. A hardware encoder streams it; touch
  from the car is injected back.
- **Network** (`core/`): a WebTransport server in Rust (quinn). One QUIC
  stream per frame, rate control that reacts to queueing rather than Wi-Fi's
  random loss, drop-to-keyframe on backlog. The car authenticates with a
  pairing code (HMAC challenge-response); the TLS certificate is derived from
  the code, so no server or internet is needed.
- **Hotspot**: the Tesla browser refuses private IPs, so TSLink restarts the
  phone's hotspot on a fixed public-looking subnet: phone 9.9.0.1, car 9.9.0.2.
- **Car page** (TypeScript, `web/`): hosted on GitHub Pages, cached offline by
  a service worker. Decodes in a worker, draws the newest frame, sends touch,
  shows notification banners and an app launcher.
- **Phone app extras**: notifications as banners in the car (following your
  lock-screen privacy settings), real app icons, Bluetooth auto-start when the
  car connects.

Protocol and certificate details: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Setup (short)

1. Install **Shizuku** from Play and start it (Wireless debugging, on Wi-Fi).
2. Build TSLink and install it over USB (see Build from source). Google Play
   Protect blocks installing it from a file manager or browser (it uses
   notification access), so install with `adb`.
3. In TSLink: follow **Setup** (three short pages), then tap **Turn on car hotspot**.
4. In the car: join the phone's hotspot and open https://tslink.app/. In
   TSLink tap **Pair a car (scan QR)** and scan the code the car shows (or
   type the pairing code on the car page instead).

The full checklist with hotspot settings and troubleshooting is in
[docs/CAR-TEST.md](docs/CAR-TEST.md).

## Build from source

Needs JDK 17 (Gradle doesn't run on newer JDKs yet: set `JAVA_HOME` to a
JDK 17 if your system default is newer), Android SDK with NDK 27.1, Rust with
[cargo-ndk](https://github.com/bbqsrc/cargo-ndk), Node 22, and `adb`.

```sh
# Rust core -> native libraries for the app
cd core
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -t x86_64 --platform 30 -o ../android/app/src/main/jniLibs build --release --locked

# Android app
cd ../android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk   # phone with USB debugging

# Car page
cd ../web
npm ci && npm run build          # dist/
```

Tests: `cargo test` (core), `./gradlew testDebugUnitTest` (app),
`npm test` (web). End-to-end test against a running engine:
`node dev/e2e.mjs --code <pairing code>` (see the file header).

Development helpers in `dev/`: run the engine on an emulator over adb
(`emu-engine.sh`) or through Shizuku (`shizuku-engine.sh`). `spike/` holds the
original benchmark page and a Wi-Fi link simulator (`netsim`) used for
testing.

## Releases

Every push runs the tests and lint (`.github/workflows/ci.yml`). The car page
deploys to GitHub Pages on every push to `main` that changes it, after its own
tests. A version tag (`git tag v0.2.0 && git push origin v0.2.0`) publishes a
release with the car page as a zip (for self-hosting) and checksums. There is
no APK in releases: build the app yourself.

## Privacy and safety

- No servers, accounts or analytics. The phone and the car talk directly over
  the hotspot; the car page is a static file.
- Only the car's hotspot subnet can connect, and only with the pairing code.
  "New pairing code" in the app revokes every car that knew the old one.
- The car screen works while the phone is locked, so system settings, Shizuku,
  app stores, installers and file managers are never offered in the car.
- Notifications in the car follow your lock-screen settings.
- The on-screen keyboard appears on the car screen, also while the phone is
  locked; its suggestions and clipboard chip can show there. Apps can't open
  Settings, Play Store, installers or the keyboard's settings in the car:
  TSLink closes them.
- Camera apps aren't offered in the car and are closed if another app opens
  them there (apps with a built-in camera, like messengers, still have it).
- On phones where the car screen shares the phone's power state (seen on a
  Pixel with Android 17), TSLink keeps the phone awake and locked with its own
  screen switched off while the car is connected. Press power to use the
  phone; it goes dark again after the screen timeout.
- Don't operate the screen while driving beyond what you would do with the
  car's own UI.

## Docs

- [docs/CAR-TEST.md](docs/CAR-TEST.md): first car test, step by step
- [docs/PROTOCOL.md](docs/PROTOCOL.md): wire protocol, certificate, limits
- [docs/qa/](docs/qa/): what was tested per milestone and what was fixed
- [spike/docs/RESULTS.md](spike/docs/RESULTS.md): early benchmarks and design findings

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): you may use, build and modify
TSLink for personal and other noncommercial purposes; commercial use is not
allowed. Parts adapted from [scrcpy](https://github.com/Genymobile/scrcpy)
stay under the Apache License 2.0; see [NOTICE](NOTICE).

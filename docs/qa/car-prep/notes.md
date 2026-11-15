# Car-test prep: reviews, automated tests, manual QA (2026-11-15)

## Reviews

- Car-readiness review (whole system, first real run).
- Whole-system security review (real networks, car park, passengers).
- Test-day checklist: docs/CAR-TEST.md.

## Automated end-to-end and soak tests (dev/e2e.mjs)

Headless Chromium at the Tesla's expanded browser size (1840x880) drives the
real page against the real engine (Shizuku). Each run connects, opens Maps,
pans continuously, sends an SMS and checks the banner, and samples the debug
overlay and engine/page memory.

| Run | Result |
|---|---|
| Direct, 5 GHz, 2.4 GHz, 2.4 GHz + power save (60 s each) | Pass: 53-60 fps, 0 drops |
| Busy 2.4 GHz (90 s), before fix | Median 0 fps: 5-15 s freezes |
| Busy 2.4 GHz, after fix | Pass: 41-60 fps, no frozen samples |
| Soak: 10 min, 2.4 GHz + power save | Pass: 60 fps, 0 drops, no reconnects, engine memory flat (68.1 -> 67.8 MB) |
| Screen off during streaming | Pass |
| Idle 55 s with a 15 s screen timeout | Car display stays awake |

## Fixed

| Issue | Fix |
|---|---|
| Freezes on a busy link: while waiting for a keyframe, the phone never asked again, so the picture waited for the encoder's 10 s interval | Re-request every 250 ms while waiting (core/src/server.rs) |
| Car screen bypassed the phone lock: Settings, Shizuku etc. reachable | Hidden from the car and refused by the engine (Apps.HIDDEN) |
| Engine completed TLS (with a code-derived cert) for anyone reaching UDP 4433; unbounded pre-auth connections | Address and backoff checked on the first packet (ignored otherwise), 5 s handshake limits, max 4 unauthenticated |
| Car display dozed when not touched (frozen picture while driving) | Screen wake lock on the car display only |
| Keyboard opened on the phone | IME policy: local to the car display |
| Engine died -> nothing restarted it | App remembers "wanted", restarts it on relink (tested: ~2 s) |
| Short disconnects moved apps to the phone screen | Panes kept 30 s; small re-measure differences (2 px, 1 dpi) no longer recreate the display |
| Old notifications popped up after a restart (re-sync) | Re-synced notifications tracked quietly, not shown as new |
| No way to diagnose in the car | Debug overlay: env (UA, WebTransport, codecs, SW, UTC, cert week, fingerprint) and last log lines; phone: status with cert fingerprint, Engine log screen with Share |
| HEVC failure = black screen | 3 decode errors in 10 s -> switch to H.264 |
| Pairing code shown in plain text on the car's settings | Masked (last 4) |
| Stale native library in the APK | Rebuilt for both ABIs, `--locked` |
| Caller checks, library path, dev switch | Every engine method checks the caller; engine loads its own library; dev switch only in debug builds |
| Supply chain | Dependabot for Gradle, Gradle wrapper checksum |

## Manual QA (GUI emulator + Chrome)

- After an emulator restart the engine came back by itself once Shizuku started.
- Launcher without Settings, Play Store, Shizuku, Files.
- Phone screen off: Waze opens and streams on the car (41 fps).
- Masked pairing code on the settings screen.
- Reconnect within 30 s keeps the same display and app; video back at once.
- Keyboard appears on the car display.

## Open

- Hosting decision (shared `<user>.github.io` origin vs. a dedicated one), first commit/push, deploy.
- Bluetooth auto-start and real lock-screen behaviour: Pixel test.
- WebTransport in the Tesla browser: unproven until the car test (the overlay shows it).
- Security leftovers (low): phone notification when a car session replaces another; narrower CSP; fragment overrides only in dev builds.

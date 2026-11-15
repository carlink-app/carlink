# First car test (2026-11-15)

Setup: Pixel 9 Pro XL (Android 16), local debug build installed over adb (Play
Protect blocks sideloading from the file manager because of the notification
listener), Shizuku started over USB. 2023 Model 3 (AMD Ryzen), browser on
https://carlink-app.github.io/carlink/ over the phone's car hotspot (9.9.0.x).
The user filmed the screen (3 clips, ~3 minutes); frames below.

## Result: works

| # | Check | Result |
|---|-------|--------|
| 1 | Bluetooth auto-start: car connects, engine + car hotspot come up | Pass (confirmed by the user) |
| 2 | WebTransport in the Tesla browser | Pass (Chrome 148, X11 UA) |
| 3 | HEVC decode | Pass: `hvc1.1.6.L120`, decode p50 ~1 ms, p95 ~2 ms, 0 errors |
| 4 | Link quality | RTT p50 4-5 ms, p95 ~10 ms; rate at the 9 Mbit/s HEVC cap |
| 5 | Frame rate | ~60 fps on motion (map pan, video), 0-10 on still screens (by design) |
| 6 | Google Maps: browse, search | Pass |
| 7 | Waze: route, turn-by-turn, arrival card | Pass |
| 8 | ABRP, Telegram (incl. video), Spotify, YouTube Music, YouTube | Pass |
| 9 | Notification banner (Telegram) | Pass |
| 10 | Launcher groups (Navigation, Music, Social, Work, Other) | Pass (`01`) |
| 11 | Rail: Close all (two taps), per-app close | Pass (`02`) |
| 12 | Debug overlay | Pass (`03`) |

Engine log: not captured. Logcat on the Pixel kept only about the last hour,
and the phone was plugged in ~2 h later. No crash entries; the engine and
Shizuku processes from before the test were still alive afterwards.

## Measurements

- Browser window (normal, not expanded): 773x601 CSS px at DPR 1.53, so the
  pane is 1036x918 device px (the rail takes 96 CSS px).
- Debug overlay, read frame by frame from the third clip (~2 min, 19:41-19:43):
  - Start: "waiting for keyframe" for ~2 s, then picture. Rate climbed
    5.4 -> 7.2 -> 8.3 -> 9.0 Mbit/s in ~8 s and stayed at the HEVC max
    (9 Mbit/s) for the rest: the cap, not the network, limits quality.
  - RTT p50 4-6 ms throughout; p95 ~8-11 ms on light screens, 21-31 ms while
    ABRP's map loaded (bigger frames).
  - Decode p50 0.9-1.0 ms, p95 1.6-2.5 ms, decoder queue 0, errors 0.
  - Drops: encoded (phone-side backpressure) 0 the whole time; decoded but
    superseded before drawing 0 -> 61 over ~2 min (of several thousand
    frames). Harmless.
  - fps: 50-60 on map pans, 21-49 on YouTube, 0 on still screens and the
    page's own launcher.
  - Overlay log: a single line (`pane 0: decoder hvc1.1.6.L120`): no
    reconnects, no codec fallback, no errors.
  - Per-app close: the armed x is visible on the ABRP rail icon at 19:43.

## Findings

| Finding | Status |
|---|---|
| Taxi apps (Uber, Bolt) land in Navigation: they declare `appCategory="maps"` | Fixed: taxi/delivery apps listed as Other |
| UI cramped (user): the dpi floor was scaled by the browser zoom (1.53), so 306 dpi on a 918 px pane = 480 dp, phone layouts | Fixed: floor in device px, now 230 dpi = 640 dp (tablet layouts, ~33% more room) |
| Bitrate pinned at the HEVC cap (9 Mbit/s) all session | Fixed: caps raised to HEVC 16 / H.264 20 Mbit/s |
| Browser not full screen | Full screen button in Display settings (shown only if the browser supports it; untested on the Tesla) |
| Launcher scrolling looks smeared in the video | Not a bug: smooth in person (camera blur at night) |
| Logs lost if not saved soon after the test | Use Engine log -> Share in the app right after a test |

## Next

- Next car test: the new dpi and bitrate caps; the Full screen button; the
  browser's expanded view.
- Release signing key (Play Protect: the debug-signed APK can only be
  installed over adb).

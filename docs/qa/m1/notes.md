# Milestone 1 manual QA — 2026-11-15

Setup: GUI emulator (AVD `carlink`, Pixel 9 Pro image, Android 36, window
visible), engine via `dev/emu-engine.sh` (app_process, `--allow any`),
production web build via `vite preview`, user's real Chrome through the Claude
extension (window 1920x905 CSS px, devicePixelRatio 2), `?debug=1` overlay.

Recording: `carlink-m1-qa.mp4` (Chrome GIF capture, 41 frames, converted to MP4) plus numbered screenshots.
The emulator's own screen was not recorded: the car UI lives on a hidden
virtual display (like Android Auto), the phone's screen stays untouched
(`12-phone-main-screen.png`), and `screenrecord` refuses virtual displays
("Invalid physical display ID").

## Checklist

| # | Check | Result |
|---|-------|--------|
| 1 | Wrong pairing code | Pass: "Opening handshake failed (wrong pairing code, ...)", retries with backoff (`01`) |
| 2 | Pairing screen without saved settings | Pass (`03`) |
| 3 | Correct code connects, launcher lists apps | Pass after fix A (`01b` shows the bug) |
| 4 | Launch Settings, video appears | Pass after fix B (`02` shows the bug, `04`) |
| 5 | Drag scroll | Pass (`05`) |
| 6 | Tap navigates | Pass: Display & touch opened (`06`) |
| 7 | Rail back | Pass |
| 8 | Launch Google Maps (sign-in skipped, not signed in) | Pass (`07`) |
| 9 | Map pan streaming | Pass: 39 fps, decode p50 0.7 ms / p95 3.0 ms, 0 drops (`08`) |
| 10 | Switch apps from rail recents | Pass |
| 11 | Engine restart: page reconnects by itself | Pass (`09`) |
| 12 | After restart the app comes back | Pass after fix C (`10`, `11` before; `13` after) |
| 13 | Web app update (new deploy) while page is cached | Pass after fix D |

Latency on this rig (loopback, no Wi-Fi): RTT p50 0.2–0.3 ms. Real numbers
need the car.

## Bugs found and fixed

A. **Editing the URL fragment didn't apply** (dev overrides `#code=...`). The
   page doesn't reload on a hash-only change, so it kept the old code. Fix:
   reload on `hashchange` (web/src/main.ts).

B. **No video on high-DPR screens.** The page requests CSS size x DPR
   (3648x1810 here); the emulator's encoder rejects that size
   (`IllegalArgumentException` in `MediaCodec.configure`). Fix: `Pane.fit`
   picks the largest size with the same aspect ratio that the encoder
   supports (5% steps, aligned), and the `pane` message reports the actual
   size (2006x994 here). Unit tests in `PaneFitTest`.

C. **Stale frame after a phone engine restart.** The new engine's virtual
   display was empty; the page kept showing the last frame, "waiting for
   keyframe", until the user picked an app. Fix: on each new session the page
   re-sends `launch` for the app it last showed.

D. **Blank page after a new deploy (serious; would hit every GitHub Pages
   update).** The service worker matched cached assets without `ignoreVary`;
   the server sends `Vary: Origin` and module/stylesheet requests
   (`crossorigin`) carry `Origin`, so they missed the cache and went to the
   network, where the new deploy no longer has the old hashed files: no CSS, no
   JS. Fixes in `web/vite.config.ts` + `main.ts`:
   - `ignoreVary: true` on cache lookups (the root cause).
   - Precache with `cache: 'reload'` so a stale HTTP-cached index.html can't
     end up in the new cache.
   - A new version waits instead of taking over at once; the page activates it
     only in its first 15 s (never mid-drive) and reloads into it.
   Verified: version A from cache, deploy B, one reload lands on B healthy, old
   cache deleted.

## Not covered here

- Car-like viewport (1920x1200 at DPR 1): needs DevTools device emulation set
  to a Responsive 1920x1200 / DPR 1 preset; the size fit in B also covers DPR 2.
- Shizuku path, hotspot static IP, HEVC, real Wi-Fi: Pixel + car tests.
- Waze / ABRP: install from Play Store (user) or test on the Pixel.

## Minor observations

- Debug overlay covers the top-right launcher icons (it ignores clicks, so the
  icons underneath still work). Fine for a debug view.
- After an update reload the `#code=` dev override is gone (it's stripped from
  the URL on first load). Dev-only; real use has saved settings.
- Most launcher icons are letters (known: adaptive icons don't load in the
  shell process).

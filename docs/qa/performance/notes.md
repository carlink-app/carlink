# Milestone: performance, first seconds after connecting (2026-11-15)

The car test showed some stutter in the first seconds. Measured with a new
profiler, `dev/perf-startup.mjs`: headless Chromium at the Tesla's default
browser size (773x601 @ 1.53), CPU slowed 4x (the car's computer is slower
than a desktop), against the real engine on the Pixel, with the map dragged
continuously so frame gaps mean freezes.

## Findings

| Finding | Measured |
|---|---|
| The app list carried every icon: 198 apps, ~942 KB in one message at connect, on the same connection as the first video frames | arrived 650 ms after connect |
| Bitrate started at 60% of the cap and took ~5 s to reach it (soft picture at first) | 12 -> 20 Mbit/s in 5 s |
| Page main thread | no long tasks even at 4x slower CPU (app list handled in ~20 ms) |
| "fps" can't show stutter (a still screen sends no frames on this phone's encoder, despite the repeat-frame setting) | 0 fps seconds on a static map |

## Changes

| Change | Effect |
|---|---|
| App list without icons (hash per icon); the car page keeps icons in IndexedDB and asks for missing/changed ones (`app-icons`) after the first frame; the phone sends them 16 per message, 40 ms apart | list 28 ms after connect; icons after the video, and only once per car browser |
| Older cached pages: the page says `icons: "hash"` in `hello`, otherwise icons stay inline | no letters-only launcher after an update |
| Start bitrate 80% of the cap | at the cap after 2 s |
| Debug overlay: `gap max` (longest time between frames, per second) and `stalls` (frames drawn >250 ms later than their phone timestamps say) | stutter visible in the car, still screens don't count |
| Exact first-frame mark (worker `firstDraw`) | timing for the profiler and the icon request |

## Results (Pixel, Tesla size, CPU 4x slower)

| | Cold (fresh engine, Waze starting) | Quick reconnect (display kept) |
|---|---|---|
| First frame after connect | 3.9 s (Waze's own cold start) | 95 ms |
| App list after connect | 0.7 s (engine renders icons once) | 28 ms |
| fps | 60 from second 3 | 58-60 from second 1 |
| Longest frame gap | 80 ms once, then 21-38 ms | 22-54 ms |
| Stalls | 0 | 0 |
| Bitrate at cap | 2 s | 2 s |

## Reviews

- Code review: an icon request over the phone's 16 KB line limit would end the
  session in a loop (requests split by size), older pages losing icons (hello
  flag), icons cached under a stale hash (hash in each reply, matched), icon
  request waiting 2 s when video started first, storage that never answers
  blocking the launcher (500 ms timeout), recents losing icons, still screens
  counted as freezes (timestamp-based), coarse first-frame detection.
- Security: icons size-capped (64 KB) and count-capped (500) in storage and
  on load, only requested icons accepted, engine de-duplicates and drops
  batches from an earlier session, profiler takes the pairing code from
  `CARLINK_CODE` and deletes its browser profile.

## Follow-up passes (after the first commit)

- Test coverage: ~30 tests (web 175 total, Android `AppsIconsTest`, core
  `app-icons` as a heavy message). Helpers extracted for testing:
  `chunkByBytes`, `acceptIcons`, `refreshRecents`, `DrawGaps`,
  `Apps.withIcons`. Found: names with control characters could make a request
  line too long; requests now only carry valid package names.
- Simplify: shared constants, `DrawGaps.takeMaxGapMs`, comment cleanups.
- Manual QA on the Pixel:

| # | Check | Result |
|---|---|---|
| 1 | First connect in a fresh car browser | 198 icons fetched after the first frame |
| 2 | Second connect, same browser | 0 icons requested; all 198 shown within 1 s (from the cache) |
| 3 | Previous page version (367c071, built locally) against the new engine | 198 icons inline (old pages keep icons) |
| 4 | Profiler after all changes: cold / quick reconnect | first frame 4.2 s (Waze cold start) / 97 ms; 60 fps; 0 stalls; system_server unchanged |

The live GitHub Pages page couldn't be tested from the desktop against the
phone's home-Wi-Fi address (Chrome blocks public sites from private
addresses); in the car the phone is 9.9.0.1, which isn't affected.

## Next

- Car test: read `gap max` / `stalls` in the overlay during the first minute.
- Cold start of an app after a disconnect (apps close with the car display
  since the Android 17 crash fix) is the app's own start time.

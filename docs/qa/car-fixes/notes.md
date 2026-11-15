# Milestone: car-test fixes (2026-11-15)

Fixes from the first car test and the evening's testing on the Pixel 9 Pro XL
(Android 17), with the four review passes and manual QA.

## What changed

| Area | Change |
|---|---|
| UI size | dpi floor in device pixels: the Tesla's 1.53 zoom gave 306 dpi (480 dp, phone layouts); now 230 dpi (640 dp, tablet layouts) |
| Bitrate | Caps HEVC 9 -> 16, H.264 15 -> 20 Mbit/s (the car link sat at the old cap all session) |
| Categories | New group "Charging & fuel" (ABRP, PlugShare, Tesla, EV and fuel-station apps); taxi/delivery apps moved to Other |
| Touch | Page sends at most ~125 moves/s per finger (a 1000 Hz mouse killed the session as a "control flood"); engine drops excess moves instead of closing, closes only at 2000/s; launches/closes limited to 20/s |
| Phone power | The car display shares the phone's power group on this Pixel, so it slept with the phone (frozen picture, no touch or keyboard). While a car is connected the phone stays awake, locked, with its panel off; power button lights it, screen timeout darkens it again; released 30 s after disconnect |
| Keyboard | IME policy set through the window service (the old path failed in the shell process): keyboard on the car display |
| Car-display guard | Settings, Play Store, installers, file managers, Shizuku and the keyboard's settings are closed within 1 s if an app opens them on the car display |
| Close apps | Task manager API instead of an `am` process per task |
| Full screen | Button in Display settings (only if the browser supports it; untested in the Tesla) |
| System crash | Android 17 bug: an app moved between displays is restarted by the system onto our display, which it may not do, and system_server crashed (phone soft-rebooted once during QA). Now: car display apps close on display removal (DESTROY_CONTENT_ON_REMOVAL), and an app open on the phone screen is closed there before it starts on the car |

## Reviews

- Code review: lock/timeout gaps in phone power (fixed), lit phone never timing
  out while the car is touched (own timeout, fixed), wake failure flapping
  (fixed), half-applied layouts (validate first), two rail glitches (fixed),
  close blocking the engine thread (task API).
- Security: phone could stay unlocked while dark (now locks first), keyboard
  on the car screen while locked (accepted, documented in README; keyboard
  settings blocked), apps opening Settings in the car (guard), panel left off
  after an engine crash (marker file, restored at next start), foldables (only
  the active panel), launch flooding (20/s), dev allow list ignored by release
  engines.
- Test coverage: ~45 new tests (Rust 37, Android 73, web 130); found the dev
  allow list accepting leading-zero octets that stop the engine (fixed).
- Simplify: shared service lookup and error unwrapping helpers.

## Manual QA (Pixel, headless Chromium at the Tesla default size 773x601 @ 1.53)

| # | Check | Result |
|---|---|---|
| 1 | Pane size at Tesla default browser size | 1034x918 at 230 dpi |
| 2 | Launcher groups | Navigation, Charging & fuel, Music, Social, Work, Other (`01`) |
| 3 | 600 touch moves without pauses | Session kept |
| 4 | Keyboard on Maps search | Shown on the car display (`mInputShown=true`) |
| 5 | Settings started on the car display from outside | Closed within 1 s, toast shown |
| 6 | Close front app (two taps) | Next app (Maps) in front |
| 7 | Close all | Rail empty, launcher shown, no tasks left on the car display |
| 8 | Phone asleep at connect | Woken, locked=true, panel off |
| 9 | Power button while dark / lit | Panel on / dark again (user) |
| 10 | Waze open on the phone, launched in the car | Closed on the phone, started on the car, no system crash |
| 11 | Disconnect | After 30 s: car apps closed (not moved to the phone), phone asleep, panel marker cleared |
| 12 | Settings screen at Tesla size | Full screen row shown (`02`) |

## Open

- The page's own launcher is large at the Tesla's zoom (2 columns): next
  milestone (tidier UI).
- Play Protect's on-device classifier rated the debug build "harmful" after a
  reinstall (notification access + shell control); no action taken by it yet.
- Second car test: UI size, bitrate, Full screen, phone in pocket.

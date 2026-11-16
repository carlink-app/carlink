# Less Shizuku milestone QA (2026-11-16)

Setup: Pixel 9 Pro XL (Android 17, Tensor G4), debug build over adb, engine on
the phone's Wi-Fi address (PC on the dev allow-list). Car page from `vite
preview` in Chrome at the Tesla browser size (773x601 CSS px). Emulators
(Android 11 / 13 / 16, 2 / 4 / 6 cores) for the device check and capability
probes.

## Results

| # | Check | Result |
|---|-------|--------|
| 1 | Car connects with the phone locked: phone panel off, locked, car keeps running | Pass |
| 2 | Power button while dark: lit and locked for the screen timeout, then dark again | Pass |
| 3 | Phone unlocked while connected: stays on (dim), no tricks | Pass ("keeping it on") |
| 4 | Phone locked again: dark at once | Pass |
| 5 | Battery and signal above the sidebar clock (45-47 %, charging, LTE 3 bars) | Pass |
| 6 | Notifications reach the car; notification access granted in Android's settings, not by the engine | Pass |
| 7 | Message text: Never shows app (+ title when the lock screen allows); Default follows the lock screen | Pass |
| 8 | Now playing; play/pause/next from the car without disconnecting | Pass |
| 9 | Notification tap brings its app in front; reload shows the app the phone put back | Pass |
| 10 | Device check: capability list (15 hidden APIs present on Android 16/17; Android 11 misses 3, all with fallbacks) | Pass |
| 11 | Engine refuses to start under root Shizuku with an explanation | Pass (emulator) |

## Found and fixed during this milestone's testing

| Finding | Fix |
|---|---|
| Media buttons dropped the car connection: `media-cmd` missing from the core's allow-list | Added; test that every page message type is on the list |
| Closing the playing app left the music playing | Closing it pauses it |
| Signal missing and charging false in the engine's shell process | One telephony init step; battery status property |
| Opened notification's app stayed behind the launcher; same after a reload | Launcher hides; phone reports the restored app (`pane.front`) |
| Now playing lost after Spotify was stopped and restarted (only the followed session was watched) | Callbacks on every active session |
| Encoder refused realtime keys with a CodecException (Android 11 emulator) | Every refusal steps down a level |
| Setup's Shizuku grant didn't link the main screen to the engine | Links on resume |
| Content under the status bar | Solid bar behind it |
| Review: quick power press after a long unlocked stretch went dark at once; no-lock phones stayed lit | Timer reset on lock; only a secure lock counts |

## Not covered

- In the car: local-network prompt on the normal hotspot (decides the fixed-address hack).
- Phones other than the Pixel (only emulators).

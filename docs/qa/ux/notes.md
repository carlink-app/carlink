# UX milestone QA (2026-11-16)

Setup: Pixel 9 Pro XL (Android 17), debug build over adb, engine on the
phone's Wi-Fi address with the PC on the dev allow-list. Car page from
`vite preview` in Chrome at the Tesla browser's size (773x601 CSS px; the
desktop draws at DPR 2 instead of the car's 1.53) and in headless Chromium at
773x601 @ 1.53 for screenshots.

## Results

| # | Check | Result |
|---|-------|--------|
| 1 | First visit shows the QR code (`01`), no address field | Pass |
| 2 | Scan with "Pair a car (scan QR)": page pairs, saves the code, reconnects with it ~7 s later, no error flash | Pass |
| 3 | Phone names the address that got the code | Pass (the PC's address) |
| 4 | "Type the code instead" form, address under Advanced | Pass |
| 5 | Connection help panel on an unreachable phone (`02`) | Pass |
| 6 | Launcher search ("pl" gives 4 apps) | Pass |
| 7 | Hold an app: Open / Pin to sidebar / Close app / Cancel; pinned app above a line | Pass |
| 8 | Launch from a pinned app (Waze) | Pass |
| 9 | Now playing: cover art, title, artist, colours from the cover (`03`) | Pass |
| 10 | Notifications bell always in the sidebar, no badge at 0 | Pass |
| 11 | Setup & checks screen on the phone | Pass (used for setup) |

## Found and fixed during QA

| Finding | Fix |
|---|---|
| Cover art never reached the car: each metadata read is a new Bitmap, so the per-bitmap cache re-encoded forever | Cached per track |
| Phone said Stopped after pairing while running | State refreshed after the engine swaps back |
| QR pairing ignored the dev `#host=` override | Uses the effective address |
| Now-playing colour averaged to grey on busy covers | Strongest hue group |
| A-Z strip took two rows (two scripts plus #) | Removed; search only |
| Drag to reorder pins, tap twice to close, Close all | Removed at the user's request; Close app in the hold menu |

## Not covered

- In the car: QR on the Tesla screen, hold gestures on its touchscreen.
- Setup steps for Xiaomi, Samsung, OnePlus (other phones).
- Play/pause/next from the card against the phone (only display checked).

# Friendly phone UI and faster QR pairing (design)

## Why

A non-technical user should be able to install TSLink, set up Shizuku in a few
guided taps, glance through the optional extras and pair the car with the QR
code. In the last try, "Pair a car" did nothing visible until the engine was
started (the hint went to a card scrolled off screen). Ambiguous controls like
that go.

## Goals

- One obvious next action on the main screen, driven by state.
- No "engine" in the UI. Words: Start/Stop (the background service),
  Pair/Unpair (cars), Connect/Disconnect (a live car session).
- Setup as three short pages: Shizuku, Battery & hotspot, Optional (skippable),
  ending at "Pair a car".
- QR pairing from scan to picture in about 2 s (now ~5-10 s), measured.

## Non-goals

- No Compose / Material dependency; no change to the car page beyond pairing
  timing.
- No change to what the engine does or to the IEngine interface (beyond what
  faster pairing needs).
- Parked items stay parked (built-in pairing, auto-restart after reboot).

## Approaches

1. Keep Android Views (Ui.kt helpers), restyle and regroup. **Chosen:** no new
   dependencies, small APK, builds on what exists.
2. Rewrite in Jetpack Compose / Material 3. Rejected: full UI rewrite, bigger
   APK and dependency surface for a three-screen app.

## Design

### Main screen

- Status card on top: dot + plain state ("Off", "Ready: waiting for the car",
  "Connected to the car"), a one-line hint for what to do in the car (join the
  phone's hotspot, open tslink.app), and one primary button:
  - setup incomplete: "Finish setup";
  - otherwise: "Pair a car (scan QR)";
  - car connected: "Disconnect".
- "Type the code instead" expands the pairing code (hidden by default).
- Options: notifications in the car (switch + Default/Always/Never),
  start with the car (car name, choose).
- More (collapsed): hotspot mode, Unpair all cars (new code, with a confirm),
  Stop/Start, Device check, Log, Setup again, version.
- TSLink starts by itself when Shizuku allows it, unless the user pressed Stop
  (existing engineWanted pref; default becomes on once setup is done).
- Pair starts TSLink first if needed, then opens the scanner. Messages appear
  next to the button that caused them.

### Setup

Three pages with Back/Next and a page indicator:
1. Shizuku: one card with the current sub-step (install, start, allow).
   Next enabled when allowed.
2. Battery & hotspot (+ brand-specific steps).
3. Optional: status notification, notifications in the car, start with the
   car. "Skip" / "Done".
Done returns to the main screen, which offers "Pair a car". The pure
`Setup` model keeps its tests; pages group its steps.

### Pairing speed

- Car page: while a QR secret is shown, retry every 1 s (no backoff to 5 s).
- Engine: hand back to the normal engine as soon as the `paired` message has
  gone out (short flush, not a fixed 1.5 s).
- Car page: after `paired`, reconnect right away with short retries instead of
  a fixed 2.5 s wait.
- Measure scan-to-picture before and after on the Pixel (or emulator).

## Left to the builder

Exact copy, colours, spacing; whether More is an expander or a second screen;
the flush mechanism for the `paired` message.

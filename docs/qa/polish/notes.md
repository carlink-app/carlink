# Milestone: tidier notifications, transitions, app icon, phone screen (2026-11-15)

## What changed

| Area | Change |
|---|---|
| Notifications in the car | One compact pop-up at the top right (calls always shown, on top), 6 s; older ones wait in a tray behind a bell on the rail with a counter; "+N" on the pop-up opens the tray; touching the app closes the tray (the touch doesn't reach the app) |
| Privacy | Tray and pop-ups are cleared as soon as the phone disconnects and whenever the phone re-syncs or notification access ends (`notif-reset`); replays after a reconnect skip what the driver already opened or dismissed |
| Switching apps | "Opening <app>" placeholder (icon + name) instead of a black or stale frame, until the app has drawn (engine `launched` + 450 ms, max 3 s) |
| Page UI at the Tesla's zoom | Rail, launcher, banners, tray and settings scale by `1.15 / devicePixelRatio` (0.75 at the Tesla's 1.53): launcher fits 3 columns |
| Launcher groups | "Charging & fuel" separated from Navigation |
| Phone app | Adaptive icon (phone mirrored to a car screen); card layout: Engine (dot + one Start/Stop), Pairing code, Car connection, Car, Notifications, Diagnostics |
| Phone status | Ongoing "Connected to car" notification (lock screen) with Disconnect, like Android Auto's |
| Pairing code | Not selectable, hidden from the Recents thumbnail |

## Reviews

- Code review: notice left up after auto-stop (session reported on stop, resent
  on relink), dismissed notifications coming back on reconnect (replay flag +
  dismissed set; opened ones dropped by the engine), status stuck on
  "Starting engine...", stale `launched` hiding a newer placeholder, tray and
  placeholder over the launcher, CSS widths under zoom, Disconnect racing
  auto-start (same executor).
- Security: tray readable after disconnect (cleared), tray not following phone
  re-syncs (`notif-reset` to the page), pairing code copy/thumbnail, notice
  clearing on Stop/auto-stop/access off, tray tap guard, label crash guard.
  Disconnect from the lock screen is intended (as Android Auto).
- Test coverage: 144 web tests (tray/banner interplay, badge, ordering, UI
  scale), Android EngineStatus parsing.
- Simplify: shared helpers for clearing, dismiss buttons, session messages.

## Manual QA

| # | Check | Result |
|---|---|---|
| 1 | Launcher at Tesla size (demo) | 3 columns, groups (`01`) |
| 2 | Pop-up + pinned call + "+1" + bell counter (demo) | Pass (`02`) |
| 3 | Tray (demo) | Pass (`03`) |
| 4 | Opening placeholder (demo) | Pass (`04`) |
| 5 | Pixel, user's Chrome: 3 phone notifications | One pop-up "+2", bell 3, tray with 3 |
| 6 | Pixel: launch Waze | Placeholder, then Waze at 46 fps |
| 7 | "Connected to car" on the phone, lock screen | Shown (after allowing notifications; now asked when the app opens) |
| 8 | New phone screen and icon | Seen by the user |

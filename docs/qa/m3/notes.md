# Milestone 3: notifications, icons, auto-start, display size (2026-11-15)

Setup: GUI emulator (Android 16, Google Play image, test Google account
signed in by the user), Shizuku from Play started over adb
(`.../libshizuku.so`), carlink engine started the production way (app ->
Shizuku user service, `dev/shizuku-engine.sh`), dev switch
`files/dev_allow_any` (accept the car page from the emulator's 10.0.2.2), the
user's Chrome through the Claude extension. Waze and ABRP installed from Play
by the user.

Recording: the Chrome GIF export for this run didn't arrive in Downloads
(Chrome likely held it behind a multiple-downloads prompt); screenshots below.

## What was built

- Notifications to the car: a NotificationListenerService in the app forwards
  worthwhile notifications to the engine, which sends `notif` / `notif-gone`
  to the page; banners on the page (max 3, calls stay until gone, tap opens,
  × dismisses, 400 ms tap guard). Re-sync on link-up; replay after reconnect
  only for calls and notifications under 5 minutes old.
- Privacy: the car screen follows the lock-screen rules. SECRET is never shown;
  PRIVATE content is shown only if "sensitive notifications" is allowed on the
  lock screen (otherwise the app's public version, or "New notification").
  Android 15+ also redacts detected one-time codes ("Sensitive notification
  content hidden" was seen for "Gate code is 1234").
- Notification access is granted only by the user's tap in the app (through
  the engine: one tap), never silently.
- Real app icons: rendered in the app process (the shell engine can't load
  adaptive icons), sent in batches of 8 per binder call.
- Auto-start: pick the car among paired Bluetooth devices; connect starts the
  engine + car hotspot; disconnect stops only what auto-start started, after
  20 s (Bluetooth dropouts).
- Display size: automatic, the biggest UI that keeps apps in their wide
  layout (shortest side ~640 dp, 200-260 dpi). The size picker was removed at
  the user's request. Tesla browser area: ~1190x880 (normal) / ~1840x880
  (expanded) -> 220 dpi.

## Findings during QA (fixed)

| Finding | Fix |
|---|---|
| SELinux: an app can't connect to a socket owned by the shell-domain engine (`avc: denied { connectto }`) | App <-> engine link moved to binder (IEngine.setClient / fromApp), the way Shizuku itself works |
| An update didn't replace the running engine daemon (Shizuku reuses it when the version is unchanged) | Engine version = build timestamp |
| Engine dpi wrong after the encoder size fit (518 dpi on a shrunk display) | dpi scaled with the fitted size (`Pane.scaledDpi`) |
| Messages showed only "New notification": PRIVATE treated as always hidden | Lock-screen semantics (see above) |
| Tapping a banner did nothing: Android 16 blocks background starts of notification intents (`BAL_BLOCK`) and reports success to the sender anyway | Engine (shell) opens the app on the car display first (allowed), then fires the intent. Result: the app opens on the car; Android may still refuse the exact screen |
| Emulator keyboard didn't type (`hw.keyboard=no`) | AVD config `hw.keyboard=yes` |

## Checks

| # | Check | Result |
|---|-------|--------|
| 1 | Real app icons in launcher and rail (through Shizuku) | Pass (screenshot removed: showed the test account email) |
| 2 | A real phone notification appears as a banner (Play services) | Pass (screenshot removed: showed the test account email) |
| 3 | SMS banner shows sender + text | Pass (`06`) |
| 4 | One-time code is redacted by Android | Pass |
| 5 | Tap banner opens the app on the car display | Pass, app level (screenshot removed: showed the test account email) |
| 6 | Waze runs on the car display (location prompt answered by the user) | Pass (`05`) |
| 7 | Banners in demo mode: update in place, cap 3, markup shown as text, bad icon rejected | Pass (`01`) |
| 8 | Message-text toggle | Pass |
| 9 | Display size picker (`02`, `03`) | Superseded: removed, automatic now |
| 10 | Bluetooth auto-start | Not testable on the emulator (no Bluetooth): Pixel test |

## Reviews

- Code review: 3 bugs fixed: icon batch exceeding the binder buffer, no
  relink after engine death, call banner stuck after `tracked` LRU eviction
  (now a separate sent-id set). Also fixed: car connect/disconnect races
  (single-thread executor), clean engine stop before process exit, `am`
  timeout, emoji-safe truncation, notification icons 48 px for the 16 KB cap.
- Security: no injection/escalation. Fixed: lock-screen privacy rules,
  explicit notification-access consent, stale-notification replay, banner tap
  guard, auto-stop only what auto-start started, pairing code excluded from
  backup/device transfer.
- Test coverage: +28 tests (Android, Rust, web).
- Simplify: see final report.

## For the Pixel / car test

- Shizuku: start it via Wireless debugging, allow carlink.
- In carlink: Start engine, "Turn car notifications on", choose the car.
- Tesla: try the browser's expand button and the Theater route for more
  screen; the debug overlay (`?debug=1`) shows the real viewport size.

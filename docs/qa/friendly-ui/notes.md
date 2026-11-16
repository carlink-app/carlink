# Friendly phone UI and faster QR pairing: QA (2026-11-16)

Setup: Pixel 9 Pro XL over USB, debug build at 78220f8. Car page: local
build (`vite preview` on 127.0.0.1:4173) in Chrome at Tesla size, dev host
override to the phone on home Wi-Fi.

## Result: pass

| # | Check | Result |
|---|-------|--------|
| 1 | Main screen while a car is connected: "Connected to the car", Disconnect | Pass (`01`) |
| 2 | More: phone address, car page, Unpair all cars, Stop TSLink, Setup, Device check, Log, certificate | Pass (`02`) |
| 3 | Disconnect: status Off, primary button Pair a car | Pass |
| 4 | Pair a car while Off: one tap starts TSLink, then opens the scanner (once) | Pass |
| 5 | QR pairing, scan to apps on the car page | Pass, 1.6 s (was 10.6 s) |
| 6 | Setup opens on the first page that needs something (all done: Optional) | Pass (`05`) |
| 7 | Setup pages: Back/Next, Shizuku shows one card, Battery & hotspot | Pass (`03`, `04`) |
| 8 | Unpair all cars: confirm dialog, old session dropped, new code at once, no "Address already in use" | Pass (cert 7d5527 -> 2bff0f, restart 20 ms) |
| 9 | Car page gear while connected: "Paired and connected", Pair again | Pass |
| 10 | Car page gear while not connected / first visit: QR first, Type the code instead | Pass |
| 11 | Chosen car highlighted; no car/normal hotspot choice | Pass (`02`) |

## Pairing timing (phone log, tag tslink)

| Step | Before (main) | After |
|---|---|---|
| Phone in pairing mode -> code sent to the page | 5.2 s | 1.0 s |
| Code sent -> normal engine back | 0.1 s | 0.03 s |
| Normal engine -> car page connected | 5.3 s | 0.6 s |
| Total | 10.6 s | 1.6 s |

Both 5 s gaps were handshakes started while the phone switched codes: no
answer, and the page waited out QUIC's retransmits. While pairing the page
now cuts a handshake after 1.5 s and retries.

## Found during QA (fixed)

| Finding | Fix |
|---|---|
| Pair did nothing visible while TSLink was off (hint went to a card off screen) | Pair starts TSLink, then scans; notes sit under the button |
| "Address already in use" after Unpair all cars | stop() waits for the port; binds retry briefly |
| Status didn't change after pairing (no refresh) | Status read every 2 s while the screen is up |
| Pairing took ~10 s | Short handshakes and fast retries around pairing |
| Car page: settings and first visit were different screens | One QR-first pairing screen |
| Car page help said "Start engine" | Reworded |

## Noted, not fixed

- A car page tab left retrying across a phone engine *process* restart (app
  update) stopped sending handshakes until reloaded (Chrome side). Not seen
  without an app update; a reload fixes it.
- After the car leaves (Bluetooth auto-stop or Disconnect) TSLink stays off
  until Pair or Start: by design.

# Round 2: camera link, pairing port (2026-11-16, later)

Same setup; builds up to 3bbcca4. Clean reinstall of TSLink and a clean car
page (site data, cache and service worker cleared) before the final runs.

| # | Check | Result |
|---|-------|--------|
| 1 | Clean reinstall: the old engine exits by itself after uninstall | Pass (6 s, port freed) |
| 2 | Reinstalled app is accepted (was "denied") | Pass |
| 3 | First visit, home Wi-Fi: amber "Can't reach the phone at 9.9.0.1:4433" | Pass |
| 4 | Advanced: phone address on the QR screen, Use this address | Pass |
| 5 | QR up for minutes: no failed handshakes (Chrome no longer throttles the page) | Pass (phone log: one waiting connection, no errors) |
| 6 | Scan -> code -> car connected | Pass, 110 ms and 200 ms (was 10.6 s; earlier fix 1.6 s) |
| 7 | One scan per pairing; the waiting page and the scan match (tag) | Pass |
| 8 | Phone note stays "Paired with the car at ..." (was "Pairing timed out" after 2 min) | Pass, checked at 2 min 7 s (`06`) |

## Found and fixed this round

| Finding | Fix |
|---|---|
| Pairing stalled after the QR was up a while (Chrome: failed WebTransport handshakes delay new ones up to 60 s for 5 min) | Pairing port 4434 with a public certificate; the page waits there; the code comes sealed with a key from the QR secret |
| "denied" after reinstalling TSLink | Engine looks TSLink's uid up again (same signer only) |
| Engine kept running after uninstall | Stops and exits once TSLink is gone |
| No way to set the phone address with QR pairing | Advanced: phone address on every pairing view |
| Warning colours inconsistent | One palette on phone and car page: red broke, amber needs you, green worked, grey info |
| Reviews: "Pairing timed out" after a pairing that worked; a reloaded page's dead wait could take the code; 8 reloads could block pairing; another app could pass a link secret | Fixed with tests (see commit 2182e5c) |

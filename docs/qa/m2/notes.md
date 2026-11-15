# Milestone 2 — adaptive streaming — 2026-11-15

Scope: bitrate adaptation for 2.4 GHz hotspot links, HEVC/H.264 profile
choice, GitHub Pages deploy workflow, review fixes.

Setup: same as M1 (GUI emulator, `dev/emu-engine.sh`, `vite preview`, the
user's Chrome via the Claude extension, `?debug=1`). Throttled runs go through
`spike/bin/netsim -profile wifi24-busy` (22 Mbit/s usable, 2.5 ms + 4 ms
jitter, 10%/100 ms chance of a 30-150 ms busy stall, 1% loss), page pointed
at it with `#host=127.0.0.2&port=4433`.

Recordings: `carlink-m2-rate-busy24.mp4` (tuning runs), `carlink-m2-qa.mp4`
(final build), numbered screenshots.

## What was built

- `core/src/rate.rs`: sender-side rate control that ignores random loss.
  Every 200 ms: RTT vs. its 10 s floor, send-queue depth, queue overflows,
  frames sent. Backs off x0.75 when 3 of the last 5 ticks were congested;
  climbs +level/8 after a clean second, only while video is flowing. Output
  is a level (150-1000 permille of the codec's max bitrate).
- Engine applies the level live (`PARAMETER_KEY_VIDEO_BITRATE`), no encoder
  restart. Max: H.264 15 Mbit/s, HEVC 9 Mbit/s.
- Codec: HEVC when the car's browser decodes it in hardware and the phone has
  an encoder; otherwise H.264. Falls back to H.264 if the HEVC encoder fails.
  `?codec=h264` on the car page forces H.264.
- Debug overlay shows the target bitrate.
- `.github/workflows/pages.yml` + `dependabot.yml` (not pushed; no remote yet).

## Tuning on the busy 2.4 GHz profile

| Version | Behaviour | Fix |
|---|---|---|
| v1 | Pinned at the floor (2.3 Mbit/s) on a 22 Mbit/s link (`01`) | Short Wi-Fi stalls counted as congestion. Require it sustained: 3 of 5 ticks |
| v2 | Still dropped hard; logs showed drop-until-keyframe skips counted as overflows | Count real queue overflows only. Then: 9 → 2.8 Mbit/s during heavy panning (RTT 514 ms), back to 15 in ~12 s (`02`) |
| v3 | Idle screen climbed to max, so the next motion burst hit the link at full rate | Only climb while >= 30 fps is being sent (`03`) |

Final build on the same profile: panning with 0 drops, 0 decode errors
(`07`); the stalls are random, so not every run triggers a backoff.

Caveat: the emulator's software encoder manages roughly 5-15 fps at
2006x994, so frame pacing and the 30 fps "video is flowing" gate behave
differently from the Pixel's hardware encoder. Real tuning needs the car.

## Checks on the final build

| # | Check | Result |
|---|-------|--------|
| 1 | Connects; overlay shows rate 9.0 Mbit/s (start level) | Pass (`04`) |
| 2 | Clean link: level climbs to max (15 Mbit/s) | Pass |
| 3 | Engine restart: app comes back, launched exactly once (logcat: one START) | Pass (`05`) |
| 4 | Web update with dev override: reloads into the new version, override kept, URL stays clean | Pass |
| 5 | Dev override shows a visible notice | Pass (`06`) |
| 6 | Busy 2.4 GHz link: streams, adapts | Pass (`07`, tuning table) |
| 7 | HEVC end to end | Not testable here: desktop Chrome on Linux has no HEVC decoder. Falls back to H.264 as designed. Needs the car |

## Review passes

- Code review: `MediaCodec.getName()` is API 31 but minSdk is 30, so every
  pane would fail on Android 11 (fixed: `codecInfo.name`). Also fixed:
  double app launch on reconnect (engine and page both resumed; now one
  path), HEVC fallback on unrelated errors, service worker version ignoring
  `index.html` changes (now content-hashed, plugin runs `enforce: 'post'`),
  frame copy under the global lock.
- Security: no critical/high. Fixed: replaced session's rate task could
  steal the new session's counters / override its bitrate; CI permissions
  per job, actions pinned to SHAs, `npm ci --ignore-scripts`; pairing code no
  longer restored into the URL on update reloads (sessionStorage instead);
  visible notice for dev overrides; codec list parsed instead of substring
  match; extra `.gitignore` entries.
  Open, needs a decision: **GitHub Pages origin is shared** by every Pages
  site of the same account (`<user>.github.io`), and the pairing code lives
  in that origin's localStorage. Use a dedicated origin (custom subdomain) or
  keep no other Pages sites on the account.
- Test coverage: tests added for rate control edge cases, backpressure
  counters, per-session start level (integration), `Pane.fit`, settings,
  and the generated service worker (runs `sw.js` against fake SW globals).
- Simplify: 11 small edits, no behaviour change.

Totals: Rust 29 unit + 1 integration, Kotlin 14, web 48. Dependency audit
clean (cargo audit, npm audit, osv-scanner).

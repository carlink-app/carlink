# carlink: benchmark results and findings (2026-11-15)

Everything here is from the **local rig**:
- Android 16 emulator (Pixel 9 Pro AVD) runs the server.
- `netsim` simulates hotspot Wi-Fi.
- Headless Chromium 156, pinned to 4 cores at 1920x1200, stands in for the Tesla browser.

No car data yet. The rig can't reproduce:
- the Tesla's GPU and hardware decoder (headless uses software decode and SwiftShader)
- the private-IP block
- Wi-Fi priority queues
- the phone's hardware encoder

Treat these numbers as "does the pipeline work and how does it react to bad Wi-Fi", not as final latency figures.

Screenshots: `screenshot-running.png`, `screenshot-results.png`. Recording of a full run: `benchmark-run.mp4` / `benchmark-run.gif`.

## Results

Times in ms. `jit` = one-way network jitter, `rtt` = round trip measured during video, `n2p` = frame received to drawn. All runs at 1920x1200, H.264 High, 10 Mbit/s, 60 fps.

| Link (netsim) | Transport | fps | decode p99 | n2p p99 | jit p99 | jit max | rtt p50 | rtt p99 |
|---|---|---|---|---|---|---|---|---|
| none (loopback) | WebTransport | 60 | 2.5 | 5.0 | 0.5 | 0.9 | 0.3 | 1.5 |
| none (loopback) | WebSocket | 60 | 1.6 | 3.1 | 0.3 | 0.4 | 0.2 | 0.3 |
| 5 GHz | WebTransport | 60 | 2.9 | 4.4 | 2.9 | 4.9 | 3.5 | 6.6 |
| 5 GHz | WebSocket | 60 | 1.4 | 2.9 | 3.5 | 4.3 | 3.5 | 7.1 |
| 2.4 GHz (full run) | WebTransport | 60 | 1.3 | 3.1 | 29.9 | 70.5 | 10.8 | 49.3 |
| 2.4 GHz (full run) | WebSocket | 60 | 2.0 | 3.6 | 32.4 | 59.0 | 11.9 | 54.2 |
| 2.4 GHz busy (full run) | WebTransport | **35** | 3.1 | 18.5 | 158.7 | 198.7 | 30.0 | 160.2 |
| 2.4 GHz busy (full run) | WebSocket | 59 | 3.1 | 5.0 | 152.5 | 162.8 | **138.3** | 311.7 |

Decode and render in software (headless Chromium, SwiftShader):

| Renderer | fps | decode p99 | draw p99 |
|---|---|---|---|
| 2D canvas `drawImage` | 60 | 1.3-3.0 | 1.6-3.0 |
| `bitmaprenderer` | 60 | 1.3-1.6 | 3.1-3.4 |
| WebGL `texImage2D` | 54-59 | 45-108 | 27 |

Wi-Fi power-save emulation (2.4 GHz + the client dozes after 100 ms idle):

| Ping interval | rtt p50 | rtt p99 |
|---|---|---|
| 20 ms | 7.6 | 29.6 |
| 200 ms | 68.8 | 104.9 |
| 1000 ms | 55.9 | 140.2 |

Raw reports: `../rig-reports/*.json` (`python3 summarize.py rig-reports/*.json`).

## Findings

1. **The pipeline works end to end.** Phone server in Android, WebTransport with a self-signed certificate, WebCodecs decode in a Worker and canvas drawing all run at 60 fps with 1920x1200 H.264. On a clean link, receive-to-pixel is about 3 ms. Encode (phone) and display scan-out are not included.

2. **Static-IP hotspot without root works.** `CarHotspot start` brought the Android 16 emulator's hotspot up as `9.9.0.1/24` in tethered state through `adb shell app_process`. This is the CarMirror fix for the Tesla's private-IP block. It still has to be tried on the Pixel.

3. **On 5 GHz and normal 2.4 GHz, WebTransport and WebSocket perform about the same.** Head-of-line blocking only showed up clearly when netsim dropped UDP but not TCP, which wasn't a fair test. With TCP retransmit delay modelled too, they were within noise of each other. So WebTransport's advantage is mostly about recovery behaviour, not raw latency.

4. **A busy 2.4 GHz link (22 Mbit/s, frequent interference) breaks both transports, in different ways:**
   - WebSocket delivers every frame but lags by 140-300 ms. The queue grows.
   - WebTransport keeps lag lower but drops to about 35 fps. QUIC's congestion control backs off on random Wi-Fi loss, and the server never skips frames.
   - Neither is acceptable, and the transport choice can't fix it. What fixes it is **bitrate adaptation**: the 2.4 GHz "safe" profile (HEVC around 5-6 Mbit/s or a lower resolution), plus dropping to the next keyframe when backed up. It also needs congestion control that doesn't treat random Wi-Fi loss as congestion (BBR or app-level rate control). That's a point for Rust/quinn, which supports pluggable congestion control.

5. **Wi-Fi power-save could add 50-140 ms after idle**, in the simulation. If the car shows the same pattern (RTT grows with ping interval), the app needs a small keepalive about every 20 ms in both directions. The benchmark's 20/200/1000 ms ping tests measure exactly this on the real car.

6. **Draw only the newest decoded frame.** If drawing is slower than decoding, the worker now draws only the latest frame and drops older decoded ones. Without this, a slow renderer (WebGL in software) pushed decode latency to 200-380 ms. Encoded frames must never be dropped. Keep this rule in the app.

7. **Renderer choice is a car question.** In software, WebGL is the worst (texture upload), while 2D canvas and `bitmaprenderer` are equal. On the Ryzen GPU it may be the other way round. The benchmark measures all three.

8. **Small things found along the way:**
   - A hotspot with WPA3 only is invisible to the Tesla (ATTD docs). Keep WPA2.
   - The Tesla routes audio to the browser while the page is open (CarMirror, ATTD), so audio must be streamed.
   - quic-go on Android warns that it can't get a large UDP receive buffer (512 KiB). The real app should set socket buffers explicitly.
   - Tooling bugs the rig caught: a TCP queue that dropped bytes, a stopped test's download saturating the link, `adb forward` adding about 20 ms to TCP. All fixed.

## What still needs the car

Run the benchmark (README, "In the car") in this order:
1. `CarHotspot start` on the Pixel (USB, at home), then the server in Termux.
2. **Test A**: `http://9.9.0.1:8080/` in the Tesla. Does the page load at all? (No internet needed.)
3. **Test B**: the public copy of `dist/`, host `9.9.0.1`, **Run benchmark**. Run once parked and once while driving (passenger).

The report answers:
- hardware decode yes/no
- HEVC yes/no
- best renderer
- WebTransport from a public page to 9.9.0.1 yes/no
- real 2.4 GHz jitter
- whether power-save shows up
- whether DSCP priority helps

## Decisions waiting on car data

| Question | Default if car data agrees |
|---|---|
| Transport | WebTransport (WebSocket fallback), page hosted publicly, no DNS/cert backend |
| Codec | HEVC on 2.4 GHz if hardware decode works, H.264 otherwise |
| Renderer | whichever has the lowest draw p99 on the car |
| Keepalive | on if ping RTT grows with interval |
| Phone network core | Rust (quinn, custom congestion control); Go stays for tools |

## Roadmap

**Phase 0, benchmark (done locally, car run pending).**

**Phase 1, Android app core** (can start now; it doesn't depend on car data):
- Kotlin app: foreground service, shell access via Shizuku or wireless-debugging self-pairing.
- Privileged service:
  - static-IP car hotspot (port `CarHotspot`)
  - trusted virtual display at 1920x1200
  - app launch onto that display
  - touch and key injection
- MediaCodec low-latency settings: intra-refresh, high operating rate, urgent thread priority.
- Two profiles, picked by band and adjusted live:
  - **5 GHz "fast"**: 1920x1200@60, H.264 12-15 Mbit/s
  - **2.4 GHz "safe"**: HEVC 5-6 Mbit/s, dropping to 1280x800 or 30 fps under jitter
- Network core: WebTransport server, rate control that ignores random loss, drop-to-keyframe on backlog, keepalive, DSCP marking.

**Phase 2, car web client:**
- Page hosted publicly with a service worker so it loads offline.
- Reconnect loop with a keyframe request on each connect.
- Decode in a Worker, newest-frame drawing.
- Touch input with instant local ripple, keyboard bridge.

**Phase 3, audio and comfort:**
- PCM/Opus audio via WebAudio, Tesla media card controls.
- Auto start/stop on car Bluetooth, startup under 5 s.
- App launcher, split screen, navigation card.

**Phase 4, hardening:** auto-degrade, latency overlay, logs. Android Auto head-unit mode later if wanted.

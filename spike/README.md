# carlink benchmark

A benchmark page plus test tools for the questions that decide the
architecture, before the real app is built:

1. Can the Tesla browser reach a server on the phone over the hotspot?
   (Reports say it refuses private IPs. `carhotspot` moves the hotspot to a
   public-looking subnet, 9.9.0.1, to get around that.)
2. WebCodecs decode at 1920x1200@60: H.264 vs HEVC, hardware vs software,
   and which renderer (2D canvas, WebGL, bitmaprenderer) is fastest.
3. WebTransport from a public HTTPS page to the phone with
   `serverCertificateHashes`: if it works, no DNS/certificate backend is needed.
4. Real network numbers over the hotspot: jitter, RTT, WebTransport vs
   WebSocket, Wi-Fi priority (DSCP) on/off, Wi-Fi power-save on idle links.

## Parts

| Path | What |
|---|---|
| `main.go` | `spike-server`: page over HTTP `:8080` (HTTPS `:8443` with `-cert/-key`), WebSocket `/ws`, WebTransport UDP `:4433` and `:4434` (same, packets marked DSCP EF), clip streaming at 60 fps, ping echo, reports saved to `reports/` |
| `web/` | benchmark page; decode and network run in a Worker drawing to an OffscreenCanvas |
| `gen-clip.sh` | test clips, 1920x1200@60, 10 s: `clip.h264` (High, 10 Mbit/s), `clip.hevc` (Main, 6 Mbit/s) |
| `hotspot/CarHotspot.java` | static-IP hotspot tool, run with shell rights via `adb shell app_process` |
| `netsim/` | userspace Wi-Fi simulator (no root): shared airtime, jitter, interference bursts, loss, client power-save |
| `rig.sh` | local rig: server in the Android emulator, netsim in between, Tesla-like headless Chromium runs the benchmark automatically |
| `summarize.py` | prints reports as a table |
| `build.sh` | builds everything into `bin/`, writes `web/config.json`, assembles `dist/` |

### WebTransport certificate

Chromium accepts a self-signed WebTransport cert if the page passes its
SHA-256 hash and the cert is ECDSA P-256, valid for 14 days or less. The
server's cert is deterministic (fixed key from `wt-seed`, validity aligned to
Monday 00:00 UTC, 13 days), so `build.sh` precomputes hashes for 52 weeks into
`config.json`. A public copy of the page works for a year; in the car you only
type the phone's IP. The key ships in the binary: fine for a benchmark, not
for the real app.

## Build

```
./build.sh
```

Needs Go, ffmpeg (clips), and the Android SDK (only for `carhotspot.dex`).

## In the car

### 1. Put the server on the phone (Termux)

Termux from F-Droid or GitHub (not Play Store). On the PC:
```
adb push bin/spike-server-android-arm64 bin/clip.h264 bin/clip.hevc /sdcard/Download/
```
In Termux (first time):
```
termux-setup-storage
mkdir -p ~/spike && cd ~/spike
cp ~/storage/downloads/spike-server-android-arm64 ~/storage/downloads/clip.* .
chmod +x spike-server-android-arm64
```
Each session:
```
termux-wake-lock
cd ~/spike && ./spike-server-android-arm64
```

### 2. Move the hotspot to 9.9.0.1 (USB, before driving)

```
adb push bin/carhotspot.dex /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/carhotspot.dex app_process / CarHotspot start
```
The phone is then `9.9.0.1`, the car gets `9.9.0.2`. Mobile data sharing keeps
working. Back to the normal hotspot: `... CarHotspot normal`. Off: `... CarHotspot stop`.
If it prints an error, the normal hotspot still works for test A on 192.168.x.x.

### 3. Run the benchmark

**Test A, page from the phone** (no internet needed): Tesla browser,
`http://9.9.0.1:8080/` (or `http://<hotspot-ip>:8080/` on the normal hotspot).
Expect `secure context: false`: no WebCodecs or WebTransport there, so only
the WebSocket ping tests run. This answers "can the car reach the phone".

**Test B, public HTTPS page** (internet needed to load the page): host `dist/`
on any static HTTPS host (GitHub Pages, Cloudflare Pages, Netlify), open it in
the Tesla, enter `9.9.0.1` as phone host, press **Run benchmark**. Takes about
4 minutes. Run it parked, then again while driving (passenger operating).

**Test C, own domain** (optional): DNS A record `t.example.com -> 9.9.0.1`,
cert via `certbot certonly --manual --preferred-challenges dns -d t.example.com`,
run the server with `-cert fullchain.pem -key privkey.pem`, open
`https://t.example.com:8443/`. Tests whether the resolver drops private/odd answers.

The report is sent to the phone at the end of each benchmark (also: **Send
report to phone**). It lands in `~/spike/reports/`. Copy to the PC:
```
cp -r ~/spike/reports ~/storage/downloads/     # Termux
adb pull /sdcard/Download/reports .            # PC
python3 summarize.py reports/*.json
```

## Local rig

```
emulator -avd carlink -no-window -no-audio &      # Pixel 9 Pro, Android 16
./rig.sh -p wifi24             # profiles: none wifi5 wifi24 wifi24-busy
./rig.sh -p wifi24 -s          # + Tesla Wi-Fi power-save
./rig.sh -t host -p none       # server on this machine, no emulator
./rig.sh -k                    # visible browser
```

Chain: Chromium -> netsim on 127.0.0.2 -> adb forward / emulator UDP redirect
-> spike-server in the emulator. Reports and logs go to `rig-reports/`.

What the rig does not emulate: the Tesla's GPU and hardware decoder (headless
Chromium decodes in software and renders with SwiftShader), the private-IP
block, Wi-Fi priority queues (DSCP has no effect in netsim), and the phone's
hardware encoder. netsim's profile numbers are estimates; calibrate them
against the car reports.

## Reading the numbers

- `dec`: `decode()` call to decoded frame. Target < 10 ms.
- `draw`: decoded frame to drawn.
- `net→px`: frame fully received to drawn.
- `jitter`: one-way delay minus the best seen; the network part. Watch p99/max.
- `rtt`: ping round trip (during video in video tests).
- `drop`: decoded frames skipped because drawing was slower than decoding
  (only the newest frame is drawn; encoded frames are never dropped).
- Ping tests at 20/200/1000 ms: if RTT grows with the interval, the Tesla's
  Wi-Fi is dozing between packets; the app then needs a keepalive.

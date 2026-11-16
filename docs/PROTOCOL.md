# TSLink wire protocol v2

The app was called carlink before; the protocol keeps that name in its
internal identifiers (session path `/carlink`, the `carlink-auth-*` and
`carlink-wt-key-v1` labels, the certificate name), so they are unchanged.

Phone (engine) <-> car page (Tesla browser) over WebTransport (HTTP/3, QUIC).

## Connection

- URL: `https://<host>:<port>/carlink`. Defaults: host `9.9.0.1` (car hotspot
  mode), port `4433`. Dev: overrides come from the URL fragment
  `#code=...&host=127.0.0.1&port=24433` (stripped from the address bar on load).
- Certificate: self-signed, verified with `serverCertificateHashes`. The page
  computes the hash itself from the pairing code (see "Certificate" below).
  Pass hashes for **this week and last week** (the server rotates on Monday
  00:00 UTC; a page whose clock is slightly off still connects).
- One session at a time; a new session replaces the old one.

## Streams

1. **Control**: the page opens ONE bidirectional stream right after
   `ready`. Both directions: UTF-8 JSON objects, one per line (`\n`).
   Lines the page sends longer than 16 KiB close the session (phone -> page
   lines can be larger: the apps list carries icons). The first exchange on it is
   authentication (below); nothing else is accepted or sent before it.
2. **Video**: phone opens one unidirectional stream per encoded frame. Stream
   body = 24-byte header + payload. Big-endian:

   | offset | size | field |
   |---|---|---|
   | 0 | 1 | kind: `1` = video |
   | 1 | 1 | flags: bit0 `1` = keyframe (other bits reserved, 0) |
   | 2 | 2 | pane id (u16) |
   | 4 | 4 | seq (u32, per pane, starts at 0 per session) |
   | 8 | 8 | pts in microseconds (u64, encoder clock) |
   | 16 | 8 | send time in microseconds since session start (u64, phone clock) |
   | 24 | .. | Annex B access unit (H.264 or HEVC per the `pane` message) |

   Keyframes start with SPS/PPS (and VPS for HEVC), so the decoder can be
   (re)configured from any keyframe. Streams may complete out of order
   (keyframes are larger and finish later than the deltas after them); the
   page buffers frames by seq per pane, also while waiting for a keyframe,
   and starts decoding at the keyframe, discarding anything older. seq wraps
   at 2^32 (compare with serial-number arithmetic). The phone never sends a
   delta after a gap without a keyframe first; after a gap the page waits for
   the next keyframe. Phone sends frames oldest first (keyframes may be
   prioritized).

   Header golden vector (hex, 24 bytes): kind 1, flags 1, pane 0x0102, seq
   0xFFFFFFFE, pts 2^40+5, send 123456789 ->
   `01 01 0102 fffffffe 0000010000000005 00000000075bcd15`.
3. **Datagrams**: anything the page sends is echoed back unchanged. Page sends
   a 8-byte little-endian f64 `performance.now()` every 20 ms (RTT + Wi-Fi
   keepalive).

## Authentication (required, before anything else)

The certificate only proves the phone to the page. The page proves it knows
the pairing code:

1. Page opens the control stream and writes nothing yet.
2. Phone writes `{"t":"challenge","v":2,"nonce":"<base64 of 32 random bytes>"}`.
3. Page writes `{"t":"auth","mac":"<base64>"}` where
   - `K = SHA-256("carlink-auth-key-v1:" + normalizedCode)` (32 bytes; a
     different label from the certificate key)
   - `mac = HMAC-SHA256(K, "carlink-auth-v1|" || nonceBytes)` (ASCII label
     bytes followed by the 32 raw nonce bytes)
4. Phone verifies (constant time) and writes `{"t":"authok"}`; then the page
   sends `hello` etc. On failure the phone closes the session (code 1, reason
   `auth`) and delays further attempts from that address (1 s doubling to 60 s).
5. Timeouts: 5 s for each step. Datagram echo and video start only after
   `authok`.

Test vector: code `abcd-efgh-jklm`, nonce = bytes 0..31 (`00 01 02 ... 1f`)
-> mac (hex) `6b5183c6e69c925ba2965bdb3a9b95730378219ba4f8fb84147ca63b33aaf1ad`.

The phone accepts sessions only from the car hotspot subnet (`9.9.0.0/24`) in
normal use; dev builds may allow any address.

## Control messages

Page -> phone:

```json
{"t":"hello","v":2,"w":1920,"h":1200,"dpr":1,"codecs":["h264","hevc"],"icons":"hash"}
{"t":"layout","panes":[{"id":0,"w":1824,"h":1200,"dpi":200}]}
{"t":"apps"}
{"t":"app-icons","pkgs":["com.waze","com.spotify.music"]}
{"t":"launch","pane":0,"pkg":"com.google.android.apps.maps","cls":"com.google.android.maps.MapsActivity"}
{"t":"touch","pane":0,"a":"down","id":0,"x":512.0,"y":300.5,"ts":81234.5}
{"t":"key","pane":0,"k":"back"}
{"t":"keyframe","pane":0}
{"t":"notif-open","id":"n3f9a1c"}
{"t":"close","pane":0,"pkg":"com.waze"}
{"t":"close-all","pane":0}
```

- `touch.ts`: when the touch happened (page clock, ms, optional). The phone
  anchors it at each gesture's first touch and stamps the injected events
  with it, so network jitter doesn't make a steady drag uneven (never ahead
  of the phone's clock; re-anchored after a 150 ms hiccup).
- `touch.a`: `down` | `move` | `up` | `cancel`. `id` = pointer id
  (`PointerEvent.pointerId` mapped to small ints 0..9). x/y in pane pixels
  (pane canvas coordinates, i.e. video pixels).
- `key.k`: `back` | `home` (home = page shows its launcher; the phone may
  ignore it).
- Limits enforced by the phone: up to 4 panes (ids 0-3), w/h 320-4096 (even),
  dpi 72-640, pointer ids 0-9, `launch` only for apps from the `apps` list.
  Anything else is answered with `{"t":"error","msg":"..."}` (generic text).
- `close` / `close-all`: remove that app's tasks (or every task) from the
  pane's display, like swiping them away in recents; no force-stop, so
  notifications and background work continue. The page brings up the next
  app first (a plain `launch`), so nothing else flashes on the car.
- `notif-open`: the driver tapped a notification banner; the phone opens it
  on pane 0 (the app's content intent, or the app itself). Unknown ids are
  ignored.
- `layout.dpi`: biggest UI that keeps apps in tablet layout (shortest side ~640 dp, 200-260 x devicePixelRatio);
  a change re-creates the pane (apps re-layout for the new density).
- `layout`: the page decides pane sizes (main area = screen minus the app
  sidebar). Width and height must be even. The phone (re)creates one virtual
  display + encoder per pane.

Phone -> page:

```json
{"t":"hello","v":2,"device":"Pixel 9 Pro","android":36}
{"t":"pane","id":0,"w":1824,"h":1200,"codec":"h264"}
{"t":"apps","list":[{"pkg":"...","cls":"...","label":"Maps","cat":"nav","ih":"5f3a9c1e-4180"}]}
{"t":"app-icons","list":[{"pkg":"...","ih":"5f3a9c1e-4180","icon":"data:image/png;base64,..."}]}
{"t":"launched","pane":0,"pkg":"..."}
{"t":"error","msg":"..."}
{"t":"notif","id":"n3f9a1c","pkg":"com.whatsapp","app":"WhatsApp","title":"Alice","text":"On my way","icon":"data:image/png;base64,...","kind":"msg"}
{"t":"notif-gone","id":"n3f9a1c"}
{"t":"notif-reset"}
```

- `apps`: if the page's `hello` had `icons: "hash"`, no icons, only `ih`, a
  short hash of each icon (otherwise icons inline, as before). The page keeps
  icons (IndexedDB) and asks for missing or changed ones with `app-icons`
  (up to 400 packages) once video is running; the phone answers in batches
  of 16, 40 ms apart, so the first frames get the link.
- `apps.cat`: launcher group, `nav` | `energy` | `music` | `video` | `social` | `work` | `other`
  (known apps first, then the app's declared category). Missing or unknown
  = `other`. The page shows the groups in that order.
- `notif.replay`: true on the re-sends after a reconnect (calls in progress and
  notifications under 5 minutes old); the page skips ones the driver already
  opened or dismissed.
- `notif-reset`: drop every shown notification (banners and tray): the phone
  re-syncs, or notification access was turned off.
- `notif`: a phone notification worth showing in the car (new or updated;
  same `id` replaces the earlier one). `kind`: `msg` | `call` | `nav` |
  `other`. Limits: `id` up to 64 chars of `[A-Za-z0-9_-]`, `title` up to 120,
  `text` up to 300 (either may be empty), `icon` (optional) up to 16 KB.
  Ongoing, silent, media and TSLink's own notifications are not sent.
  `notif-gone`: dismissed or cancelled on the phone. After the phone's
  `hello` it re-sends active calls and notifications from the last 5
  minutes (up to 10); the page clears
  its banners on each new session. Length limits count UTF-16 code units on
  the phone; the page also truncates (by code point) and accepts icons up to
  16384 characters. `app` up to 60 chars.
- `pane` gives the size actually encoded. It can be smaller than the layout
  asked for (same aspect ratio, aligned) when the phone's encoder can't do
  the requested size; the page scales the video and maps touches to it.
- On each new session the page re-sends `launch` for the app it last showed,
  so a restarted phone engine resumes it.

## Certificate (must match `core/src/cert.rs` byte for byte)

- Code normalization: keep ASCII letters/digits, uppercase.
  `"abcd-efgh-jklm"` -> `"ABCDEFGHJKLM"`.
- Private key: `d = SHA-256("carlink-wt-key-v1:" + normalizedCode)` as a
  big-endian P-256 scalar. If `d == 0` or `d >= n`, `d = SHA-256(d)` and repeat.
- Week: `notBefore` = Monday 00:00:00 UTC of the current week (Unix seconds
  `345600 + floor((t - 345600) / 604800) * 604800`; 345600 = Monday 1970-01-05); `notAfter` = notBefore +
  13 days.
- DER (all lengths minimal DER; long form `0x81 nn` / `0x82 nn nn` when >= 128):

```
Certificate ::= SEQUENCE {
  tbs SEQUENCE {
    [0] EXPLICIT INTEGER 2                     -> A0 03 02 01 02
    serial INTEGER notBefore (unsigned, minimal, leading 00 if high bit set)
    SEQUENCE { OID 1.2.840.10045.4.3.2 }       -> 30 0A 06 08 2A 86 48 CE 3D 04 03 02
    issuer  SEQUENCE { SET { SEQUENCE { OID 2.5.4.3 (06 03 55 04 03), UTF8String "carlink" (0C 07 ...) } } }
    validity SEQUENCE { UTCTime notBefore, UTCTime notAfter }   (UTCTime "YYMMDDHHMMSSZ", tag 17)
    subject = same bytes as issuer
    SubjectPublicKeyInfo SEQUENCE {
      SEQUENCE { OID 1.2.840.10045.2.1 (06 07 2A 86 48 CE 3D 02 01), OID 1.2.840.10045.3.1.7 (06 08 2A 86 48 CE 3D 03 01 07) }
      BIT STRING 00 || 04 || X(32) || Y(32)
    }
    [3] EXPLICIT SEQUENCE {                    -> A3 len 30 len ...  (Chromium rejects certs without extensions)
      SEQUENCE { OID 2.5.29.15 (06 03 55 1D 0F), BOOLEAN TRUE (01 01 FF), OCTET STRING { BIT STRING 03 02 07 80 } }   keyUsage: digitalSignature
      SEQUENCE { OID 2.5.29.37 (06 03 55 1D 25), OCTET STRING { SEQUENCE { OID 1.3.6.1.5.5.7.3.1 (06 08 2B 06 01 05 05 07 03 01) } } }   extKeyUsage: serverAuth
      SEQUENCE { OID 2.5.29.19 (06 03 55 1D 13), BOOLEAN TRUE (01 01 FF), OCTET STRING { SEQUENCE {} (30 00) } }   basicConstraints
    }
  }
  SEQUENCE { OID 1.2.840.10045.4.3.2 }
  BIT STRING 00 || DER(ECDSA-Sig-Value SEQUENCE { INTEGER r, INTEGER s })
}
```

- Signature: ECDSA P-256 over SHA-256(tbs DER), deterministic nonce (RFC
  6979), **low-S normalized**. r/s as minimal DER INTEGERs.
- Hash passed to `serverCertificateHashes` = SHA-256 of the full certificate DER.

**Test vector**: code `"abcd-efgh-jklm"`, time `1794744000`
(2026-11-15 12:00 UTC, week from 2026-11-09) -> hash
`2fdf659c522b8e77a8d5d86953e1a0b67a313cd615cd5ba265136e5a11486009`.

**Fingerprint** (for comparing both sides by eye, e.g. the page's `?debug=1`
overlay): the first 6 lowercase hex chars of this week's hash (current UTC
time). Test vector above -> `2fdf65`. A mismatch means a different code or a
different week (clock off).

## Pairing with a QR code

1. The car page makes a one-time secret (20 characters from the pairing-code
   alphabet, 100 bits) and shows `TSLINK-PAIR:<secret>` as a QR code.
2. The phone app scans it and calls `IEngine.startPairing(secret)`: for up to
   2 minutes the engine runs with the secret in place of the pairing code (same
   certificate derivation and challenge/response, so the car must have seen
   the QR code to connect).
3. The page keeps connecting with the secret. The first authenticated session
   gets `{"t":"paired","code":"ABCD-EFGH-JKLM"}` and nothing else is served.
4. The page stores the code and the address it connected to; the engine goes
   back to the normal code about 1.5 s later and the page reconnects with it.

The secret is exactly 20 characters from the alphabet; the phone refuses
anything else. While pairing, the engine relays nothing but `paired` (no
notifications, media or apps). The phone app then shows the address of the
car that got the code: if the car didn't connect, someone else on the hotspot
who saw the QR code may have raced it, and "New code" revokes it.

Typing the code on the page stays available ("Type the code instead").

## Now playing

Phone to car, whenever the phone's current media session changes (and after
`hello`):

```
{"t":"media","pkg":"com.spotify.music","app":"Spotify","title":"Song","artist":"Band","playing":true,"prev":true,"next":true,"art":"data:image/jpeg;base64,..."}
{"t":"media","none":true}
```

Limits: title and artist 120 characters, app 60, `art` a JPEG or PNG data URL
of at most 32 KB (160 px). `prev`/`next`: whether the player supports them.

Car to phone: `{"t":"media-cmd","a":"play"}` with `a` one of `play`, `pause`,
`next`, `prev`. The phone app runs it on the media session (it reads sessions
with its notification access); without notification access nothing is sent.


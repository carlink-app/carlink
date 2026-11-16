# QR pairing over one waiting connection (design)

## Why

Chrome throttles WebTransport against port scanning: every failed handshake
counts against the page for 5 minutes, and each new attempt waits
10 ms x 2^(failures-1), up to 60 s, before it is even sent. While the QR code
is up, the car page retries every second with a secret the phone doesn't
know yet, so every try fails. After ~10-15 s of QR the page has throttled
itself and pairing stalls until a reload (seen twice in QA, 2026-11-16).

## Goal

The car page opens one connection that succeeds at once and waits on it
until the phone scans. No failed handshakes while waiting; pairing completes
a moment after the scan; no engine restarts.

## Non-goals

- No change to the normal connection (port 4433, code-derived certificate,
  HMAC challenge).
- Built-in pairing without a QR code stays parked.

## Approaches

1. **Pairing port with a public certificate, code sealed with the secret
   (chosen).** The engine also listens on port 4434 with a certificate
   derived from a fixed public string, so any page can complete the
   handshake. Secrecy comes from the QR secret, not TLS.
2. Slow retries while the QR is up (every 5-8 s). Rejected: pairing 5-10 s
   again, still stalls after minutes.
3. Fresh worker/iframe per attempt to reset Chrome's counter. Rejected:
   sidesteps a browser security feature.

## Design

**Keys.** From the 20-character secret S (100 bits): HKDF-SHA256(ikm = S,
salt = "tslink-pair-v1") gives `idKey` and `boxKey` (info "id" / "box").
`tag = base64url(HMAC-SHA256(idKey, "tslink pair"))`.

**Engine (core, Rust).** While running, a second endpoint on `port + 1`
(4434), same allow list, certificate from `cert::make("TSLINK-PAIRING")`
(weekly like the normal one; its hash is public).
- A session sends one line `{"t":"pair","tag":...}` within 10 s, then waits.
  At most 8 waiting sessions; each is closed after 3 minutes.
- `nativePair(secret, code)` (on scan): computes tag and box; for 2 minutes
  the first session with that tag, already waiting or arriving later, gets
  `{"t":"paired","nonce":...,"box":...}` with
  box = AES-256-GCM(boxKey, nonce, code). Then that pairing is done:
  `on_paired(remote)` to Kotlin (the app's "Paired with the car at ...").
- Nothing else on that port: no apps, no frames, no other messages.

**Phone (Kotlin).** `startPairing(secret)` calls `nativePair` on the running
engine instead of swapping engines. The handover engine mode goes.

**Car page.** While the QR is up: connect to `host:port+1` with the public
certificate hash, send the tag, wait. On `paired`: decrypt with WebCrypto
(a box that doesn't decrypt is ignored), save the code, close, connect to
4433 as usual. If the pairing connection fails (phone off or unreachable),
retry with backoff and show the "Can't reach the phone" hint.

**Security.**
- An attacker on the hotspot can complete TLS on 4434 (public certificate)
  and can man-in-the-middle it. Without S it can't make the tag's owner
  match, can't open the box, and can't forge a box the page accepts
  (AES-GCM). Replaying a seen tag gets it a box it can't open.
- The secret never crosses the network; the tag is a one-way function of it.
- Port 4434 serves nothing but the sealed code, only to the car subnet,
  with a cap on waiting sessions.

**Reconnect backoff (separate, small).** Normal retries cap at 30 s instead
of 5 s, so an outage of minutes earns at most ~10 failures per 5 minutes
(~5 s throttle) instead of the 60 s maximum.

## Left to the builder

Message names, error texts, exact limits within the ones above, test vectors
shared between Rust and TypeScript for tag and box.

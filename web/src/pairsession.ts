// QR pairing over one waiting connection (PROTOCOL.md "Pairing with a QR
// code"; core/src/pair.rs is the phone's side). The secret only ever travels
// as the QR code; from it the page derives the tag it waits under and the key
// that opens the pairing code the phone seals for it.

import { hkdf } from '@noble/hashes/hkdf.js';
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { fromBase64, toBase64 } from './auth';
import { certHashes, weekStart } from './cert';
import { withTimeout } from './connection';
import { endpoint } from './settings';

const enc = new TextEncoder();
const SALT = enc.encode('tslink-pair-v1');

/** The pairing port's certificate "code" (public: secrecy comes from the secret). */
export const PAIR_CERT_NAME = 'TSLINK-PAIRING';

const derive = (secret: string, info: string) => hkdf(sha256, enc.encode(secret), SALT, enc.encode(info), 32);

const b64url = (b: Uint8Array) => toBase64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Bytes from base64url, or null if it isn't. */
function fromB64url(s: string): Uint8Array<ArrayBuffer> | null {
  try {
    return new Uint8Array(fromBase64(s.replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null; // not base64
  }
}

/** Which waiting connection is ours, for the phone (one-way: reveals nothing of the secret). */
export function pairTag(secret: string): string {
  return b64url(hmac(sha256, derive(secret, 'id'), enc.encode('tslink pair')));
}

/** The pairing code the phone sealed for this secret, or null if it isn't one (wrong secret, changed, garbage). */
export async function openBox(secret: string, nonce: string, sealed: string): Promise<string | null> {
  const iv = fromB64url(nonce);
  const data = fromB64url(sealed);
  if (!iv || iv.length !== 12 || !data) return null;
  try {
    const key = await crypto.subtle.importKey('raw', new Uint8Array(derive(secret, 'box')), 'AES-GCM', false, ['decrypt']);
    return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, data));
  } catch {
    return null;
  }
}

/** Waits between tries when the phone isn't there: few failed handshakes for Chrome to count. */
const RETRY_MS = [2000, 4000, 8000, 15000, 30000];
/** A handshake that hasn't finished by then won't (phone off or elsewhere). */
const READY_MS = 8000;

export interface PairHooks {
  host: string;
  port: number; // the normal port; pairing is on port + 1
  secret: string;
  /** The pairing code, once the phone scanned and sealed it for us. */
  onCode(code: string): void;
  /** Tries in a row the phone didn't take (0 once it does): for the "can't reach" hint. */
  onUnanswered(n: number): void;
}

/**
 * While the QR code is up: one connection to the phone's pairing port that
 * succeeds at once and waits for the scan. Failed handshakes would make
 * Chrome throttle the page (5 min memory, up to 60 s per attempt), so only
 * a phone that isn't there costs one, with growing pauses between.
 */
export class PairSession {
  private running = false;
  private gen = 0;
  private wt: WebTransport | null = null;
  private wake: (() => void) | null = null;

  /** One-way, so computed once; the certificate hashes once per week (two ECDSA certificates). */
  private readonly tag: string;
  private hashes: { week: number; list: Uint8Array[] } | null = null;

  constructor(private hooks: PairHooks) {
    this.tag = pairTag(hooks.secret);
  }

  private certHashes(): Uint8Array[] {
    const now = Math.floor(Date.now() / 1000);
    const week = weekStart(now);
    if (this.hashes?.week !== week) this.hashes = { week, list: certHashes(PAIR_CERT_NAME, now) };
    return this.hashes.list;
  }

  start() {
    if (this.running) return;
    this.running = true;
    void this.loop(++this.gen);
  }

  stop() {
    this.running = false;
    this.gen++;
    try { this.wt?.close(); } catch { /* closed */ }
    this.wt = null;
    this.wake?.();
  }

  private async loop(gen: number) {
    const live = () => this.running && gen === this.gen;
    let misses = 0;
    while (live()) {
      let code: string | null = null;
      let answered = false;
      try {
        code = await this.attempt(() => { answered = true; misses = 0; this.hooks.onUnanswered(0); });
      } catch {
        if (!live()) return;
        if (answered) { // the phone was there; the connection just dropped: wait again
          await this.sleep(1000);
          continue;
        }
        misses++;
        this.hooks.onUnanswered(misses);
        await this.sleep(RETRY_MS[Math.min(misses, RETRY_MS.length) - 1]);
        continue;
      }
      if (!live()) return;
      if (code) {
        this.stop();
        this.hooks.onCode(code);
        return;
      }
      // The phone let us go (no scan for minutes): wait again, the handshake succeeds.
      await this.sleep(1000);
    }
  }

  /** One wait on the pairing port: the code, or null if the phone closed without one. Throws if it can't connect. */
  private async attempt(connected: () => void): Promise<string | null> {
    const { host, port } = this.hooks;
    const hashes = this.certHashes();
    const wt = new WebTransport(`${endpoint({ host, port: port + 1 })}/pair`, {
      serverCertificateHashes: hashes.map((h) => ({ algorithm: 'sha-256', value: h as BufferSource })),
    });
    this.wt = wt;
    try {
      await withTimeout(wt.ready, READY_MS, 'timed out');
      connected();
      console.info(`pairing: waiting on ${host}:${port + 1} (tag ${this.tag.slice(0, 6)})`);
      const bidi = await wt.createBidirectionalStream();
      const writer = bidi.writable.getWriter();
      await writer.write(enc.encode(JSON.stringify({ t: 'pair', tag: this.tag }) + '\n'));
      const reader = bidi.readable.getReader();
      const dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) return null;
        buf += dec.decode(value, { stream: true });
        if (buf.length > 4096) return null; // the phone sends one short line
        let nl: number;
        while ((nl = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, nl);
          buf = buf.slice(nl + 1);
          const code = await this.codeFrom(line);
          if (code) return code;
        }
      }
    } finally {
      try { wt.close(); } catch { /* closed */ }
      if (this.wt === wt) this.wt = null;
    }
  }

  /** The code from a `paired` line, if its box opens with our secret (a forged one doesn't). */
  private async codeFrom(line: string): Promise<string | null> {
    let m: unknown;
    try { m = JSON.parse(line); } catch { return null; }
    if (typeof m !== 'object' || m === null) return null;
    const { t, nonce, box } = m as Record<string, unknown>;
    if (t !== 'paired' || typeof nonce !== 'string' || typeof box !== 'string') return null;
    return openBox(this.hooks.secret, nonce, box);
  }

  private sleep(ms: number) {
    return new Promise<void>((resolve) => {
      const done = () => { this.wake = null; clearTimeout(t); resolve(); };
      const t = setTimeout(done, ms);
      this.wake = done;
    });
  }
}

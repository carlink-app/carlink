// WebTransport session to the phone: control stream (JSON lines, auth first),
// video streams (one unidirectional stream per frame), datagram pings.
// Reconnects forever with a short backoff (not at all without WebTransport).

import { authMac, fromBase64, toBase64 } from './auth';
import { certHashes } from './cert';
import type { Codec } from './h26x';
import { LineSplitter } from './lines';
import { PATH, PROTOCOL_VERSION, type PageMsg, type PaneLayout, type PhoneMsg } from './protocol';
import { endpoint, type Settings } from './settings';

export interface Status { s: 'idle' | 'connecting' | 'connected' | 'error'; text: string }

export interface ConnectionHooks {
  settings(): Settings;
  layout(): PaneLayout[];
  codecs(): Promise<Codec[]>;
  onStatus(st: Status): void;
  onMessage(m: PhoneMsg): void;
  /** A new session is authenticated: seq numbers restart. Called before any frame of that session. */
  onSession(): void;
  /** A complete video stream. `buf` is owned by the receiver (transferable). */
  onFrame(buf: ArrayBuffer): void;
  /** A video stream that ended with an error; `buf` holds whatever arrived. */
  onLost(buf: ArrayBuffer): void;
  onRtt(ms: number): void;
}

const PING_MS = 20;
const READY_TIMEOUT_MS = 8000;
const AUTH_STEP_TIMEOUT_MS = 5000;
/** No ping echo for this long on an open session -> treat it as dead. */
const DEAD_MS = 4000;
const BACKOFF_MIN_MS = 1000;
const BACKOFF_MAX_MS = 5000;
export const REJECTED = 'Pairing code rejected';
export const NO_WEBTRANSPORT = 'This browser has no WebTransport, so TSLink can\'t connect here';

const enc = new TextEncoder();

export class Connection {
  private running = false;
  private wt: WebTransport | null = null;
  private ctrl: WritableStreamDefaultWriter<Uint8Array> | null = null;
  private wake: (() => void) | null = null;
  private authed = false;

  constructor(private hooks: ConnectionHooks) {}

  start() {
    if (this.running) return;
    this.running = true;
    void this.loop();
  }

  /** Drop the current session and reconnect now (e.g. settings changed). */
  restart() {
    if (!this.running) { this.start(); return; }
    this.closeSession();
    this.wake?.();
  }

  /** Send a control message; dropped unless an authenticated session is up. */
  send(m: PageMsg) {
    if (this.authed) this.write(m);
  }

  private write(m: PageMsg) {
    this.ctrl?.write(enc.encode(JSON.stringify(m) + '\n')).catch(() => { /* session closing */ });
  }

  private closeSession() {
    const wt = this.wt;
    this.wt = null;
    this.ctrl = null;
    this.authed = false;
    try { wt?.close(); } catch { /* already closed */ }
  }

  private async loop() {
    // Retrying can't help: say so once instead of "WebTransport is not defined" forever.
    if (typeof WebTransport === 'undefined') {
      this.running = false;
      this.hooks.onStatus({ s: 'error', text: NO_WEBTRANSPORT });
      return;
    }
    let backoff = BACKOFF_MIN_MS;
    while (this.running) {
      let wasConnected = false;
      let why = 'Disconnected';
      try {
        await this.session(this.hooks.settings(), () => { wasConnected = true; });
      } catch (e) {
        why = describe(e);
      } finally {
        this.closeSession();
      }
      if (!this.running) break;
      if (wasConnected) backoff = BACKOFF_MIN_MS;
      this.hooks.onStatus({ s: 'error', text: `${why} · retrying in ${backoff / 1000}s` });
      await this.sleep(backoff);
      backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
    }
  }

  private sleep(ms: number) {
    return new Promise<void>((resolve) => {
      const done = () => { this.wake = null; clearTimeout(t); resolve(); };
      const t = setTimeout(done, ms);
      this.wake = done;
    });
  }

  /** Runs one session; returns or throws when it is over. */
  private async session(s: Settings, markConnected: () => void): Promise<void> {
    if (!s.code) throw new Error('No pairing code');
    this.hooks.onStatus({ s: 'connecting', text: `Connecting to ${s.host}:${s.port}…` });

    const hashes = certHashes(s.code, Math.floor(Date.now() / 1000));
    const wt = new WebTransport(endpoint(s) + PATH, {
      serverCertificateHashes: hashes.map((h) => ({ algorithm: 'sha-256', value: h as BufferSource })),
    });
    this.wt = wt;
    const live = () => this.wt === wt;
    // Closing with reason "auth" means the phone rejected our MAC.
    const closed = wt.closed.then(
      (info) => { throw new Error(info?.reason === 'auth' ? REJECTED : 'Session closed'); },
      (e) => { throw e; },
    );
    closed.catch(() => { /* raced below */ });

    await withTimeout(Promise.race([wt.ready, closed]), READY_TIMEOUT_MS, 'Timed out (phone unreachable?)');
    if (!live()) return;

    // Control stream: the phone speaks first (challenge).
    const bidi = await wt.createBidirectionalStream();
    if (!live()) return;
    this.ctrl = bidi.writable.getWriter();

    // Messages before `authok` go to the handshake; after it, to the app.
    let sentAuth = false;
    const queue: PhoneMsg[] = [];
    const pending: { waiter: ((m: PhoneMsg) => void) | null } = { waiter: null };
    const control = (async () => {
      const r = bidi.readable.pipeThrough(new TextDecoderStream()).getReader();
      const lines = new LineSplitter((line, why) => console.warn(`control: ${why}`, line.slice(0, 200)));
      for (;;) {
        const { value, done } = await r.read();
        if (done) throw new Error(sentAuth && !this.authed ? REJECTED : 'Phone closed the control stream');
        if (!live()) return;
        for (const m of lines.push(value) as PhoneMsg[]) {
          if (this.authed) this.hooks.onMessage(m);
          else if (pending.waiter) pending.waiter(m);
          else queue.push(m);
        }
      }
    })();
    control.catch(() => { /* raced below */ });
    const next = (what: string): Promise<PhoneMsg> => {
      const m = queue.shift();
      if (m) return Promise.resolve(m);
      const got = new Promise<PhoneMsg>((resolve) => { pending.waiter = (x) => { pending.waiter = null; resolve(x); }; });
      return withTimeout(Promise.race([got, control as Promise<never>, closed]), AUTH_STEP_TIMEOUT_MS, `No ${what} from phone`);
    };

    const challenge = await next('challenge');
    if (challenge.t !== 'challenge' || typeof challenge.nonce !== 'string') throw new Error('Unexpected first message from phone');
    let nonce: Uint8Array;
    try { nonce = fromBase64(challenge.nonce); } catch { throw new Error('Bad challenge from phone'); }
    this.write({ t: 'auth', mac: toBase64(authMac(s.code, nonce)) });
    sentAuth = true;
    const ok = await next('auth reply');
    if (ok.t !== 'authok') throw new Error(REJECTED);
    if (!live()) return;

    this.authed = true;
    markConnected();
    this.hooks.onSession();
    const codecs = await this.hooks.codecs();
    this.send({ t: 'hello', v: PROTOCOL_VERSION, w: innerWidth, h: innerHeight, dpr: devicePixelRatio, codecs, icons: 'hash' });
    const panes = this.hooks.layout();
    this.send({ t: 'layout', panes });
    this.send({ t: 'apps' });
    for (const p of panes) this.send({ t: 'keyframe', pane: p.id });
    this.hooks.onStatus({ s: 'connected', text: `Connected to ${s.host}` });
    for (const m of queue.splice(0)) this.hooks.onMessage(m);

    // Datagram pings + echo reader.
    let lastPong = performance.now();
    const dw = wt.datagrams.writable.getWriter();
    const ping = window.setInterval(() => {
      if (!live()) return;
      const b = new Uint8Array(8);
      new DataView(b.buffer).setFloat64(0, performance.now(), true);
      dw.write(b).catch(() => { /* closing */ });
    }, PING_MS);

    const pongs = (async () => {
      const r = wt.datagrams.readable.getReader();
      for (;;) {
        const { value, done } = await r.read();
        if (done || !live()) return;
        if (value.byteLength !== 8) continue;
        const now = performance.now();
        lastPong = now;
        const t = new DataView(value.buffer, value.byteOffset, 8).getFloat64(0, true);
        if (t > 0 && t <= now) this.hooks.onRtt(now - t);
      }
    })();

    const video = (async () => {
      const r = wt.incomingUnidirectionalStreams.getReader();
      for (;;) {
        const { value, done } = await r.read();
        if (done || !live()) return;
        void this.readFrame(value, live);
      }
    })();

    let watchdogTimer = 0;
    const watchdog = new Promise<never>((_, reject) => {
      watchdogTimer = window.setInterval(() => {
        if (live() && performance.now() - lastPong > DEAD_MS) reject(new Error('No response from phone'));
      }, 500);
    });

    try {
      await Promise.race([closed, control, pongs, video, watchdog]);
    } finally {
      clearInterval(ping);
      clearInterval(watchdogTimer);
    }
  }

  private async readFrame(stream: ReadableStream<Uint8Array>, live: () => boolean) {
    const reader = stream.getReader();
    const parts: Uint8Array[] = [];
    let len = 0;
    let failed = false;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        parts.push(value);
        len += value.byteLength;
      }
    } catch {
      failed = true;
    }
    if (!live()) return;
    let buf: ArrayBuffer;
    const one = parts[0];
    if (parts.length === 1 && one.byteOffset === 0 && one.buffer instanceof ArrayBuffer && one.byteLength === one.buffer.byteLength) {
      buf = one.buffer;
    } else {
      const out = new Uint8Array(len);
      let off = 0;
      for (const p of parts) { out.set(p, off); off += p.byteLength; }
      buf = out.buffer;
    }
    if (failed) this.hooks.onLost(buf);
    else this.hooks.onFrame(buf);
  }
}

function withTimeout<T>(p: Promise<T>, ms: number, msg: string): Promise<T> {
  let t = 0;
  return Promise.race([p, new Promise<never>((_, reject) => { t = window.setTimeout(() => reject(new Error(msg)), ms); })])
    .finally(() => clearTimeout(t));
}

function describe(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e);
  if (/handshake|certificate|cert/i.test(msg)) return `${msg} (wrong pairing code, phone not running, or clock off?)`;
  return msg || 'Connection failed';
}

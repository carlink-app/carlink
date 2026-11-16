import { describe, expect, it, vi } from 'vitest';
import { certHashes, toHex } from './cert';
import { openBox, PAIR_CERT_NAME, pairTag, PairSession } from './pairsession';

// Shared with core/src/pair.rs (tests::vectors): both sides must agree byte for byte.
const SECRET = 'ABCDEFGHJKLMNPQRSTUV';

describe('pairing session crypto', () => {
  it('pins the same pairing-port certificate as the phone', () => {
    expect(toHex(certHashes(PAIR_CERT_NAME, 1_794_744_000)[0])).toBe('0c2ac1440d283f05b7f79588b4f05ecb70768c4d0222e16d65511c831ec8f2c0');
  });

  it('derives the same tag as the phone', () => {
    expect(pairTag(SECRET)).toBe('1GThOC0M3ImFQlqwf34TZ95_rS9_9vP9uzu310KBHW4');
  });

  it('opens the code the phone sealed', async () => {
    expect(await openBox(SECRET, 'AAAAAAAAAAAAAAAA', 'SifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d')).toBe('ABCD-EFGH-JKLM');
  });

  it('refuses a box for another secret or a changed one', async () => {
    expect(await openBox('ABCDEFGHJKLMNPQRSTUW', 'AAAAAAAAAAAAAAAA', 'SifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d')).toBeNull();
    expect(await openBox(SECRET, 'AAAAAAAAAAAAAAAA', 'TifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d')).toBeNull();
    expect(await openBox(SECRET, 'not base64!', 'x')).toBeNull();
  });
});

describe('PairSession', () => {
  const enc = new TextEncoder();
  const PAIRED = '{"t":"paired","nonce":"AAAAAAAAAAAAAAAA","box":"SifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d"}\n';

  /**
   * A fake WebTransport: each attempt plays the next script: 'fail' (no
   * handshake), lines the phone sends (then it waits), or 'closed' (the
   * handshake works, then the phone closes without a code).
   */
  function fakeTransport(scripts: ('fail' | 'closed' | 'drop' | string[])[], log: { url: string; wrote: string }[]) {
    return class {
      ready: Promise<void>;
      closed = new Promise<never>(() => { /* open until close() */ });
      private script: 'fail' | 'closed' | 'drop' | string[];
      constructor(url: string) {
        this.script = scripts.shift() ?? 'fail';
        log.push({ url, wrote: '' });
        this.ready = this.script === 'fail' ? Promise.reject(new Error('Opening handshake failed.')) : Promise.resolve();
        this.ready.catch(() => { /* awaited by the session */ });
      }
      async createBidirectionalStream() {
        const closed = this.script === 'closed';
        const drop = this.script === 'drop';
        const lines = Array.isArray(this.script) ? this.script : [];
        const entry = log[log.length - 1];
        return {
          writable: new WritableStream<Uint8Array>({ write(c) { entry.wrote += new TextDecoder().decode(c); } }),
          readable: new ReadableStream<Uint8Array>({
            start(c) {
              for (const l of lines) c.enqueue(enc.encode(l));
              if (closed) c.close(); // else stays open: the phone waits
              if (drop) c.error(new Error('connection lost'));
            },
          }),
        };
      }
      close() { /* nothing */ }
    };
  }

  async function run(scripts: ('fail' | 'closed' | 'drop' | string[])[], ms: number) {
    const log: { url: string; wrote: string }[] = [];
    const codes: string[] = [];
    const misses: number[] = [];
    vi.useFakeTimers();
    vi.stubGlobal('WebTransport', fakeTransport(scripts, log));
    vi.stubGlobal('window', globalThis);
    try {
      const s = new PairSession({ host: '9.9.0.1', port: 4433, secret: SECRET, onCode: (c) => codes.push(c), onUnanswered: (n) => misses.push(n) });
      s.start();
      await vi.advanceTimersByTimeAsync(ms);
      // WebCrypto answers on real time, not the fake clock.
      vi.useRealTimers();
      await new Promise((r) => setTimeout(r, 100));
      s.stop();
    } finally {
      vi.unstubAllGlobals();
      vi.useRealTimers();
    }
    return { log, codes, misses };
  }

  it('waits on the pairing port with its tag and takes the sealed code', async () => {
    const r = await run([[PAIRED]], 100);
    expect(r.codes).toEqual(['ABCD-EFGH-JKLM']);
    expect(r.log[0].url).toBe('https://9.9.0.1:4434/pair');
    expect(JSON.parse(r.log[0].wrote)).toEqual({ t: 'pair', tag: pairTag(SECRET) });
    expect(r.log.length).toBe(1);
  });

  it('ignores a box that does not open and keeps waiting', async () => {
    const bad = '{"t":"paired","nonce":"AAAAAAAAAAAAAAAA","box":"TifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d"}\n';
    const r = await run([[bad, PAIRED]], 100);
    expect(r.codes).toEqual(['ABCD-EFGH-JKLM']);
  });

  it('backs off when the phone is not there, and says so', async () => {
    const r = await run(['fail', 'fail', 'fail', [PAIRED]], 20_000);
    // Tries at 0, 2, 6 s (2 s, then 4 s apart): few failed handshakes for Chrome to count.
    expect(r.log.length).toBe(4);
    expect(r.misses).toEqual([1, 2, 3, 0]);
    expect(r.codes).toEqual(['ABCD-EFGH-JKLM']);
  });

  it('waits again when the phone lets it go without a code (not a miss)', async () => {
    const r = await run(['closed', [PAIRED]], 3000);
    expect(r.log.length).toBe(2);
    expect(r.misses).toEqual([0, 0]);
    expect(r.codes).toEqual(['ABCD-EFGH-JKLM']);
  });

  it('a connection that drops after the phone answered is not "can\'t reach"', async () => {
    const r = await run(['drop', 'drop', [PAIRED]], 5000);
    expect(r.misses).toEqual([0, 0, 0]);
    expect(r.codes).toEqual(['ABCD-EFGH-JKLM']);
  });
});

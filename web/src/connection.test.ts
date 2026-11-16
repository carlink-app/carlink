import { describe, expect, it, vi } from 'vitest';
import { Connection, NO_WEBTRANSPORT, type ConnectionHooks, type Status } from './connection';

describe('Connection without WebTransport', () => {
  it('says so once instead of retrying forever', async () => {
    expect(typeof WebTransport).toBe('undefined'); // node has none
    const statuses: Status[] = [];
    const hooks: ConnectionHooks = {
      settings: () => ({ code: 'ABCD-EFGH-JKLM', host: '9.9.0.1', port: 4433 }),
      layout: () => [],
      codecs: async () => ['h264'],
      onStatus: (st) => statuses.push(st),
      onMessage: vi.fn(), onSession: vi.fn(), onFrame: vi.fn(), onLost: vi.fn(), onRtt: vi.fn(),
    };
    const c = new Connection(hooks);
    c.start();
    await new Promise((r) => setTimeout(r, 20));
    expect(statuses).toEqual([{ s: 'error', text: NO_WEBTRANSPORT }]);
    // restart() (settings saved) re-checks and reports again, still without a retry loop.
    c.restart();
    await new Promise((r) => setTimeout(r, 20));
    expect(statuses.length).toBe(2);
    expect(hooks.onSession).not.toHaveBeenCalled();
  });
});

describe('Connection retry pacing', () => {
  /** A WebTransport that fails at once; records when each attempt started. */
  function failingTransport(attempts: number[]) {
    return class {
      ready: Promise<void>;
      closed = new Promise<never>(() => { /* never closes: ready fails first */ });
      constructor() {
        attempts.push(Date.now());
        this.ready = Promise.reject(new Error('unreachable'));
        this.ready.catch(() => { /* raced in session() */ });
      }
      close() { /* nothing open */ }
    };
  }

  /** A WebTransport whose handshake never finishes (the phone was switching): only a timeout ends it. */
  function hangingTransport(attempts: number[]) {
    return class {
      ready = new Promise<void>(() => { /* no answer */ });
      closed = new Promise<never>(() => { /* never */ });
      constructor() { attempts.push(Date.now()); }
      close() { /* nothing open */ }
    };
  }

  function hooks(): ConnectionHooks {
    return {
      settings: () => ({ code: 'ABCD-EFGH-JKLM', host: '9.9.0.1', port: 4433 }),
      layout: () => [],
      codecs: async () => ['h264'],
      onStatus: vi.fn(), onMessage: vi.fn(), onSession: vi.fn(), onFrame: vi.fn(), onLost: vi.fn(), onRtt: vi.fn(),
    };
  }

  async function attemptGaps(h: ConnectionHooks, ms: number, transport = failingTransport) {
    const attempts: number[] = [];
    vi.useFakeTimers();
    vi.stubGlobal('WebTransport', transport(attempts));
    vi.stubGlobal('window', globalThis); // withTimeout uses window.setTimeout
    try {
      const c = new Connection(h);
      c.start();
      await vi.advanceTimersByTimeAsync(ms);
      c.stop();
      return attempts.slice(1).map((t, i) => t - attempts[i]);
    } finally {
      vi.unstubAllGlobals();
      vi.useRealTimers();
    }
  }

  it('backs off 1, 2, 4 ... up to 30 s', async () => {
    expect(await attemptGaps(hooks(), 7500)).toEqual([1000, 2000, 4000]);
    // Long outage: few failed handshakes for Chrome to count (it delays pages after many).
    const gaps = await attemptGaps(hooks(), 300_000);
    expect(Math.max(...gaps)).toBe(30_000);
    expect(gaps.length).toBeLessThanOrEqual(13);
  });

  it('an unanswered handshake waits the full 8 s', async () => {
    expect(await attemptGaps(hooks(), 9200, hangingTransport)).toEqual([9000]);
  });
});

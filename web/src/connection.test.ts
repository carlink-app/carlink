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

  function hooks(retryMs?: () => number | undefined): ConnectionHooks {
    return {
      settings: () => ({ code: 'ABCD-EFGH-JKLM', host: '9.9.0.1', port: 4433 }),
      layout: () => [],
      codecs: async () => ['h264'],
      onStatus: vi.fn(), onMessage: vi.fn(), onSession: vi.fn(), onFrame: vi.fn(), onLost: vi.fn(), onRtt: vi.fn(),
      retryMs,
    };
  }

  async function attemptGaps(h: ConnectionHooks, ms: number) {
    const attempts: number[] = [];
    vi.useFakeTimers();
    vi.stubGlobal('WebTransport', failingTransport(attempts));
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

  it('retryMs overrides backoff', async () => {
    expect(await attemptGaps(hooks(() => 1000), 3500)).toEqual([1000, 1000, 1000]);
  });

  it('retryMs undefined uses backoff', async () => {
    expect(await attemptGaps(hooks(() => undefined), 7500)).toEqual([1000, 2000, 4000]);
  });
});

import { describe, expect, it, vi } from 'vitest';
import { Connection, NO_WEBTRANSPORT, type ConnectionHooks, type Status } from './connection';

describe('Connection without WebTransport', () => {
  it('says so once instead of retrying forever', async () => {
    expect(typeof WebTransport).toBe('undefined'); // node has none
    const statuses: Status[] = [];
    const hooks: ConnectionHooks = {
      settings: () => ({ code: 'ABCD-EFGH-JKLM', host: '9.9.0.1', port: 4433, showText: true }),
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

import { describe as group, expect, it } from 'vitest';
import { describe, parsePhoneStatus } from './phonestatus';

group('phone status', () => {
  it('reads the message', () => {
    const s = parsePhoneStatus({ t: 'phone', bat: 80, chg: true, sig: 3, net: 'LTE' });
    expect(s).toEqual({ battery: 80, charging: true, signal: 3, network: 'LTE' });
    expect(describe(s)).toBe('Phone battery 80%, charging. Signal 3 of 4 bars, LTE');
  });
  it('rejects nonsense', () => {
    expect(parsePhoneStatus({ bat: 300, sig: 9, net: '<b>', chg: 'yes' })).toEqual({ battery: null, charging: false, signal: null, network: '' });
  });
  it('no service', () => {
    expect(describe(parsePhoneStatus({ sig: -1, bat: 5 }))).toBe('Phone battery 5%. No mobile signal');
  });
  it('keeps the edges and rounds the battery', () => {
    expect(parsePhoneStatus({ bat: 0, sig: 0 })).toMatchObject({ battery: 0, signal: 0 });
    expect(parsePhoneStatus({ bat: 100, sig: 4 })).toMatchObject({ battery: 100, signal: 4 });
    expect(parsePhoneStatus({ bat: 79.6 }).battery).toBe(80);
    expect(parsePhoneStatus({ bat: -1, sig: -2 })).toMatchObject({ battery: null, signal: null });
    expect(parsePhoneStatus({ bat: '80', sig: 2.5 })).toMatchObject({ battery: null, signal: null });
    expect(parsePhoneStatus({ bat: Number.NaN }).battery).toBeNull();
  });
  it('only known network labels', () => {
    for (const net of ['5G', 'LTE', '3G', '2G']) expect(parsePhoneStatus({ net }).network).toBe(net);
    expect(parsePhoneStatus({ net: 'lte' }).network).toBe('');
    expect(parsePhoneStatus({ net: 5 }).network).toBe('');
  });
  it('describes partial status', () => {
    expect(describe(parsePhoneStatus({}))).toBe('');
    expect(describe(parsePhoneStatus({ sig: 0 }))).toBe('Signal 0 of 4 bars');
    expect(describe(parsePhoneStatus({ sig: 2, net: '5G' }))).toBe('Signal 2 of 4 bars, 5G');
    expect(describe(parsePhoneStatus({ bat: 50, chg: false }))).toBe('Phone battery 50%');
    // No service hides the network label.
    expect(describe(parsePhoneStatus({ sig: -1, net: 'LTE' }))).toBe('No mobile signal');
  });
});


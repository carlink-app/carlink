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
});

import { describe, expect, it } from 'vitest';
import { newSecret, pairedCode, QR_PREFIX, qrModules, qrText, SECRET_LENGTH } from './pairing';

describe('pairing', () => {
  it('makes secrets from the code alphabet', () => {
    const s = newSecret();
    expect(s).toHaveLength(SECRET_LENGTH);
    expect(s).toMatch(/^[A-HJ-NP-Z2-9]+$/);
    expect(newSecret((a) => a.fill(0))).toBe('A'.repeat(SECRET_LENGTH));
  });
  it('QR text is what the phone expects', () => {
    expect(qrText('ABC')).toBe(`${QR_PREFIX}ABC`);
    const m = qrModules(qrText(newSecret()));
    expect(m.length).toBeGreaterThanOrEqual(21);
    expect(m.every((row) => row.length === m.length)).toBe(true);
  });
  it('accepts only well-formed codes from the phone', () => {
    expect(pairedCode('abcd-efgh-jkmn')).toBe('ABCD-EFGH-JKMN');
    expect(pairedCode('ABCDEFGHJKMN')).toBe('ABCD-EFGH-JKMN');
    expect(pairedCode('ABCD-EFGH-JKM0')).toBeNull(); // 0 isn't in the alphabet
    expect(pairedCode('ABCD-EFGH')).toBeNull();
    expect(pairedCode(42)).toBeNull();
    expect(pairedCode('<script>')).toBeNull();
  });
});

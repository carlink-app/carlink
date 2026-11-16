import { describe, expect, it } from 'vitest';
import { certHashes, formatCode, makeCert, normalizeCode, toHex, weekStart, WEEK_SECS } from './cert';

describe('cert', () => {
  it('normalizes codes', () => {
    expect(normalizeCode('abcd-efgh-jklm')).toBe('ABCDEFGHJKLM');
    expect(normalizeCode(' ab cd_ef ')).toBe('ABCDEF');
  });

  it('week starts on Monday 00:00 UTC', () => {
    expect(weekStart(1_794_744_000)).toBe(1_794_182_400);
    expect(new Date(1_794_182_400 * 1000).toISOString()).toBe('2026-11-09T00:00:00.000Z');
    expect(weekStart(1_794_182_400)).toBe(1_794_182_400);
    expect(weekStart(1_794_182_399)).toBe(1_794_182_400 - WEEK_SECS);
  });

  it('matches the PROTOCOL.md test vector', () => {
    const c = makeCert('abcd-efgh-jklm', 1_794_744_000);
    expect(toHex(c.hash)).toBe('2fdf659c522b8e77a8d5d86953e1a0b67a313cd615cd5ba265136e5a11486009');
    expect(c.notBefore).toBe(1_794_182_400);
  });

  it('is deterministic and ignores case/separators', () => {
    const a = makeCert('abcd-efgh-jklm', 1_794_744_000);
    const b = makeCert('ABCDEFGHJKLM', 1_794_182_400 + 5);
    expect(toHex(a.der)).toBe(toHex(b.der));
  });

  it('returns this week and last week', () => {
    const [now, prev] = certHashes('abcd-efgh-jklm', 1_794_744_000);
    expect(toHex(now)).toBe('2fdf659c522b8e77a8d5d86953e1a0b67a313cd615cd5ba265136e5a11486009');
    expect(toHex(prev)).toBe(toHex(makeCert('abcd-efgh-jklm', 1_794_744_000 - WEEK_SECS).hash));
    expect(toHex(prev)).not.toBe(toHex(now));
  });
});

describe('formatCode', () => {
  it('upper-cases, drops separators and junk, groups by four', () => {
    expect(formatCode('abcdefghjklm').value).toBe('ABCD-EFGH-JKLM');
    expect(formatCode('ab cd_ef.gh').value).toBe('ABCD-EFGH');
    expect(formatCode('ABCD-').value).toBe('ABCD');
    expect(formatCode('').value).toBe('');
  });
  it('stops at twelve characters', () => {
    expect(formatCode('ABCDEFGHJKLMNPQR').value).toBe('ABCD-EFGH-JKLM');
  });
  it('keeps the caret after the same character', () => {
    // Typing the 5th character at the end: caret after "ABCD-E".
    expect(formatCode('ABCDE', 5)).toEqual({ value: 'ABCD-E', caret: 6 });
    // Caret in the middle (after "ab"): stays after "AB".
    expect(formatCode('abXcdefgh', 2)).toEqual({ value: 'ABXC-DEFG-H', caret: 2 });
    // Pasting a dashed code: caret at the end.
    expect(formatCode('ABCD-EFGH-JKLM', 14)).toEqual({ value: 'ABCD-EFGH-JKLM', caret: 14 });
  });
});

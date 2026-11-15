import { describe, expect, it } from 'vitest';
import { certHashes, makeCert, normalizeCode, toHex, weekStart, WEEK_SECS } from './cert';

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

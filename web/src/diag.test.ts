import { describe, expect, it } from 'vitest';
import { makeCert } from './cert';
import { ErrorWindow, fingerprint, LogRing, maskCode, resolveCode, shortUA, utcStamp } from './diag';

describe('maskCode', () => {
  it('shows only the last 4, grouped', () => {
    expect(maskCode('ABCD-EFGH-XY7Q')).toBe('••••-••••-XY7Q');
    expect(maskCode('abcdefghxy7q')).toBe('••••-••••-XY7Q');
    expect(maskCode('ABCDEF')).toBe('••CD-EF');
  });
  it('masks short codes entirely and handles empty', () => {
    expect(maskCode('AB-CD')).toBe('••••');
    expect(maskCode('X')).toBe('•');
    expect(maskCode('')).toBe('');
    expect(maskCode('--')).toBe('');
  });
  it('never contains the masked part of the code', () => {
    const m = maskCode('ABCD-EFGH-JKLM');
    for (const ch of 'ABCDEFGH') expect(m).not.toContain(ch);
  });
});

describe('resolveCode', () => {
  it('keeps the saved code when the field is empty', () => {
    expect(resolveCode('', 'ABCD-EFGH-JKLM')).toBe('ABCD-EFGH-JKLM');
    expect(resolveCode('   ', 'ABCD-EFGH-JKLM')).toBe('ABCD-EFGH-JKLM');
  });
  it('uses a newly typed code', () => {
    expect(resolveCode(' wxyz-1234-abcd ', 'ABCD-EFGH-JKLM')).toBe('WXYZ-1234-ABCD');
  });
  it('first pairing: nothing saved, nothing typed -> empty (form rejects it)', () => {
    expect(resolveCode('', '')).toBe('');
  });
});

describe('fingerprint', () => {
  it('is the first 6 hex chars of the cert hash (PROTOCOL.md test vector)', () => {
    expect(fingerprint(makeCert('abcd-efgh-jklm', 1_794_744_000).hash)).toBe('2fdf65');
  });
  it('pads bytes', () => {
    expect(fingerprint(new Uint8Array([0, 1, 0xab, 0xff]))).toBe('0001ab');
  });
});

describe('utcStamp', () => {
  it('formats seconds as UTC', () => {
    expect(utcStamp(1_794_744_000)).toBe('2026-11-15 12:00:00Z');
  });
});

describe('shortUA', () => {
  it('leaves short user agents alone', () => {
    expect(shortUA('tslink-test/1.0')).toBe('tslink-test/1.0');
  });
  it('drops boilerplate from long ones', () => {
    const ua = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36 Tesla/2026.32.6';
    const s = shortUA(ua, 60);
    expect(s).toBe('X11 Chrome/136.0.0.0 Safari/537.36 Tesla/2026.32.6');
    expect(s.length).toBeLessThanOrEqual(60);
  });
  it('truncates whatever is still too long', () => {
    const s = shortUA('x'.repeat(200), 50);
    expect(s.length).toBe(50);
    expect(s.endsWith('…')).toBe(true);
  });
});

describe('ErrorWindow (HEVC fallback trigger)', () => {
  it('trips at 3 errors within 10 s', () => {
    const w = new ErrorWindow(3, 10_000);
    expect(w.push(0)).toBe(false);
    expect(w.push(4000)).toBe(false);
    expect(w.push(9999)).toBe(true);
  });
  it('forgets errors older than the window', () => {
    const w = new ErrorWindow(3, 10_000);
    w.push(0);
    w.push(5000);
    expect(w.push(10_000)).toBe(false); // the one at 0 has aged out
    expect(w.count).toBe(2);
    expect(w.push(14_999)).toBe(true);
  });
  it('sparse errors never trip it', () => {
    const w = new ErrorWindow(3, 10_000);
    for (let t = 0; t < 100_000; t += 6000) expect(w.push(t)).toBe(false);
  });
  it('clear resets', () => {
    const w = new ErrorWindow(2, 10_000);
    w.push(0);
    w.clear();
    expect(w.push(1)).toBe(false);
  });
});

describe('LogRing', () => {
  it('keeps the last N lines', () => {
    const r = new LogRing(3);
    for (let i = 0; i < 5; i++) r.push(`line ${i}`, 't');
    expect(r.lines()).toEqual(['t line 2', 't line 3', 't line 4']);
  });
  it('collapses repeats', () => {
    const r = new LogRing(3);
    r.push('retrying', '10:00:00');
    r.push('retrying', '10:00:05');
    r.push('other', '10:00:06');
    expect(r.lines()).toEqual(['10:00:05 retrying (x2)', '10:00:06 other']);
  });
});

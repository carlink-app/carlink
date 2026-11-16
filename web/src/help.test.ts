import { describe, expect, it } from 'vitest';
import { NO_WEBTRANSPORT, REJECTED } from './connection';
import { errorText, helpFor, helpKind } from './help';

describe('helpKind', () => {
  it('maps connection errors to the trouble behind them', () => {
    expect(helpKind(NO_WEBTRANSPORT)).toBe('browser');
    expect(helpKind(`${REJECTED} · retrying in 2s`)).toBe('code');
    expect(helpKind('No pairing code · retrying in 1s')).toBe('code');
    expect(helpKind('Opening handshake failed. (wrong pairing code, phone not running, or clock off?) · retrying in 4s')).toBe('handshake');
    expect(helpKind('Timed out (phone unreachable?) · retrying in 1s')).toBe('unreachable');
    expect(helpKind('No response from phone · retrying in 1s')).toBe('unreachable');
    expect(helpKind('Session closed')).toBe('unreachable');
  });
});

describe('errorText', () => {
  it('drops the retry countdown', () => {
    expect(errorText('Session closed · retrying in 5s')).toBe('Session closed');
    expect(errorText('Session closed')).toBe('Session closed');
  });
});

describe('helpFor', () => {
  it('nothing when connected or idle', () => {
    expect(helpFor({ s: 'connected', text: 'Connected to x' }, 'Session closed')).toBeNull();
    expect(helpFor({ s: 'idle', text: 'Not paired' }, null)).toBeNull();
  });

  it('an error: its help, the error text, and a retry button', () => {
    const h = helpFor({ s: 'error', text: `${REJECTED} · retrying in 2s` }, null)!;
    expect(h.kind).toBe('code');
    expect(h.detail).toBe(REJECTED);
    expect(h.retry).toBe(true);
    expect(h.steps.some((s) => /enter it again/i.test(s))).toBe(true);
  });

  it('no retry button when retrying cannot help (no WebTransport)', () => {
    const h = helpFor({ s: 'error', text: NO_WEBTRANSPORT }, null)!;
    expect(h.kind).toBe('browser');
    expect(h.retry).toBe(false);
  });

  it('the unreachable help names the hotspot and Start engine', () => {
    const h = helpFor({ s: 'error', text: 'Timed out (phone unreachable?) · retrying in 1s' }, null)!;
    expect(h.steps.join(' ')).toMatch(/hotspot/);
    expect(h.steps.join(' ')).toMatch(/Start engine/);
  });

  it('connecting: generic help at first, the last error\'s help between retries', () => {
    expect(helpFor({ s: 'connecting', text: 'Connecting…' }, null)!.kind).toBe('connecting');
    const h = helpFor({ s: 'connecting', text: 'Connecting…' }, `${REJECTED} · retrying in 1s`)!;
    expect(h.kind).toBe('code');
    expect(h.detail).toBe('Trying again…');
    expect(h.retry).toBe(false);
  });
});

describe('help edge cases', () => {
  it('certificate errors are handshake trouble', () => {
    expect(helpKind('Certificate hash mismatch')).toBe('handshake');
  });
  it('the browser kind needs the exact text (not a prefix)', () => {
    expect(helpKind(`${NO_WEBTRANSPORT} · retrying in 1s`)).toBe('browser');
    expect(helpKind(`${NO_WEBTRANSPORT}!`)).toBe('unreachable');
  });
  it('errorText keeps a dot in the middle of the text', () => {
    expect(errorText('a · b · retrying in 3s')).toBe('a · b');
  });
});

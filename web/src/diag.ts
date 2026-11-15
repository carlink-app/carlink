// Pure helpers for the debug overlay, the HEVC fallback and the pairing form.
// No DOM access here, so they're unit-tested in node.

import { normalizeCode, toHex } from './cert';

/**
 * A saved pairing code for display: masked except the last 4 characters,
 * grouped like the phone shows it ("••••-••••-XY7Q"). Codes of 4 or fewer
 * characters are masked entirely.
 */
export function maskCode(code: string): string {
  const c = normalizeCode(code);
  if (!c) return '';
  const visible = c.length > 4 ? 4 : 0;
  const masked = '•'.repeat(c.length - visible) + c.slice(c.length - visible);
  return masked.match(/.{1,4}/g)!.join('-');
}

/** The code to save from the pairing form: an empty field keeps the saved code. */
export function resolveCode(typed: string, saved: string): string {
  const t = typed.trim().toUpperCase();
  return t || saved;
}

/** Short certificate fingerprint (first 6 hex chars of the cert hash), see docs/PROTOCOL.md. */
export const fingerprint = (hash: Uint8Array): string => toHex(hash.subarray(0, 3));

/** `unixSecs` as "YYYY-MM-DD HH:MM:SSZ". */
export function utcStamp(unixSecs: number): string {
  return new Date(unixSecs * 1000).toISOString().replace('T', ' ').replace(/\.\d+Z$/, 'Z');
}

/** A user agent for one overlay line: browser tokens kept, the long platform part dropped. */
export function shortUA(ua: string, max = 90): string {
  if (ua.length <= max) return ua;
  // "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/... Chrome/... Safari/..." -> keep the product tokens.
  const tokens = ua.replace(/\([^)]*\)/g, ' ').replace(/Mozilla\/5\.0|AppleWebKit\/\S+|KHTML, like Gecko/g, ' ')
    .split(/\s+/).filter(Boolean);
  const platform = /\(([^;)]*)/.exec(ua)?.[1]?.trim();
  const s = [platform, ...tokens].filter(Boolean).join(' ');
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

/** Counts events in a sliding time window; `push` says whether `limit` was reached. */
export class ErrorWindow {
  private times: number[] = [];
  constructor(readonly limit = 3, readonly windowMs = 10_000) {}

  push(now: number): boolean {
    this.times = this.times.filter((t) => now - t < this.windowMs);
    this.times.push(now);
    return this.times.length >= this.limit;
  }

  clear() { this.times = []; }
  get count() { return this.times.length; }
}

/** The last `size` log lines; a line repeating the previous one bumps its count instead. */
export class LogRing {
  private items: { text: string; n: number; at: string }[] = [];
  constructor(readonly size = 15) {}

  push(text: string, at: string) {
    const last = this.items[this.items.length - 1];
    if (last && last.text === text) { last.n++; last.at = at; return; }
    this.items.push({ text, n: 1, at });
    if (this.items.length > this.size) this.items.shift();
  }

  lines(): string[] {
    return this.items.map((x) => `${x.at} ${x.text}${x.n > 1 ? ` (x${x.n})` : ''}`);
  }
}

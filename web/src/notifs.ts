// Notification banners: validation of untrusted `notif` messages and the
// banner queue (pure; main.ts does the DOM).

import type { NotifKind } from './protocol';

/** Limits from PROTOCOL.md, enforced here too (the phone is not trusted to). */
export const MAX_TITLE = 120;
export const MAX_TEXT = 300;
export const MAX_ICON = 16 * 1024;
/** Not limited by the protocol; long enough for any real app label / package. */
export const MAX_APP = 64;
export const MAX_PKG = 255;

export const MAX_VISIBLE = 3;
export const AUTO_HIDE_MS = 8000;

export interface Notif {
  id: string;
  pkg: string;
  app: string;
  title: string;
  text: string;
  icon?: string;
  kind: NotifKind;
}

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const ICON_PREFIXES = ['data:image/png;base64,', 'data:image/webp;base64,'];
const BASE64_RE = /^[A-Za-z0-9+/]+={0,2}$/;
const KINDS: readonly NotifKind[] = ['msg', 'call', 'nav', 'other'];
// C0/C1 controls and bidi overrides/isolates (could make a banner read differently than it is).
const CONTROL_RE = /[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]+/g;

export const validId = (v: unknown): v is string => typeof v === 'string' && ID_RE.test(v);

/** Untrusted string -> single-line plain text, at most `max` code points. */
export function clean(v: unknown, max: number): string {
  if (typeof v !== 'string') return '';
  const s = v.replace(CONTROL_RE, ' ').replace(/\s+/g, ' ').trim();
  const cps = Array.from(s); // don't split surrogate pairs
  return cps.length > max ? cps.slice(0, max - 1).join('').trimEnd() + '…' : s;
}

/** Only base64 PNG/WebP data URLs within the size limit; anything else is dropped. */
export function safeIcon(v: unknown): string | undefined {
  if (typeof v !== 'string' || v.length > MAX_ICON) return undefined;
  const prefix = ICON_PREFIXES.find((p) => v.startsWith(p));
  if (!prefix) return undefined;
  return BASE64_RE.test(v.slice(prefix.length)) ? v : undefined;
}

/** A `notif` message from the phone -> a banner, or null if it can't be shown. */
export function sanitizeNotif(m: unknown): Notif | null {
  if (!m || typeof m !== 'object') return null;
  const o = m as Record<string, unknown>;
  if (!validId(o.id)) return null;
  const pkg = clean(o.pkg, MAX_PKG);
  const app = clean(o.app, MAX_APP) || pkg;
  const title = clean(o.title, MAX_TITLE);
  const text = clean(o.text, MAX_TEXT);
  if (!app && !title && !text) return null;
  const kind = KINDS.includes(o.kind as NotifKind) ? (o.kind as NotifKind) : 'other';
  const icon = safeIcon(o.icon);
  return { id: o.id, pkg, app, title, text, kind, ...(icon ? { icon } : {}) };
}

export interface Banner {
  n: Notif;
  /** When it auto-hides (ms, same clock as `now`); null = until `notif-gone` (calls). */
  expires: number | null;
}

const expiry = (n: Notif, now: number) => (n.kind === 'call' ? null : now + AUTO_HIDE_MS);

/**
 * New or updated notification. Same id: replaced in place (timer restarts).
 * New: on top; beyond MAX_VISIBLE the oldest (bottom) is dropped.
 */
export function addBanner(list: readonly Banner[], n: Notif, now: number): Banner[] {
  const b: Banner = { n, expires: expiry(n, now) };
  const i = list.findIndex((x) => x.n.id === n.id);
  if (i >= 0) return list.map((x, j) => (j === i ? b : x));
  const next = [b, ...list];
  while (next.length > MAX_VISIBLE) {
    // Over the cap: drop the oldest that isn't a ringing call (calls stay until notif-gone).
    let k = next.length - 1;
    while (k > 0 && next[k].expires === null) k--;
    next.splice(k, 1);
  }
  return next;
}

export function removeBanner(list: readonly Banner[], id: string): Banner[] {
  return list.filter((x) => x.n.id !== id);
}

/** Drop banners whose time is up. */
export function pruneExpired(list: readonly Banner[], now: number): Banner[] {
  return list.filter((x) => x.expires === null || x.expires > now);
}

/** Earliest pending expiry, or null if nothing auto-hides. */
export function nextExpiry(list: readonly Banner[]): number | null {
  let t: number | null = null;
  for (const x of list) if (x.expires !== null && (t === null || x.expires < t)) t = x.expires;
  return t;
}

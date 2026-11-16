// Wire protocol v2 (docs/PROTOCOL.md): types and the video stream header.

import type { Codec } from './h26x';

export const PROTOCOL_VERSION = 2;
export const PATH = '/carlink';
export const DEFAULT_HOST = '9.9.0.1';
export const DEFAULT_PORT = 4433;
/** Fallback `layout.dpi` at devicePixelRatio 1 (see paneDpi in settings.ts). */
export const PANE_DPI = 220;
/** `layout.dpi` range the phone accepts. */
export const DPI_MIN = 72;
export const DPI_MAX = 640;

export const HEADER_LEN = 24;
export const KIND_VIDEO = 1;
/** flags bit0. Other bits are reserved (0). */
export const FLAG_KEY = 1;

export interface FrameHeader extends HeaderPrefix {
  ptsUs: number;
  sendUs: number;
}

/** kind, pane and seq: the first 8 bytes, enough to identify a truncated stream. */
export interface HeaderPrefix { kind: number; flags: number; pane: number; seq: number }

export function parseHeaderPrefix(b: Uint8Array): HeaderPrefix | null {
  if (b.length < 8) return null;
  const dv = new DataView(b.buffer, b.byteOffset, 8);
  return { kind: dv.getUint8(0), flags: dv.getUint8(1), pane: dv.getUint16(2), seq: dv.getUint32(4) };
}

export function parseHeader(b: Uint8Array): FrameHeader | null {
  if (b.length < HEADER_LEN) return null;
  const dv = new DataView(b.buffer, b.byteOffset, HEADER_LEN);
  return {
    ...parseHeaderPrefix(b)!,
    ptsUs: Number(dv.getBigUint64(8)),
    sendUs: Number(dv.getBigUint64(16)),
  };
}

export function writeHeader(h: FrameHeader, out: Uint8Array): void {
  const dv = new DataView(out.buffer, out.byteOffset, HEADER_LEN);
  dv.setUint8(0, h.kind);
  dv.setUint8(1, h.flags);
  dv.setUint16(2, h.pane);
  dv.setUint32(4, h.seq);
  dv.setBigUint64(8, BigInt(h.ptsUs));
  dv.setBigUint64(16, BigInt(h.sendUs));
}

/** Launcher group, from the phone (missing on older engines: Other). */
export type AppCategory = 'nav' | 'energy' | 'music' | 'video' | 'social' | 'work' | 'other';
/** `ih`: hash of the app's icon; the icon itself comes on request (`app-icons`), unless sent inline. */
export interface AppInfo { pkg: string; cls: string; label: string; icon?: string; ih?: string; cat?: AppCategory }

export type PhoneMsg =
  | { t: 'challenge'; v: number; nonce: string }
  | { t: 'authok' }
  | { t: 'hello'; v: number; device?: string; android?: number }
  | { t: 'pane'; id: number; w: number; h: number; codec: Codec }
  | { t: 'apps'; list: AppInfo[] }
  | { t: 'app-icons'; list: { pkg: string; ih: string; icon: string }[] }
  /** QR pairing: the real pairing code, for the car that showed the QR code. */
  | { t: 'paired'; code: string }
  | { t: 'launched'; pane: number; pkg: string }
  /** Phone's rate control: level in permille of the codec's max bitrate. */
  | { t: 'rate'; level: number; kbps: number }
  | { t: 'error'; msg: string }
  /** Untrusted: run through `sanitizeNotif` (notifs.ts) before use. */
  | { t: 'notif'; id: string; pkg: string; app: string; title: string; text: string; icon?: string; kind: NotifKind; replay?: boolean }
  | { t: 'notif-gone'; id: string }
  /** Drop all shown notifications (the phone re-sends current ones, or access ended). */
  | { t: 'notif-reset' };

export type NotifKind = 'msg' | 'call' | 'nav' | 'other';

export interface PaneLayout { id: number; w: number; h: number; dpi: number }

export type PageMsg =
  | { t: 'auth'; mac: string }
  /** `icons: 'hash'`: the page fetches icons with `app-icons` (else the phone sends them inline). */
  | { t: 'hello'; v: number; w: number; h: number; dpr: number; codecs: Codec[]; icons?: 'hash' }
  | { t: 'layout'; panes: PaneLayout[] }
  | { t: 'apps' }
  /** Icons the page doesn't have (by package); answered in small batches. */
  | { t: 'app-icons'; pkgs: string[] }
  | { t: 'launch'; pane: number; pkg: string; cls: string }
  /** `ts`: when it happened (page clock, ms); the phone keeps the spacing between events. */
  | { t: 'touch'; pane: number; a: 'down' | 'move' | 'up' | 'cancel'; id: number; x: number; y: number; ts?: number }
  | { t: 'key'; pane: number; k: 'back' | 'home' }
  | { t: 'keyframe'; pane: number }
  | { t: 'notif-open'; id: string }
  /** Close one app on the pane, or all of them (removes their tasks; no force-stop). */
  | { t: 'close'; pane: number; pkg: string }
  | { t: 'close-all'; pane: number };

// ---- main thread <-> decode worker ----

export type ToWorker =
  | { type: 'canvas'; pane: number; canvas: OffscreenCanvas }
  | { type: 'frame'; buf: ArrayBuffer }
  /** Stream ended early; the header (if it arrived) tells which seq is lost. */
  | { type: 'lost'; buf: ArrayBuffer }
  /** New session: seq restarts at 0, wait for a keyframe. */
  | { type: 'reset' }
  /** Phone announced the pane's video format (after layout changes). */
  | { type: 'pane'; pane: number; w: number; h: number; codec: Codec };

export interface PaneStats {
  pane: number;
  fps: number;
  decodeP50: number | null;
  decodeP95: number | null;
  droppedDecoded: number;
  droppedEncoded: number;
  codec: string | null;
  width: number;
  height: number;
  queue: number;
  errors: number;
  waitingKey: boolean;
  /** Longest time between two drawn frames in the last second (ms; null = none drawn). */
  maxGapMs: number | null;
  /** Freezes since the session started: frames drawn more than STALL_MS later than their phone timestamps say (a still screen sends nothing and doesn't count). */
  stalls: number;
}

export type FromWorker =
  /** First frame drawn after a reset (session start). */
  | { type: 'firstDraw'; pane: number }
  | { type: 'keyframe'; pane: number }
  | { type: 'stats'; stats: PaneStats }
  | { type: 'log'; text: string }
  /** A decode/configure failure (also counted in `errors`); main may fall back to H.264 on repeats. */
  | { type: 'decodeError'; pane: number; codec: Codec; text: string };

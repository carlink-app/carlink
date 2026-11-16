// Apps pinned to the rail (like a dock): list operations and storage parsing; no DOM here.
import type { AppInfo } from './protocol';

export const MAX_PINS = 5;
export const PINS_KEY = 'tslink.pins';

const same = (a: AppInfo, b: AppInfo) => a.pkg === b.pkg && a.cls === b.cls;

/** Pins from storage (JSON text): well-formed entries only, no duplicates, at most MAX_PINS. */
export function parsePins(raw: string | null): AppInfo[] {
  let v: unknown;
  try { v = JSON.parse(raw || '[]'); } catch { return []; }
  if (!Array.isArray(v)) return [];
  const out: AppInfo[] = [];
  for (const a of v) {
    if (!a || typeof a.pkg !== 'string' || typeof a.cls !== 'string') continue;
    const app: AppInfo = { pkg: a.pkg, cls: a.cls, label: typeof a.label === 'string' ? a.label : a.pkg };
    if (typeof a.icon === 'string') app.icon = a.icon;
    if (!out.some((p) => same(p, app))) out.push(app);
    if (out.length === MAX_PINS) break;
  }
  return out;
}

export const isPinned = (pins: readonly AppInfo[], a: AppInfo): boolean => pins.some((p) => same(p, a));

/** Pins with [a] added at the end; null if the rail is full. Already pinned: unchanged. */
export function addPin(pins: readonly AppInfo[], a: AppInfo, max = MAX_PINS): AppInfo[] | null {
  if (isPinned(pins, a)) return [...pins];
  if (pins.length >= max) return null;
  return [...pins, a];
}

export const removePin = (pins: readonly AppInfo[], a: AppInfo): AppInfo[] => pins.filter((p) => !same(p, a));

/** Recents to show on the rail: the pinned ones are already there. */
export const unpinnedRecents = (recents: readonly AppInfo[], pins: readonly AppInfo[]): AppInfo[] =>
  recents.filter((r) => !isPinned(pins, r));

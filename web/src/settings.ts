import { DEFAULT_HOST, DEFAULT_PORT, DPI_MAX, DPI_MIN, PANE_DPI } from './protocol';

export interface Display {
  /** Notification banners show the message text (off: app + title only). */
  showText: boolean;
}

export interface Settings extends Display { code: string; host: string; port: number }

export const DEFAULT_DISPLAY: Display = { showText: true };

/** Shortest side of the phone's display in dp: just above the 600 dp where apps switch to phone layouts. */
const TARGET_SW_DP = 640;
const MIN_DPI = 200;
const MAX_CSS_DPI = 260;

/**
 * `layout.dpi` for a w x h device-pixel pane: the biggest UI that keeps apps
 * in their wide (tablet) layout (shortest side ~640 dp). Tesla, measured in
 * the car: the browser zooms by 1.53, and the normal window leaves a
 * 1036x918 px pane -> 230 dpi. The floor is in device pixels: scaling it by
 * the zoom (306 dpi there) gave phone layouts and a cramped UI. The ceiling
 * scales with the pixel ratio, so a high-DPI test screen shows the same
 * layout as the car.
 */
export function paneDpi(w: number, h: number, dpr: number): number {
  const r = dpr > 0 ? dpr : 1;
  const side = Math.min(w, h);
  const ideal = side > 0 ? Math.round((side * 160) / TARGET_SW_DP) : PANE_DPI * r;
  const dpi = Math.min(MAX_CSS_DPI * r, Math.max(MIN_DPI, ideal));
  return Math.min(DPI_MAX, Math.max(DPI_MIN, Math.round(dpi)));
}

/** Page UI scale (--ui in style.css): keep the sidebar and menus about the size they have at 1x. */
export const uiScale = (dpr: number): number => Math.min(1, Math.max(0.6, 1.15 / (dpr || 1)));

/**
 * Largest stream the phone is asked for: the Tesla's whole screen. Bigger
 * windows (a 2x desktop, full screen) would make the phone render and encode
 * several times that, and drop frames; the page scales the picture up instead.
 */
export const MAX_PANE_PIXELS = 1920 * 1200;

/** [w]x[h] at [dpi], shrunk to MAX_PANE_PIXELS keeping the aspect and the dp layout. */
export function capPane(w: number, h: number, dpi: number): { w: number; h: number; dpi: number } {
  if (w * h <= MAX_PANE_PIXELS) return { w, h, dpi };
  const s = Math.sqrt(MAX_PANE_PIXELS / (w * h));
  const even = (n: number) => Math.max(2, Math.floor(n / 2) * 2);
  return { w: even(w * s), h: even(h * s), dpi: Math.max(DPI_MIN, Math.round(dpi * s)) };
}

const KEY = 'tslink.settings';

export function loadSaved(): Settings {
  let s: Partial<Settings> = {};
  try { s = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch { /* corrupt: use defaults */ }
  return {
    code: typeof s.code === 'string' ? s.code : '',
    host: typeof s.host === 'string' && s.host ? s.host : DEFAULT_HOST,
    port: validPort(s.port) ?? DEFAULT_PORT,
    // Added in milestone 3: older saved settings lack it.
    showText: typeof s.showText === 'boolean' ? s.showText : DEFAULT_DISPLAY.showText,
  };
}

export function save(s: Settings): void {
  const { code, host, port, showText } = s; // nothing else gets persisted
  localStorage.setItem(KEY, JSON.stringify({ code, host, port, showText }));
}

/** Save display settings only, keeping the saved pairing (not any dev override in effect). */
export function saveDisplay(d: Display): void {
  save({ ...loadSaved(), showText: d.showText });
}

/** Whether a change of settings needs a new session (display settings don't). */
export const sameEndpoint = (a: Settings, b: Settings) => a.code === b.code && a.host === b.host && a.port === b.port;

/**
 * Saved settings with dev overrides from the URL fragment
 * (#code=…&host=…&port=…; not saved). The fragment never reaches a server,
 * and the caller strips it from the address bar/history.
 */
export function effective(fragment: string): Settings {
  const s = loadSaved();
  const o = new URLSearchParams(fragment.replace(/^#/, ''));
  const code = o.get('code');
  const host = o.get('host');
  const port = validPort(o.get('port'));
  return { ...s, code: code ?? s.code, host: host || s.host, port: port ?? s.port };
}

export function validPort(v: unknown): number | null {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN;
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : null;
}

export function endpoint(s: Pick<Settings, 'host' | 'port'>): string {
  const host = s.host.includes(':') && !s.host.startsWith('[') ? `[${s.host}]` : s.host;
  return `https://${host}:${s.port}`;
}

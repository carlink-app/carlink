// Main thread: UI (sidebar, launcher, pairing), connection, touch, debug overlay.
// Video bytes are handed to the decode worker untouched.

import './style.css';
import { Connection, TIMED_OUT, type Status } from './connection';
import { demoApps, demoNotifs, DEMO_H, DEMO_W, startDemo } from './demo';
import type { Codec } from './h26x';
import { PANE_DPI, type AppInfo, type FromWorker, type PageMsg, type PaneLayout, type PaneStats, type PhoneMsg, type ToWorker } from './protocol';
import { formatCode, makeCert, weekStart } from './cert';
import { ErrorWindow, fingerprint, LogRing, maskCode, resolveCode, shortUA, utcStamp } from './diag';
import { afterClose, groupApps, refreshRecents, resumeAction, withFront } from './apps';
import { accentFrom, textOn } from './theme';
import { describe as describePhone, parsePhoneStatus } from './phonestatus';
import { parseMedia, subtitle, type NowPlaying } from './media';
import { drawQr, newSecret, pairedCode, pairingPace, pairingView, qrModules, qrStatusText, qrText } from './pairing';
import { errorText, helpFor } from './help';
import { attachLongPress } from './longpress';
import { addPin, isPinned, MAX_PINS, parsePins, PINS_KEY, removePin, unpinnedRecents } from './pins';
import { filterApps } from './search';
import { acceptIcons, applyCache, chunkByBytes, ICON_REQUEST_BYTES, loadIcons, MAX_CACHED, saveIcons, validIcon, type CachedIcon } from './iconcache';
import {
  addBanner, addToTray, callsFirst, nextExpiry, pruneExpired, removeBanner, removeFromTray, sanitizeNotif, trayBadge, trayOthers, validId,
  type Banner, type Notif,
} from './notifs';
import { addressError, capPane, effective, loadSaved, paneDpi, sameEndpoint, save, uiScale, validPort, type Settings } from './settings';
import { Samples } from './stats';
import { attachTouch } from './touch';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const params = new URLSearchParams(location.search);
const DEMO = params.get('demo') === '1';
const MAX_RECENTS = 5;
/** Time for a closed app's sidebar icon to shrink away (.recent.leaving) before it's dropped. */
const LEAVE_MS = 180;
let debugOn = params.get('debug') === '1';
/** Build id (`vite.config.ts`): "YYMMDD-HHMM-<content hash>", or "dev". */
const BUILD = /^\d{6}-\d{4}-[0-9a-f]{8}$/.test(__BUILD__) ? __BUILD__ : 'dev';

// ---------- log (debug overlay: no devtools in the car) ----------

const logRing = new LogRing(15);
function logLine(text: string) {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  logRing.push(text.slice(0, 160), `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`);
  renderDebug();
}
addEventListener('error', (e) => logLine(`error: ${e.message}`));
addEventListener('unhandledrejection', (e) => logLine(`unhandled: ${e.reason instanceof Error ? e.reason.message : String(e.reason)}`));

// ---------- decode worker ----------

const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
worker.onerror = (e) => logLine(`worker error: ${e.message || 'failed to load'}`);
const toWorker = (m: ToWorker, transfer: Transferable[] = []) => worker.postMessage(m, transfer);
/** Video bytes (24-byte header + access unit) go to the worker untouched, transferred. */
const forwardFrame = (buf: ArrayBuffer) => toWorker({ type: 'frame', buf }, [buf]);

// ---------- panes ----------

interface Pane {
  id: number;
  el: HTMLElement;
  canvas: HTMLCanvasElement;
  layout: PaneLayout;              // what we asked for
  video: { w: number; h: number } | null; // what the phone announced
  stats: PaneStats | null;
}

const panes: Pane[] = [];

function addPane(id: number): Pane {
  const el = document.createElement('div');
  el.className = 'pane';
  const canvas = document.createElement('canvas');
  el.appendChild(canvas);
  $('panes').appendChild(el);
  const off = canvas.transferControlToOffscreen();
  toWorker({ type: 'canvas', pane: id, canvas: off }, [off]);
  const p: Pane = { id, el, canvas, layout: { id, w: 0, h: 0, dpi: PANE_DPI }, video: null, stats: null };
  attachTouch({ pane: id, el: canvas, rippleHost: el, send, size: () => p.video ?? p.layout });
  panes.push(p);
  return p;
}

const even = (n: number) => Math.max(2, Math.floor(n / 2) * 2);

/** Pane sizes in device pixels: CSS size × devicePixelRatio, rounded down to even, capped (capPane); dpi to match. */
function measureLayout(): boolean {
  let changed = false;
  for (const p of panes) {
    const r = p.el.getBoundingClientRect();
    const fw = even(r.width * devicePixelRatio);
    const fh = even(r.height * devicePixelRatio);
    // Layout (dpi) for the full size, stream capped at the Tesla's whole screen.
    const { w, h, dpi } = capPane(fw, fh, paneDpi(fw, fh, devicePixelRatio));
    if (w !== p.layout.w || h !== p.layout.h || dpi !== p.layout.dpi) { p.layout = { id: p.id, w, h, dpi }; changed = true; }
  }
  return changed;
}
const sendLayout = () => { if (measureLayout()) send({ t: 'layout', panes: panes.map((p) => p.layout) }); };
const layout = (): PaneLayout[] => { measureLayout(); return panes.map((p) => p.layout); };

let layoutTimer = 0;
/** Page UI scale (--ui in style.css); see uiScale. */
function applyUiScale() {
  document.documentElement.style.setProperty('--ui', uiScale(devicePixelRatio).toFixed(3));
}
applyUiScale();
addEventListener('resize', applyUiScale);

new ResizeObserver(() => {
  clearTimeout(layoutTimer);
  layoutTimer = window.setTimeout(sendLayout, 250);
}).observe($('panes'));

addPane(0);

// ---------- connection ----------

/**
 * HEVC safety net: a decoder that keeps failing on HEVC (it can claim support
 * and still fail) -> reconnect offering only H.264, for the rest of this page
 * session. The phone picks the codec from the page's `hello`.
 */
let h264Only = false;
const hevcErrors = new ErrorWindow(3, 10_000);
function onDecodeError(m: { pane: number; codec: Codec; text: string }) {
  console.warn(`[worker] pane ${m.pane} (${m.codec}): ${m.text}`);
  logLine(`decode pane ${m.pane} ${m.codec}: ${m.text}`);
  if (m.codec !== 'hevc' || h264Only || DEMO) return;
  if (!hevcErrors.push(performance.now())) return;
  h264Only = true;
  logLine('HEVC keeps failing: reconnecting with H.264 only');
  toast('Switched to H.264');
  conn.restart();
}

let codecsP: Promise<Codec[]> | null = null;
/** What this browser can decode (for the overlay; the hello may offer less). */
let detected: Codec[] | null = null;
function detectCodecs(): Promise<Codec[]> {
  if (h264Only) return Promise.resolve(['h264']);
  codecsP ??= (async () => {
    const out: Codec[] = [];
    const probe: [Codec, string][] = [['h264', 'avc1.640032'], ['hevc', 'hvc1.1.6.L150.90']];
    // ?codec=h264 forces H.264 (the phone prefers HEVC when offered).
    const only = params.get('codec');
    for (const [c, s] of probe) {
      if (only && c !== only) continue;
      try {
        const r = await VideoDecoder.isConfigSupported({ codec: s, codedWidth: 1920, codedHeight: 1200, optimizeForLatency: true });
        if (r.supported) out.push(c);
      } catch { /* unsupported */ }
    }
    detected = out;
    return out.length ? out : ['h264'];
  })();
  return codecsP;
}

/**
 * Dev overrides (#code=&host=&port=). They may carry the pairing code, so
 * they're taken off the address bar and history at once; a reload for an
 * update gets them back from sessionStorage (this tab only).
 */
const OVERRIDE_KEY = 'tslink.override';
const overrides = location.hash || (sessionStorage.getItem(OVERRIDE_KEY) ?? '');
sessionStorage.removeItem(OVERRIDE_KEY);
let settings: Settings = effective(overrides);
if (location.hash) history.replaceState(null, '', location.pathname + location.search);
// A link can point the page at another phone: never let that go unnoticed.
if (overrides) setTimeout(() => toast(`Dev override active: ${settings.host}:${settings.port}`), 0);
// Editing the fragment of an open page doesn't reload it: apply overrides by reloading.
addEventListener('hashchange', () => { if (location.hash) location.reload(); });
const rtt = new Samples(250); // ~5 s of 20 ms pings

const conn = new Connection({
  settings: () => settings,
  layout,
  codecs: detectCodecs,
  onStatus: showStatus,
  onMessage,
  onSession: () => {
    $('phone-status').hidden = true; // until this phone sends its status
    performance.mark('tslink:session');
    firstFrameMarked = false;
    rtt.clear();
    for (const p of panes) p.video = null;
    toWorker({ type: 'reset' });
    setMedia(null); // the phone sends what plays after hello
    // Banners of the previous session would never get their notif-gone.
    clearNotifs();
  },
  onFrame: forwardFrame,
  onLost: (buf) => toWorker({ type: 'lost', buf }, [buf]),
  onRtt: (ms) => rtt.push(ms),
  // Pairing: the phone may switch any moment, so no growing backoff.
  retryMs: () => pace().retryMs,
  connectTimeoutMs: () => pace().connectTimeoutMs,
});

function send(m: PageMsg) {
  if (DEMO) {
    if (m.t === 'notif-open') toast(`Demo: would open notification ${m.id}`);
    return;
  }
  conn.send(m);
}

let phoneName = '';
let rateKbps = 0;
/** Set on each new session: the phone may have restarted with nothing on the pane. */
let resumePending = false;
function onMessage(m: PhoneMsg) {
  switch (m.t) {
    case 'hello':
      resumePending = true;
      phoneName = m.device || 'phone';
      showStatus({ s: 'connected', text: `Connected to ${phoneName}` });
      break;
    case 'pane': {
      const p = panes.find((x) => x.id === m.id);
      if (!p) break;
      p.video = { w: m.w, h: m.h };
      toWorker({ type: 'pane', pane: m.id, w: m.w, h: m.h, codec: m.codec });
      // Bring back what the car was showing (a no-op if it's still in front).
      if (resumePending && m.id === 0) {
        resumePending = false;
        const r = resumeAction(recents, current, m.front);
        if (r && 'launch' in r) send({ t: 'launch', pane: 0, pkg: r.launch.pkg, cls: r.launch.cls });
        // A reloaded page doesn't know: the phone says what it put back, so
        // show that instead of the launcher on top of it.
        else if (r) showFront(r.front);
      }
      break;
    }
    case 'apps': void onApps(Array.isArray(m.list) ? m.list : []); break;
    case 'app-icons': if (Array.isArray(m.list)) onIcons(m.list); break;
    case 'paired': onPaired(m.code); break;
    case 'media': setMedia(parseMedia(m as Record<string, unknown>)); break;
    case 'phone': showPhoneStatus(m as Record<string, unknown>); break;
    case 'rate': rateKbps = Number(m.kbps) || 0; break;
    case 'error': toast(`Phone: ${m.msg}`); break;
    case 'notif': {
      const n = sanitizeNotif(m);
      // A replay after a reconnect of something the driver already dismissed: skip.
      if (n && !(m.replay === true && dismissed.has(n.id))) {
        setBanners(addBanner(banners, n, performance.now()));
        setTray(addToTray(tray, n));
      }
      break;
    }
    case 'notif-gone': if (validId(m.id)) forget(m.id); break;
    // The phone re-sends what's current (or notification access ended): drop ours.
    case 'notif-reset': clearNotifs(); break;
    case 'launched': if (m.pane === 0 && m.pkg === openingPkg) openingDone(OPENING_AFTER_LAUNCH_MS); break;
  }
}

// ---------- notification banners and tray ----------

let banners: Banner[] = [];
let tray: Notif[] = [];
/** Ids the driver opened or dismissed (bounded): the phone replays recent ones on reconnect. */
const dismissed = new Set<string>();

/** Gone from the phone: off the screen and out of the tray. */
function forget(id: string) {
  setBanners(removeBanner(banners, id));
  setTray(removeFromTray(tray, id));
}

function clearNotifs() {
  setBanners([]);
  setTray([]);
}

/** Opened or dismissed by the driver: forget it and don't take it back from a replay. */
function dismiss(id: string) {
  remember(id);
  forget(id);
}

function remember(id: string) {
  dismissed.add(id);
  if (dismissed.size > 200) dismissed.delete(dismissed.values().next().value!);
}

/** [pkg] is in front on the car screen (the phone says so): sidebar and launcher follow. */
function showFront(pkg: string) {
  current = pkg;
  const next = withFront(recents, apps, pkg);
  if (next) setRecents(next);
  // Not on the sidebar yet: the app list usually comes after the pane; add it then.
  frontPending = next ? null : pkg;
  showLauncher(false);
}
let frontPending: string | null = null;

/** Open a notification's app on the car (the phone opens it; see notif-open). */
function openNotif(n: Notif) {
  send({ t: 'notif-open', id: n.id });
  showOpening(n.app, n.icon, OPENING_NOTIF_MS);
  dismiss(n.id);
  showTray(false);
  showFront(n.pkg); // in front, like a launch (not behind the launcher)
}

function setTray(next: Notif[]) {
  tray = next;
  const count = tray.length;
  $('tray-count').textContent = trayBadge(count);
  if (!$('tray').hidden) renderTray();
  renderBanners(); // the "+N more" chip
}

function showTray(on: boolean) {
  $('tray').hidden = !on;
  $('btn-tray').classList.toggle('active', on);
  $('main').classList.toggle('tray-open', on); // pop-ups hide behind it (style.css)
  if (on) renderTray();
}
// Touching the app (left of the tray) closes it, like a phone's shade.
// The touch only closes it (it doesn't also reach the app).
$('panes').addEventListener('pointerdown', (e) => {
  if ($('tray').hidden) return;
  e.stopPropagation();
  e.preventDefault();
  showTray(false);
}, { capture: true });

let trayShownAt = 0;
function renderTray() {
  trayShownAt = performance.now(); // rows may have moved: ignore taps meant for the old order
  $('tray-clear').hidden = tray.length === 0;
  if (!tray.length) {
    const none = document.createElement('div');
    none.className = 'tray-empty';
    none.textContent = 'No notifications';
    $('tray-list').replaceChildren(none);
    return;
  }
  $('tray-list').replaceChildren(...tray.map((n) => {
    const row = document.createElement('div');
    row.className = `tray-item ${n.kind}`;
    const body = document.createElement('button');
    body.className = 'tray-body';
    body.setAttribute('aria-label', `Open ${n.app}: ${n.title}`);
    body.append(iconEl(n.app, n.icon), notifTexts(n));
    body.addEventListener('click', () => { if (performance.now() - trayShownAt >= TAP_GUARD_MS) openNotif(n); });
    row.append(body, dismissButton(n.id));
    return row;
  }));
}

$('btn-tray').addEventListener('click', () => { showMedia(false); showTray($('tray').hidden === true); });

// ---------- now playing ----------

let nowPlaying: NowPlaying | null = null;
const PLAY_ICON = 'M8 5v14l11-7z';
const PAUSE_ICON = 'M6 5h4v14H6zm8 0h4v14h-4z';

/** Cover art as a background (validated data URL), else the app's first letter. */
/** Cover art if the player gives one, else the app's icon (both vetted data URLs). */
function mediaImage(n: NowPlaying): string | undefined {
  if (n.art) return n.art;
  const app = apps.find((a) => a.pkg === n.pkg);
  return app && vettedIcon(app);
}

/** The picture as a background, else the app's first letter. */
function paintArt(el: HTMLElement, n: NowPlaying) {
  const src = mediaImage(n);
  el.style.backgroundImage = src ? `url("${src}")` : '';
  el.classList.toggle('icon', !n.art && !!src); // an app icon: shown whole, not cropped
  el.textContent = src ? '' : (n.app || n.title).charAt(0).toUpperCase();
}

/** Theme the card from the picture's most vivid colour (default accent if none). */
let themedFrom = '';
function themeMedia(n: NowPlaying) {
  const src = mediaImage(n) ?? '';
  if (src === themedFrom) return;
  themedFrom = src;
  const apply = (c: [number, number, number] | null) => {
    if (themedFrom !== src) return; // the track changed meanwhile
    for (const el of [$('media'), $('btn-media')]) {
      el.style.setProperty('--np', c ? `rgb(${c.join(' ')})` : '');
      el.style.setProperty('--np-fg', c ? textOn(c) : '');
    }
  };
  if (!src) { apply(null); return; }
  const img = new Image();
  img.onload = () => {
    const cv = document.createElement('canvas');
    cv.width = cv.height = 24;
    const g = cv.getContext('2d', { willReadFrequently: true });
    if (!g) return apply(null);
    g.drawImage(img, 0, 0, 24, 24);
    apply(accentFrom(g.getImageData(0, 0, 24, 24).data));
  };
  img.onerror = () => apply(null);
  img.src = src;
}

function setMedia(n: NowPlaying | null) {
  nowPlaying = n;
  const btn = $('btn-media');
  btn.hidden = !n;
  if (!n) { showMedia(false); return; }
  btn.classList.toggle('playing', n.playing);
  btn.setAttribute('aria-label', `Now playing: ${n.title}`);
  paintArt($('media-thumb'), n);
  themeMedia(n);
  if (!$('media').hidden) renderMedia();
}

function renderMedia() {
  const n = nowPlaying;
  if (!n) return;
  paintArt($('media-art'), n);
  $('media-title').textContent = n.title;
  $('media-sub').textContent = subtitle(n);
  $('media-play-icon').setAttribute('d', n.playing ? PAUSE_ICON : PLAY_ICON);
  $('media-play').setAttribute('aria-label', n.playing ? 'Pause' : 'Play');
  $<HTMLButtonElement>('media-prev').disabled = !n.prev;
  $<HTMLButtonElement>('media-next').disabled = !n.next;
  const app = apps.find((a) => a.pkg === n.pkg);
  $('media-open').hidden = !app;
  $('media-open').textContent = `Open ${app?.label ?? n.app}`;
}

let mediaShownAt = 0;
function showMedia(on: boolean) {
  $('media').hidden = !on;
  $('btn-media').classList.toggle('active', on);
  if (on) { mediaShownAt = performance.now(); showTray(false); renderMedia(); }
}

function mediaCmd(a: 'play' | 'pause' | 'next' | 'prev', fromClose = false) {
  if (!fromClose && performance.now() - mediaShownAt < TAP_GUARD_MS) return;
  send({ t: 'media-cmd', a });
  // Feels instant; the phone's next update confirms (or corrects) it.
  if (nowPlaying && (a === 'play' || a === 'pause')) setMedia({ ...nowPlaying, playing: a === 'play' });
}

$('btn-media').addEventListener('click', () => showMedia($('media').hidden === true));
$('media-play').addEventListener('click', () => mediaCmd(nowPlaying?.playing ? 'pause' : 'play'));
$('media-prev').addEventListener('click', () => mediaCmd('prev'));
$('media-next').addEventListener('click', () => mediaCmd('next'));
$('media-open').addEventListener('click', () => {
  const app = nowPlaying && apps.find((a) => a.pkg === nowPlaying!.pkg);
  showMedia(false);
  if (app) launch(app);
});
// A touch outside the card closes it; over the app it only closes it (like the tray).
$('main').addEventListener('pointerdown', (e) => {
  if ($('media').hidden || $('media').contains(e.target as Node)) return;
  showMedia(false);
  if ($('panes').contains(e.target as Node)) { e.stopPropagation(); e.preventDefault(); }
}, { capture: true });
$('tray-clear').addEventListener('click', () => {
  for (const n of tray) remember(n.id);
  setTray([]);
  setBanners(banners.filter((b) => b.n.kind === 'call'));
});

// ---------- opening an app: a short placeholder instead of a black or stale frame ----------

const OPENING_MAX_MS = 3000;
const OPENING_AFTER_LAUNCH_MS = 450;
const OPENING_NOTIF_MS = 1800;
let openingTimer = 0;
/** Package the placeholder is for: a late `launched` of an earlier app mustn't hide it. */
let openingPkg = '';

function showOpening(label: string, icon: string | undefined, maxMs = OPENING_MAX_MS, pkg = '') {
  openingPkg = pkg;
  $('opening-icon').replaceChildren(iconEl(label, icon));
  $('opening-label').textContent = label;
  $('opening').hidden = false;
  openingDone(maxMs);
}

function showOpeningApp(a: AppInfo) {
  showOpening(a.label || a.pkg, vettedIcon(a), OPENING_MAX_MS, a.pkg);
}

/** Hide the placeholder after [ms] (the app gets a moment to draw its first frames). */
function openingDone(ms: number) {
  clearTimeout(openingTimer);
  openingTimer = window.setTimeout(() => { $('opening').hidden = true; }, ms);
}
const bannerEls = new Map<string, HTMLElement>();
let bannerTimer = 0;

function setBanners(next: Banner[]) {
  banners = next;
  renderBanners();
  clearTimeout(bannerTimer);
  const t = nextExpiry(banners);
  if (t !== null) {
    bannerTimer = window.setTimeout(() => setBanners(pruneExpired(banners, performance.now())), Math.max(0, t - performance.now()));
  }
}

const SVG_NS = 'http://www.w3.org/2000/svg';
function closeIcon(): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z');
  svg.appendChild(path);
  return svg;
}
/** The ✕ on a banner or tray row. */
function dismissButton(id: string): HTMLButtonElement {
  const close = document.createElement('button');
  close.className = 'notif-close';
  close.setAttribute('aria-label', 'Dismiss');
  close.appendChild(closeIcon());
  close.addEventListener('click', () => dismiss(id));
  return close;
}
/** What each banner element currently shows, to skip refilling unchanged ones. */
const filled = new WeakMap<HTMLElement, Notif>();
/** When each banner last appeared or changed: taps right after that were meant for what was below. */
const shownAt = new WeakMap<HTMLElement, number>();
const TAP_GUARD_MS = 400;

function bannerEl(id: string): HTMLElement {
  let el = bannerEls.get(id);
  if (el) return el;
  el = document.createElement('div');
  const body = document.createElement('button');
  body.className = 'notif-body';
  body.addEventListener('click', () => {
    if (performance.now() - (shownAt.get(el!) ?? 0) < TAP_GUARD_MS) return;
    const b = banners.find((x) => x.n.id === id);
    if (b) openNotif(b.n);
  });
  // "+N": more notifications waiting in the tray.
  const more = document.createElement('button');
  more.className = 'notif-more';
  more.addEventListener('click', () => {
    if (performance.now() - (shownAt.get(el!) ?? 0) < TAP_GUARD_MS) return;
    setBanners(removeBanner(banners, id));
    showTray(true);
  });
  el.append(body, more, dismissButton(id));
  bannerEls.set(id, el);
  return el;
}

/** Fill a banner from (sanitized) phone data: textContent only. */
function fillBanner(el: HTMLElement, n: Notif) {
  const f = filled.get(el);
  if (f === n) return;
  filled.set(el, n);
  shownAt.set(el, performance.now());
  el.className = `notif ${n.kind}`;
  const body = el.firstElementChild as HTMLElement;
  body.replaceChildren(iconEl(n.app, n.icon), notifTexts(n));
  body.setAttribute('aria-label', `Open ${n.app}: ${n.title}`);
}

/** App, title and (if enabled) text of a notification: textContent only. */
function notifTexts(n: Notif): HTMLElement {
  const texts = document.createElement('div');
  texts.className = 'notif-texts';
  const line = (cls: string, text: string) => {
    const d = document.createElement('div');
    d.className = cls;
    d.textContent = text;
    return d;
  };
  texts.append(line('notif-app', n.app), line('notif-title', n.title));
  if (n.text) texts.append(line('notif-text', n.text)); // the phone decides (TSLink > Notifications)
  return texts;
}

function renderBanners() {
  const host = $('notifs');
  const ids = new Set(banners.map((b) => b.n.id));
  for (const [id, el] of bannerEls) if (!ids.has(id)) { el.remove(); bannerEls.delete(id); }
  // Insert/move only what changed: re-inserting a node would replay its entrance animation.
  callsFirst(banners).forEach((b, i) => {
    const el = bannerEl(b.n.id);
    fillBanner(el, b.n);
    // Others waiting in the tray (calls aren't in it).
    const others = trayOthers(tray, b.n.id);
    const more = el.querySelector<HTMLElement>('.notif-more')!;
    more.hidden = b.n.kind === 'call' || others === 0;
    more.textContent = `+${others}`;
    more.setAttribute('aria-label', `${others} more notifications`);
    if (host.children[i] !== el) host.insertBefore(el, host.children[i] ?? null);
  });
}

worker.onmessage = (e: MessageEvent<FromWorker>) => {
  const m = e.data;
  if (m.type === 'keyframe') send({ t: 'keyframe', pane: m.pane });
  else if (m.type === 'decodeError') onDecodeError(m);
  else if (m.type === 'stats') {
    const p = panes.find((x) => x.id === m.stats.pane);
    if (p) p.stats = m.stats;
    renderDebug();
  } else if (m.type === 'firstDraw') {
    if (!firstFrameMarked) {
      firstFrameMarked = true;
      performance.mark('tslink:firstFrame');
      requestIcons(); // video is running: now the icons may use the link
    }
  } else if (m.type === 'log') {
    console.log(`[worker] ${m.text}`);
    logLine(m.text);
  }
};

// ---------- status ----------

let statusTimer = 0;
let lastStatus: Status = { s: 'idle', text: '' };
/** Pairing handshakes in a row that got no answer (see qrStatusText). */
let unanswered = 0;

function showStatus(st: Status) {
  // While pairing, failed attempts are expected (the phone hasn't scanned yet).
  if ((pairingSecret || performance.now() < pairedQuietUntil) && st.s !== 'connected') {
    // A handshake that times out got no answer at all: the phone isn't at that address.
    if (st.s === 'error') unanswered = st.text.startsWith(TIMED_OUT) ? unanswered + 1 : 0;
    const q = qrStatusText(unanswered, settings.host, settings.port);
    $('pair-qr-status').textContent = q.text;
    $('pair-qr-status').classList.toggle('pair-warn', q.warn);
    return;
  }
  unanswered = 0;
  // The retry countdown changes each time; without it, repeats collapse in the log.
  if (st.s === 'error') logLine(errorText(st.text));
  lastStatus = st;
  // No phone, no notifications: nothing stays readable in a parked car.
  if (st.s !== 'connected') clearNotifs();
  const el = $('status');
  el.className = st.s;
  el.textContent = st.text;
  $('conn-dot').className = st.s;
  clearTimeout(statusTimer);
  // Connected: show briefly, then get out of the way of the video.
  if (st.s === 'connected') statusTimer = window.setTimeout(() => { el.textContent = ''; }, 2500);
  updateLauncherMsg();
  updateHelp();
}

// ---------- connection help: what to check while the phone can't be reached ----------

/** A first connect usually takes well under this: no help panel flashing up at every start. */
const HELP_DELAY_MS = 2500;
/** Last error of the current outage (between retries the status says "connecting"). */
let lastError: string | null = null;
let helpTimer = 0;

function updateHelp() {
  const st = lastStatus;
  if (st.s === 'error') lastError = st.text;
  else if (st.s !== 'connecting') lastError = null;
  const h = DEMO ? null : helpFor(st, lastError);
  if (!h) {
    clearTimeout(helpTimer);
    helpTimer = 0;
    $('help').hidden = true;
    return;
  }
  if (!$('help').hidden || st.s === 'error') { renderHelp(); return; }
  if (!helpTimer) helpTimer = window.setTimeout(() => { helpTimer = 0; renderHelp(); }, HELP_DELAY_MS);
}

function renderHelp() {
  const h = DEMO ? null : helpFor(lastStatus, lastError);
  $('help').hidden = !h;
  if (!h) return;
  $('help').dataset.kind = h.kind;
  $('help-title').textContent = h.title;
  $('help-detail').textContent = h.detail; // our own or the browser's error text: textContent
  $('help-steps').replaceChildren(...h.steps.map((s) => {
    const li = document.createElement('li');
    li.textContent = s;
    return li;
  }));
  $('help-retry').hidden = !h.retry;
}
$('help-pairing').addEventListener('click', () => showPairing(true));
// The connection is waiting out its backoff: wake it now.
$('help-retry').addEventListener('click', () => conn.restart());

let toastTimer = 0;
function toast(text: string) {
  $('toast').textContent = text;
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { $('toast').textContent = ''; }, 4000);
}

// ---------- app icons: cached in the browser, fetched after the first frame ----------

/** Set at the first drawn frame of a session (marked for dev/perf-startup.mjs); icons wait for it. */
let firstFrameMarked = false;
let iconCache = new Map<string, CachedIcon>();
// A storage that never answers mustn't hold up the launcher.
const iconCacheReady = Promise.race([loadIcons(), new Promise<Map<string, CachedIcon>>((r) => setTimeout(() => r(new Map()), 500))])
  .then((c) => { iconCache = c; });
let missingIcons: string[] = [];
/** Asked for and not yet received: only these are accepted. */
const requestedIcons = new Set<string>();
let iconsTimer = 0;
let iconsRender = 0;
const MAX_PKG_CHARS = 255;
/** Android package names (also keeps every name one JSON byte per character). */
const PKG_RE = /^[A-Za-z0-9_.]+$/;

async function onApps(list: AppInfo[]) {
  await iconCacheReady;
  const t0 = performance.now();
  const valid = list.filter((a) => a && typeof a.pkg === 'string');
  missingIcons = applyCache(valid, iconCache).filter((p) => p.length <= MAX_PKG_CHARS && PKG_RE.test(p)).slice(0, MAX_CACHED);
  setApps(valid);
  if (frontPending && frontPending === current) {
    const next = withFront(recents, apps, frontPending);
    if (next) setRecents(next);
  }
  frontPending = null;
  performance.measure('tslink:apps', { start: t0, end: performance.now(), detail: { count: apps.length, missingIcons: missingIcons.length } });
  void saveIcons(new Map(), new Set(valid.map((a) => a.pkg))); // forget uninstalled apps
  // Asked for at the first frame; if video is already running, now; the timer
  // covers a session without video.
  clearTimeout(iconsTimer);
  if (firstFrameMarked) requestIcons();
  else iconsTimer = window.setTimeout(requestIcons, 2000);
}

function requestIcons() {
  clearTimeout(iconsTimer);
  requestedIcons.clear();
  for (const p of missingIcons) requestedIcons.add(p);
  // Split by size: one line must stay under the phone's control line limit.
  for (const pkgs of chunkByBytes(missingIcons, ICON_REQUEST_BYTES)) send({ t: 'app-icons', pkgs });
  missingIcons = [];
}

function onIcons(list: { pkg: string; ih: string; icon: string }[]) {
  // Only icons we asked for, valid (size-capped PNG), with the hash our list has.
  const add = acceptIcons(list, apps, requestedIcons);
  if (!add.size) return;
  void saveIcons(add);
  for (const [k, v] of add) iconCache.set(k, v);
  // Batches arrive ~40 ms apart: redraw the grid once they settle.
  clearTimeout(iconsRender);
  iconsRender = window.setTimeout(() => setApps(apps), 150);
}

// ---------- apps, launcher, recents ----------

let apps: AppInfo[] = [];
let current: string | null = null;
let recents: AppInfo[] = loadRecents();
/** Apps pinned to the top of the sidebar (shown there whether running or not). */
let pins: AppInfo[] = loadPins();

function loadPins(): AppInfo[] {
  try { return parsePins(localStorage.getItem(PINS_KEY)); } catch { return []; }
}

function setPins(list: AppInfo[]) {
  pins = list;
  try { localStorage.setItem(PINS_KEY, JSON.stringify(pins)); } catch { /* quota: icons too big */ }
  renderRecents();
}

function loadRecents(): AppInfo[] {
  try {
    const r = JSON.parse(localStorage.getItem('tslink.recents') || '[]');
    return Array.isArray(r) ? r.filter((a) => a && typeof a.pkg === 'string').slice(0, MAX_RECENTS) : [];
  } catch { return []; }
}

/** Only PNG data URLs from the phone; anything else gets the letter fallback. */
const vettedIcon = (a: AppInfo): string | undefined => (validIcon(a.icon) ? a.icon : undefined);

function appIcon(a: AppInfo): HTMLElement {
  return iconEl(a.label, vettedIcon(a));
}

/** `src` must already be a vetted data: URL. */
function iconEl(label: string, src: string | undefined): HTMLElement {
  if (src) {
    const img = document.createElement('img');
    img.src = src;
    img.alt = '';
    img.draggable = false;
    return img;
  }
  const ph = document.createElement('div');
  ph.className = 'ph';
  ph.textContent = Array.from((typeof label === 'string' && label) || '?')[0].toUpperCase();
  return ph;
}

function setApps(list: AppInfo[]) {
  apps = list.filter((a) => a && typeof a.pkg === 'string');
  recents = refreshRecents(recents, apps);
  pins = refreshRecents(pins, apps);
  renderRecents();
  $('launcher-bar').hidden = apps.length === 0;
  renderGrid();
  updateLauncherMsg();
}

// Launcher search: typed text narrows the grouped view to one flat, sorted list.
let query = '';

function appTile(a: AppInfo): HTMLButtonElement {
  const b = document.createElement('button');
  b.className = 'app';
  const label = document.createElement('span');
  label.textContent = a.label || a.pkg;
  b.append(appIcon(a), label);
  attachLongPress(b, () => openSheet(a)); // before the tap handler: it swallows the lift's click
  b.addEventListener('click', () => launch(a));
  return b;
}

function appSection(title: string, list: AppInfo[]): HTMLElement {
  const section = document.createElement('section');
  section.className = 'app-group';
  const h = document.createElement('h2');
  h.textContent = title;
  const grid = document.createElement('div');
  grid.className = 'app-grid';
  grid.append(...list.map(appTile));
  section.append(h, grid);
  return section;
}

function renderGrid() {
  const searching = !!query.trim();
  $('search-clear').hidden = !searching && !query;
  if (!searching) {
    $('grid').replaceChildren(...groupApps(apps).map((g) => appSection(g.title, g.apps)));
    return;
  }
  const list = filterApps(apps, query);
  const title = `Apps matching “${query.trim()}”`;
  if (list.length) {
    $('grid').replaceChildren(appSection(title, list));
  } else {
    const none = document.createElement('div');
    none.className = 'no-match';
    none.textContent = `No apps match “${query.trim()}”`;
    $('grid').replaceChildren(none);
  }
}

function setQuery(next: string) {
  const input = $<HTMLInputElement>('search');
  query = next;
  if (input.value !== query) input.value = query;
  renderGrid();
  $('launcher').scrollTop = 0;
}

$('search').addEventListener('input', () => setQuery($<HTMLInputElement>('search').value));
$('search').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('search').blur(); });
$('search-clear').addEventListener('click', () => { setQuery(''); $('search').blur(); });

// ---------- app actions (long press on a launcher tile or a sidebar app) ----------

let sheetApp: AppInfo | null = null;
let sheetShownAt = 0;

function openSheet(a: AppInfo) {
  sheetApp = a;
  sheetShownAt = performance.now();
  $('sheet-icon').replaceChildren(appIcon(a));
  $('sheet-label').textContent = a.label || a.pkg;
  const pinned = isPinned(pins, a);
  const full = !pinned && pins.length >= MAX_PINS;
  const pin = $<HTMLButtonElement>('sheet-pin');
  if (pinned) pin.textContent = 'Unpin from sidebar';
  else if (full) pin.textContent = `Sidebar full (${MAX_PINS}): unpin one first`;
  else pin.textContent = 'Pin to sidebar';
  pin.disabled = full;
  $('sheet-close').hidden = !recents.some((r) => r.pkg === a.pkg); // only running apps
  $('sheet').hidden = false;
}

function closeSheet() {
  sheetApp = null;
  $('sheet').hidden = true;
}

/** A sheet action, unless the tap came right as the sheet appeared (the finger was still on the tile). */
function sheetAction(fn: (a: AppInfo) => void) {
  return (e: Event) => {
    e.stopPropagation();
    if (performance.now() - sheetShownAt < TAP_GUARD_MS || !sheetApp) return;
    const a = sheetApp;
    closeSheet();
    fn(a);
  };
}

$('sheet-open').addEventListener('click', sheetAction((a) => launch(a)));
$('sheet-pin').addEventListener('click', sheetAction((a) => {
  if (isPinned(pins, a)) { setPins(removePin(pins, a)); return; }
  const next = addPin(pins, a);
  if (next) { setPins(next); toast(`${a.label || a.pkg} pinned to the sidebar`); }
}));
$('sheet-close').addEventListener('click', sheetAction((a) => closeApp(a.pkg)));
$('sheet-cancel').addEventListener('click', sheetAction(() => {}));
// A tap beside the card closes it.
$('sheet').addEventListener('click', (e) => {
  if (e.target === $('sheet') && performance.now() - sheetShownAt >= TAP_GUARD_MS) closeSheet();
});

function updateLauncherMsg() {
  let msg = '';
  if (!apps.length) {
    msg = lastStatus.s === 'connected' ? 'Loading apps…' : lastStatus.text || 'Not connected';
  }
  $('launcher-msg').textContent = msg;
}

function launch(a: AppInfo) {
  send({ t: 'launch', pane: 0, pkg: a.pkg, cls: a.cls });
  if (a.pkg !== current) showOpeningApp(a);
  showTray(false);
  current = a.pkg;
  setRecents([a, ...recents.filter((r) => !(r.pkg === a.pkg && r.cls === a.cls))]);
  showLauncher(false);
}

function setRecents(list: AppInfo[]) {
  recents = list.slice(0, MAX_RECENTS);
  try { localStorage.setItem('tslink.recents', JSON.stringify(recents)); } catch { /* quota: icons too big */ }
  renderRecents();
}

/**
 * Close one app: the sidebar moves on to the next app (already running, so it's
 * instant) or the launcher. The next app is brought up before the closed one
 * goes, so the car never sees what happens to be underneath.
 */
function closeApp(pkg: string) {
  // Closing an app doesn't stop its music (Android keeps the player service):
  // closing the one that plays means "stop it".
  if (nowPlaying?.pkg === pkg && nowPlaying.playing) mediaCmd('pause', true);
  const r = afterClose(recents, current, pkg);
  if (r.next) {
    send({ t: 'launch', pane: 0, pkg: r.next.pkg, cls: r.next.cls });
    showOpeningApp(r.next);
    current = r.next.pkg;
  } else if (r.wasCurrent) {
    current = null;
    showLauncher(true);
  }
  send({ t: 'close', pane: 0, pkg });
  $('recents').querySelectorAll<HTMLElement>('.recent').forEach((el) => {
    el.classList.toggle('current', el.dataset.pkg === current);
    // A pinned app stays on the sidebar, just no longer running.
    if (el.dataset.pkg !== pkg) return;
    if (el.classList.contains('pinned')) el.classList.remove('running');
    else el.classList.add('leaving');
  });
  // Re-launched within the animation? Then it stays on the sidebar.
  setTimeout(() => setRecents(recents.filter((a) => a.pkg !== pkg || a.pkg === current)), LEAVE_MS);
}


/** Sidebar apps: the pinned ones on top, a line, then recents that aren't pinned. */
function renderRecents() {
  const rest = unpinnedRecents(recents, pins);
  const divider = document.createElement('div');
  divider.className = 'rail-divider';
  $('recents').replaceChildren(
    ...pins.map((a) => railApp(a, true)),
    ...(pins.length ? [divider] : []),
    ...rest.map((a) => railApp(a, false)),
  );
}

function railApp(a: AppInfo, pinned: boolean): HTMLButtonElement {
  const b = document.createElement('button');
  const running = recents.some((r) => r.pkg === a.pkg);
  b.className = 'rail-btn recent' + (pinned ? ' pinned' : '') + (pinned && running ? ' running' : '') + (a.pkg === current ? ' current' : '');
  b.dataset.pkg = a.pkg;
  b.setAttribute('aria-label', pinned ? `${a.label} (pinned)` : a.label);
  b.append(appIcon(a));
  attachLongPress(b, () => openSheet(a)); // hold for the menu: open, pin, close
  b.addEventListener('click', () => launch(a));
  return b;
}

let launcherOn = false;
function showLauncher(on: boolean) {
  if (on) { $('opening').hidden = true; showTray(false); }
  else if (query) setQuery(''); // the full launcher next time
  launcherOn = on;
  $('launcher').hidden = !on;
  $('btn-home').classList.toggle('active', on);
}

$('btn-home').addEventListener('click', () => {
  const on = !launcherOn;
  if (on) send({ t: 'key', pane: 0, k: 'home' });
  showLauncher(on || !current); // until something is launched, there's nothing behind it
});
$('btn-back').addEventListener('click', () => {
  if (launcherOn && current) { showLauncher(false); return; }
  send({ t: 'key', pane: 0, k: 'back' });
});

// ---------- clock + debug ----------

/** Battery and signal above the clock, like Android Auto's status bar. */
function showPhoneStatus(m: Record<string, unknown>) {
  const s = parsePhoneStatus(m);
  $('phone-status').hidden = s.battery === null && s.signal === null;
  const sig = $('signal');
  sig.hidden = s.signal === null;
  sig.dataset.bars = String(Math.max(0, s.signal ?? 0));
  sig.classList.toggle('none', s.signal === -1);
  $('net').textContent = s.signal === -1 ? '✕' : s.network;
  const bat = $('battery');
  bat.hidden = $('battery-pct').hidden = s.battery === null;
  $('battery-fill').style.width = `calc(${s.battery ?? 0}% - 2px)`;
  bat.classList.toggle('low', (s.battery ?? 100) <= 15 && !s.charging);
  bat.classList.toggle('charging', s.charging);
  $('battery-pct').textContent = s.battery === null ? '' : `${s.battery}${s.charging ? '⚡' : ''}`;
  $('clock').setAttribute('aria-label', `Clock (tap for debug info). ${describePhone(s)}`);
}

function tickClock() {
  const d = new Date();
  $('clock-time').textContent = `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
}
tickClock();
setInterval(tickClock, 5000);

$('debug').hidden = !debugOn;
$('clock').addEventListener('click', () => {
  debugOn = !debugOn;
  $('debug').hidden = !debugOn;
  renderDebug();
});
if (debugOn) void detectCodecs();
setInterval(renderDebug, 1000); // the clock line, even when no stats arrive

let swReg: ServiceWorkerRegistration | null = null;
function swState(): string {
  if (!('serviceWorker' in navigator)) return 'unsupported';
  if (!import.meta.env.PROD) return 'off (dev server)';
  const ctl = navigator.serviceWorker.controller ? 'controlled' : 'no controller';
  if (!swReg) return `${ctl}, not registered`;
  return ctl + (swReg.waiting ? ', update waiting' : swReg.installing ? ', update installing' : '');
}

/** This week's certificate fingerprint for the code in use; cached (signing isn't free). */
let fpCache = { key: '', fp: '' };
function certFingerprint(code: string, unixSecs: number): string {
  if (!code) return '-';
  const key = `${code}|${weekStart(unixSecs)}`;
  if (fpCache.key !== key) fpCache = { key, fp: fingerprint(makeCert(code, unixSecs).hash) };
  return fpCache.fp;
}

const ms = (v: number | null) => (v === null ? '  -  ' : v.toFixed(1).padStart(5));
function renderDebug() {
  if (!debugOn) return;
  const lines: string[] = [];
  lines.push(DEMO ? 'mode   demo' : `conn   ${lastStatus.s}${phoneName ? ` (${phoneName})` : ''}`);
  lines.push(`rtt    p50 ${ms(rtt.pct(0.5))}  p95 ${ms(rtt.pct(0.95))} ms`);
  if (rateKbps) lines.push(`rate   ${(rateKbps / 1000).toFixed(1)} Mbit/s target`);
  for (const p of panes) {
    const s = p.stats;
    lines.push(`-- pane ${p.id}`);
    lines.push(`size   ${p.layout.w}x${p.layout.h} req` + (p.video ? `, ${p.video.w}x${p.video.h} phone` : '')
      + (s?.width ? `, ${s.width}x${s.height} video` : ''));
    if (!s) continue;
    lines.push(`codec  ${s.codec ?? '-'}${s.waitingKey ? '  (waiting for keyframe)' : ''}`);
    lines.push(`fps    ${s.fps}  gap max ${s.maxGapMs ?? '-'} ms  stalls ${s.stalls}`);
    lines.push(`decode p50 ${ms(s.decodeP50)}  p95 ${ms(s.decodeP95)} ms  queue ${s.queue}`);
    lines.push(`drop   decoded ${s.droppedDecoded}  encoded ${s.droppedEncoded}  errors ${s.errors}`);
  }
  lines.push(`dpr    ${devicePixelRatio}  window ${innerWidth}x${innerHeight}`);
  const now = Math.floor(Date.now() / 1000);
  lines.push('-- env');
  lines.push(`build  ${BUILD}`);
  lines.push(`ua     ${shortUA(navigator.userAgent)}`);
  lines.push(`api    WebTransport ${typeof WebTransport}  VideoDecoder ${typeof VideoDecoder}`);
  lines.push(`codecs ${detected ? detected.join(',') || 'none' : '…'}${h264Only ? '  (offering h264 only: HEVC errors)' : ''}`);
  lines.push(`sw     ${swState()}`);
  lines.push(`utc    ${utcStamp(now)}`);
  lines.push(`cert   week ${utcStamp(weekStart(now)).slice(0, 10)}  fp ${certFingerprint(settings.code, now)}`);
  const log = logRing.lines();
  if (log.length) lines.push('-- log', ...log);
  $('debug').textContent = lines.join('\n');
}

// ---------- pairing / settings ----------

// ---------- QR pairing ----------

/** The one-time secret shown as a QR code, while pairing; it stands in for the code. */
let pairingSecret: string | null = null;
/** Just paired: the phone switches back to the real code; failed tries meanwhile are expected. */
let pairedQuietUntil = 0;
/** Fast reconnects after pairing, while the phone switches to the real code. */
const PAIRED_QUIET_MS = 8000;
const pace = () => pairingPace(!!pairingSecret, performance.now(), pairedQuietUntil);

function startQrPairing() {
  pairingSecret = newSecret();
  unanswered = 0;
  const canvas = $<HTMLCanvasElement>('pair-qr-canvas');
  drawQr(canvas, qrModules(qrText(pairingSecret)), 300, devicePixelRatio || 1);
  $('pair-qr-status').textContent = 'Waiting for your phone…';
  $('pair-qr-status').classList.remove('pair-warn');
  $('pair-qr').hidden = false;
  $('pair-done').hidden = true;
  $('pairing-form').hidden = true;
  // Connect with the secret; the phone accepts it once it has scanned the code.
  useCode(pairingSecret);
  conn.restart();
}

/** Connect with [code] at the saved address. */
function useCode(code: string) {
  const { host, port } = effective(overrides); // saved address, or a dev override
  settings = { ...settings, code, host, port };
}

/** Leave QR pairing without a result: back to the saved code (if any). */
function stopQrPairing() {
  if (!pairingSecret) return;
  pairingSecret = null;
  const { code } = loadSaved();
  useCode(code);
  if (code) conn.restart();
  else conn.stop(); // nothing to connect with until a code is typed
}

function onPaired(raw: unknown) {
  const code = pairedCode(raw);
  if (!pairingSecret || !code) return; // only the car that showed a QR code asked for it
  pairingSecret = null;
  // Keep the address that worked (the hotspot default in the car).
  const s: Settings = { ...loadSaved(), code, host: settings.host, port: settings.port };
  save(s);
  settings = { ...settings, code, host: s.host, port: s.port };
  showPairing(false);
  toast('Paired with your phone');
  // Leaving the pairing session tells the phone to switch to the real code;
  // reconnect at once and keep trying fast until it has.
  pairedQuietUntil = performance.now() + PAIRED_QUIET_MS;
  conn.restart();
}

$('pair-manual').addEventListener('click', () => {
  stopQrPairing();
  $('pair-qr').hidden = true;
  $('pair-done').hidden = true;
  $('pairing-form').hidden = false;
});
$('pair-scan').addEventListener('click', startQrPairing);
$('pair-qr-cancel').addEventListener('click', () => { stopQrPairing(); showPairing(false); });
$('pair-again').addEventListener('click', startQrPairing);
$('pair-done-close').addEventListener('click', () => showPairing(false));

const form = $<HTMLFormElement>('pairing-form');
function showPairing(on: boolean) {
  $('pairing').hidden = !on;
  const codeEl = $<HTMLInputElement>('f-code');
  codeEl.value = ''; // never keep a typed code around in the hidden form
  if (!on) return;
  const s = loadSaved();
  // The saved code is never shown: masked placeholder, empty field keeps it.
  codeEl.placeholder = s.code ? maskCode(s.code) : 'ABCD-EFGH-JKLM';
  codeEl.required = !s.code;
  $('f-code-hint').textContent = s.code ? 'Saved — leave empty to keep it, or type a new code' : '';
  $<HTMLInputElement>('f-host').value = s.host;
  $<HTMLInputElement>('f-port').value = String(s.port);
  $('f-error').textContent = '';
  $('f-address-error').textContent = '';
  $('f-cancel').hidden = !s.code && !DEMO;
  $('pair-qr-cancel').hidden = !s.code && !DEMO;
  // One screen for the first visit and the gear: the QR code first, typing
  // one tap away; a live paired session isn't dropped for a new QR code.
  $('pairing-form').hidden = true;
  if (pairingView(!!s.code, lastStatus.s === 'connected') === 'paired') {
    $('pair-qr').hidden = true;
    $('pair-done').hidden = false;
  } else if (!pairingSecret) {
    startQrPairing();
  }
}

// Upper case and dashes while typing, so the code reads like the phone shows it.
$<HTMLInputElement>('f-code').addEventListener('input', (e) => {
  const el = e.target as HTMLInputElement;
  const { value, caret } = formatCode(el.value, el.selectionStart ?? el.value.length);
  if (value !== el.value) {
    el.value = value;
    el.setSelectionRange(caret, caret);
  }
});

// The phone's address, on every pairing view (a QR code too): keeps the code
// or QR secret in use and reconnects there. Normally the car hotspot default.
$('f-address-use').addEventListener('click', () => {
  const host = $<HTMLInputElement>('f-host').value.trim();
  const portText = $<HTMLInputElement>('f-port').value;
  const err = addressError(host, portText);
  $('f-address-error').textContent = err;
  if (err) return;
  const port = validPort(portText)!;
  save({ ...loadSaved(), host, port });
  settings = { ...settings, host, port };
  if (!DEMO) conn.restart();
  toast(`Connecting to ${host}:${port}`);
});

form.addEventListener('submit', (e) => {
  e.preventDefault();
  const saved = loadSaved();
  const code = resolveCode($<HTMLInputElement>('f-code').value, saved.code);
  const host = $<HTMLInputElement>('f-host').value.trim();
  const port = validPort($<HTMLInputElement>('f-port').value);
  const err = code.replace(/[^A-Z0-9]/g, '').length < 4 ? 'Enter the pairing code shown on the phone.'
    : addressError(host, $<HTMLInputElement>('f-port').value);
  $('f-error').textContent = err;
  if (err) return;
  pairingSecret = null; // typed a code instead
  const s: Settings = { ...saved, code, host, port: port! };
  save(s);
  const reconnect = !sameEndpoint(s, settings);
  settings = { ...settings, code, host, port: port! };
  showPairing(false);
  if (reconnect && !DEMO) conn.restart();
});

// Full screen, where the browser allows it (the Tesla browser's support is
// untested; no section at all without it). The pane re-measures on resize and
// the phone follows. Message text is the phone's setting (TSLink > Notifications).
$('display').hidden = !document.fullscreenEnabled;
const syncFullscreen = () => { $('f-fullscreen').textContent = document.fullscreenElement ? 'Exit full screen' : 'Full screen'; };
document.addEventListener('fullscreenchange', syncFullscreen);
$('f-fullscreen').addEventListener('click', () => {
  const p = document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen({ navigationUI: 'hide' });
  p.catch((e: unknown) => toast(`Full screen not available: ${e instanceof Error ? e.message : String(e)}`));
});

$('f-cancel').addEventListener('click', () => { stopQrPairing(); showPairing(false); });
$('btn-settings').addEventListener('click', () => showPairing(true));

// ---------- start ----------

showLauncher(true);
renderRecents();

if (DEMO) {
  showStatus({ s: 'connected', text: 'Demo mode' });
  const p = panes[0];
  p.video = { w: DEMO_W, h: DEMO_H };
  toWorker({ type: 'reset' });
  toWorker({ type: 'pane', pane: 0, w: DEMO_W, h: DEMO_H, codec: 'h264' });
  setApps(demoApps());
  // Fake phone notifications through the real message path, for QA.
  for (const [ms, m] of demoNotifs()) setTimeout(() => onMessage(m), ms);
  onMessage({ t: 'phone', bat: 76, chg: true, sig: 3, net: 'LTE' });
  onMessage({ t: 'media', pkg: 'com.spotify.music', app: 'Spotify', title: 'Night Drive', artist: 'Demo Band', playing: true, prev: true, next: true });
  void startDemo(forwardFrame, (msg) => showStatus({ s: 'error', text: msg }));
} else if (!settings.code) {
  showStatus({ s: 'idle', text: 'Not paired' });
  showPairing(true); // first visit: the QR code (typing stays one tap away)
} else {
  conn.start();
}

if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  // Updates: a new version installs alongside and waits. It's switched to
  // only while starting up (never mid-drive), then the page reloads into it.
  // Not on first install, when there was no previous version.
  let hadController = !!navigator.serviceWorker.controller;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (!hadController) { hadController = true; return; }
    if (overrides) sessionStorage.setItem(OVERRIDE_KEY, overrides);
    location.reload();
  });
  const startedAt = performance.now();
  const activate = (w: ServiceWorker | null) => {
    if (w && hadController && performance.now() - startedAt < 15_000) w.postMessage('activate');
  };
  navigator.serviceWorker.register('./sw.js').then((reg) => {
    swReg = reg;
    activate(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      w?.addEventListener('statechange', () => { if (w.state === 'installed') activate(w); });
    });
  }).catch((e) => {
    console.warn('service worker', e);
    logLine(`service worker: ${e instanceof Error ? e.message : String(e)}`);
  });
}

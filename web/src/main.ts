// Main thread: UI (rail, launcher, pairing), connection, touch, debug overlay.
// Video bytes are handed to the decode worker untouched.

import './style.css';
import { Connection, type Status } from './connection';
import { demoApps, demoNotifs, DEMO_H, DEMO_W, startDemo } from './demo';
import type { Codec } from './h26x';
import { PANE_DPI, type AppInfo, type FromWorker, type PageMsg, type PaneLayout, type PaneStats, type PhoneMsg, type ToWorker } from './protocol';
import { makeCert, weekStart } from './cert';
import { ErrorWindow, fingerprint, LogRing, maskCode, resolveCode, shortUA, utcStamp } from './diag';
import { afterClose, groupApps } from './apps';
import { addBanner, nextExpiry, pruneExpired, removeBanner, sanitizeNotif, validId, type Banner, type Notif } from './notifs';
import { effective, loadSaved, paneDpi, sameEndpoint, save, saveDisplay, validPort, type Settings } from './settings';
import { Samples } from './stats';
import { attachTouch } from './touch';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const params = new URLSearchParams(location.search);
const DEMO = params.get('demo') === '1';
const MAX_RECENTS = 5;
/** Time for a closed app's rail icon to shrink away (.recent.leaving) before it's dropped. */
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

/** Pane sizes in device pixels: CSS size × devicePixelRatio, rounded down to even; dpi to match. */
function measureLayout(): boolean {
  let changed = false;
  for (const p of panes) {
    const r = p.el.getBoundingClientRect();
    const w = even(r.width * devicePixelRatio);
    const h = even(r.height * devicePixelRatio);
    const dpi = paneDpi(w, h, devicePixelRatio);
    if (w !== p.layout.w || h !== p.layout.h || dpi !== p.layout.dpi) { p.layout = { id: p.id, w, h, dpi }; changed = true; }
  }
  return changed;
}
const sendLayout = () => { if (measureLayout()) send({ t: 'layout', panes: panes.map((p) => p.layout) }); };
const layout = (): PaneLayout[] => { measureLayout(); return panes.map((p) => p.layout); };

let layoutTimer = 0;
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
const OVERRIDE_KEY = 'carlink.override';
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
    rtt.clear();
    for (const p of panes) p.video = null;
    toWorker({ type: 'reset' });
    // Banners of the previous session would never get their notif-gone.
    setBanners([]);
  },
  onFrame: forwardFrame,
  onLost: (buf) => toWorker({ type: 'lost', buf }, [buf]),
  onRtt: (ms) => rtt.push(ms),
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
        const a = current ? recents.find((r) => r.pkg === current) : undefined;
        if (a) send({ t: 'launch', pane: 0, pkg: a.pkg, cls: a.cls });
      }
      break;
    }
    case 'apps': setApps(Array.isArray(m.list) ? m.list : []); break;
    case 'rate': rateKbps = Number(m.kbps) || 0; break;
    case 'error': toast(`Phone: ${m.msg}`); break;
    case 'notif': {
      const n = sanitizeNotif(m);
      if (n) setBanners(addBanner(banners, n, performance.now()));
      break;
    }
    case 'notif-gone': if (validId(m.id)) setBanners(removeBanner(banners, m.id)); break;
  }
}

// ---------- notification banners ----------

let banners: Banner[] = [];
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
/** What each banner element currently shows, to skip refilling unchanged ones. */
const filled = new WeakMap<HTMLElement, { n: Notif; showText: boolean }>();
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
    send({ t: 'notif-open', id });
    setBanners(removeBanner(banners, id));
  });
  const close = document.createElement('button');
  close.className = 'notif-close';
  close.setAttribute('aria-label', 'Dismiss');
  close.appendChild(closeIcon());
  close.addEventListener('click', () => setBanners(removeBanner(banners, id)));
  el.append(body, close);
  bannerEls.set(id, el);
  return el;
}

/** Fill a banner from (sanitized) phone data: textContent only. */
function fillBanner(el: HTMLElement, n: Notif) {
  const f = filled.get(el);
  if (f && f.n === n && f.showText === settings.showText) return;
  filled.set(el, { n, showText: settings.showText });
  shownAt.set(el, performance.now());
  el.className = `notif ${n.kind}`;
  const body = el.firstElementChild as HTMLElement;
  const texts = document.createElement('div');
  texts.className = 'notif-texts';
  const line = (cls: string, text: string) => {
    const d = document.createElement('div');
    d.className = cls;
    d.textContent = text;
    return d;
  };
  texts.append(line('notif-app', n.app), line('notif-title', n.title));
  if (settings.showText) texts.append(line('notif-text', n.text));
  body.replaceChildren(iconEl(n.app, n.icon), texts);
  body.setAttribute('aria-label', `Open ${n.app}: ${n.title}`);
}

function renderBanners() {
  const host = $('notifs');
  const ids = new Set(banners.map((b) => b.n.id));
  for (const [id, el] of bannerEls) if (!ids.has(id)) { el.remove(); bannerEls.delete(id); }
  // Insert/move only what changed: re-inserting a node would replay its entrance animation.
  banners.forEach((b, i) => {
    const el = bannerEl(b.n.id);
    fillBanner(el, b.n);
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
  } else if (m.type === 'log') {
    console.log(`[worker] ${m.text}`);
    logLine(m.text);
  }
};

// ---------- status ----------

let statusTimer = 0;
let lastStatus: Status = { s: 'idle', text: '' };
function showStatus(st: Status) {
  // The retry countdown changes each time; without it, repeats collapse in the log.
  if (st.s === 'error') logLine(st.text.replace(/ · retrying in .*$/, ''));
  lastStatus = st;
  const el = $('status');
  el.className = st.s;
  el.textContent = st.text;
  $('conn-dot').className = st.s;
  clearTimeout(statusTimer);
  // Connected: show briefly, then get out of the way of the video.
  if (st.s === 'connected') statusTimer = window.setTimeout(() => { el.textContent = ''; }, 2500);
  updateLauncherMsg();
}

let toastTimer = 0;
function toast(text: string) {
  $('toast').textContent = text;
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { $('toast').textContent = ''; }, 4000);
}

// ---------- apps, launcher, recents ----------

let apps: AppInfo[] = [];
let current: string | null = null;
let recents: AppInfo[] = loadRecents();

function loadRecents(): AppInfo[] {
  try {
    const r = JSON.parse(localStorage.getItem('carlink.recents') || '[]');
    return Array.isArray(r) ? r.filter((a) => a && typeof a.pkg === 'string').slice(0, MAX_RECENTS) : [];
  } catch { return []; }
}

function appIcon(a: AppInfo): HTMLElement {
  // Only PNG data URLs from the phone; anything else gets the letter fallback.
  return iconEl(a.label, typeof a.icon === 'string' && a.icon.startsWith('data:image/png;base64,') ? a.icon : undefined);
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
  ph.textContent = Array.from(label || '?')[0].toUpperCase();
  return ph;
}

function setApps(list: AppInfo[]) {
  apps = list.filter((a) => a && typeof a.pkg === 'string');
  // Refresh recents' icons/labels from the phone's list.
  recents = recents.map((r) => apps.find((a) => a.pkg === r.pkg && a.cls === r.cls) ?? r);
  renderRecents();
  $('grid').replaceChildren(...groupApps(apps).map((g) => {
    const section = document.createElement('section');
    section.className = 'app-group';
    const h = document.createElement('h2');
    h.textContent = g.title;
    const grid = document.createElement('div');
    grid.className = 'app-grid';
    grid.append(...g.apps.map((a) => {
      const b = document.createElement('button');
      b.className = 'app';
      const label = document.createElement('span');
      label.textContent = a.label || a.pkg;
      b.append(appIcon(a), label);
      b.addEventListener('click', () => launch(a));
      return b;
    }));
    section.append(h, grid);
    return section;
  }));
  updateLauncherMsg();
}

function updateLauncherMsg() {
  let msg = '';
  if (!apps.length) {
    msg = lastStatus.s === 'connected' ? 'Loading apps…' : lastStatus.text || 'Not connected';
  }
  $('launcher-msg').textContent = msg;
}

function launch(a: AppInfo) {
  send({ t: 'launch', pane: 0, pkg: a.pkg, cls: a.cls });
  current = a.pkg;
  setRecents([a, ...recents.filter((r) => !(r.pkg === a.pkg && r.cls === a.cls))]);
  showLauncher(false);
}

function setRecents(list: AppInfo[]) {
  recents = list.slice(0, MAX_RECENTS);
  try { localStorage.setItem('carlink.recents', JSON.stringify(recents)); } catch { /* quota: icons too big */ }
  disarm();
  renderRecents();
}

/**
 * Close one app: the rail moves on to the next app (already running, so it's
 * instant) or the launcher. The next app is brought up before the closed one
 * goes, so the car never sees what happens to be underneath.
 */
function closeApp(pkg: string) {
  const r = afterClose(recents, current, pkg);
  if (r.next) {
    send({ t: 'launch', pane: 0, pkg: r.next.pkg, cls: r.next.cls });
    current = r.next.pkg;
  } else if (r.wasCurrent) {
    current = null;
    showLauncher(true);
  }
  send({ t: 'close', pane: 0, pkg });
  disarm();
  $('recents').querySelectorAll<HTMLElement>('.recent').forEach((el) => {
    el.classList.toggle('current', el.dataset.pkg === current);
    if (el.dataset.pkg === pkg) el.classList.add('leaving');
  });
  // Re-launched within the animation? Then it stays on the rail.
  setTimeout(() => setRecents(recents.filter((a) => a.pkg !== pkg || a.pkg === current)), LEAVE_MS);
}

function closeAll() {
  send({ t: 'close-all', pane: 0 });
  current = null;
  $('recents').querySelectorAll('.recent').forEach((el) => el.classList.add('leaving'));
  showLauncher(true);
  setTimeout(() => setRecents([]), LEAVE_MS);
}

// Destructive rail actions take two taps: the first arms (shows ×), the second acts.
let armed: string | null = null; // package, or '*' for close-all
let armTimer = 0;
function arm(what: string) {
  armed = what;
  clearTimeout(armTimer);
  armTimer = window.setTimeout(disarm, 3000);
  renderArmed();
}
function disarm() {
  armed = null;
  clearTimeout(armTimer);
  renderArmed();
}
function renderArmed() {
  $('recents').querySelectorAll<HTMLElement>('.recent').forEach((el) => el.classList.toggle('armed', el.dataset.pkg === armed));
  $('btn-closeall').classList.toggle('armed', armed === '*');
}

function renderRecents() {
  $('recents').replaceChildren(...recents.map((a) => {
    const b = document.createElement('button');
    b.className = 'rail-btn recent' + (a.pkg === current ? ' current' : '');
    b.dataset.pkg = a.pkg;
    b.setAttribute('aria-label', a.label);
    const x = document.createElement('span');
    x.className = 'close-badge';
    x.appendChild(closeIcon());
    b.append(appIcon(a), x);
    b.addEventListener('click', () => {
      if (armed === a.pkg) closeApp(a.pkg);
      // Tapping the app already in front again offers to close it.
      else if (a.pkg === current && !launcherOn) arm(a.pkg);
      else launch(a);
    });
    return b;
  }));
  $('btn-closeall').hidden = recents.length === 0;
  renderArmed(); // a re-render (new app list) keeps a pending close visible
}

let launcherOn = false;
function showLauncher(on: boolean) {
  if (on !== launcherOn) disarm(); // a close armed for the app in front no longer applies
  launcherOn = on;
  $('launcher').hidden = !on;
  $('btn-home').classList.toggle('active', on);
}

$('btn-home').addEventListener('click', () => {
  const on = !launcherOn;
  if (on) send({ t: 'key', pane: 0, k: 'home' });
  showLauncher(on || !current); // until something is launched, there's nothing behind it
});
$('btn-closeall').addEventListener('click', () => {
  if (armed === '*') closeAll();
  else arm('*');
});
$('btn-back').addEventListener('click', () => {
  if (launcherOn && current) { showLauncher(false); return; }
  send({ t: 'key', pane: 0, k: 'back' });
});

// ---------- clock + debug ----------

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
    lines.push(`fps    ${s.fps}`);
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
  $('f-cancel').hidden = !s.code && !DEMO;
  renderDisplay();
}

form.addEventListener('submit', (e) => {
  e.preventDefault();
  const saved = loadSaved();
  const code = resolveCode($<HTMLInputElement>('f-code').value, saved.code);
  const host = $<HTMLInputElement>('f-host').value.trim();
  const port = validPort($<HTMLInputElement>('f-port').value);
  const err = code.replace(/[^A-Z0-9]/g, '').length < 4 ? 'Enter the pairing code shown on the phone.'
    : !host ? 'Enter the phone address.' : !port ? 'Port must be 1–65535.' : '';
  $('f-error').textContent = err;
  if (err) return;
  const s: Settings = { ...saved, code, host, port: port! };
  save(s);
  const reconnect = !sameEndpoint(s, settings);
  settings = { ...settings, code, host, port: port! };
  showPairing(false);
  if (reconnect && !DEMO) conn.restart();
});

// Display settings apply (and are saved) on tap, without the pairing form.
function renderDisplay() {
  $('f-showtext').setAttribute('aria-checked', String(settings.showText));
}
// Full screen, where the browser allows it (the Tesla browser's support is
// untested). The pane re-measures on resize and the phone follows.
$('fullscreen-row').hidden = !document.fullscreenEnabled;
const syncFullscreen = () => { $('f-fullscreen').textContent = document.fullscreenElement ? 'Exit full screen' : 'Full screen'; };
document.addEventListener('fullscreenchange', syncFullscreen);
$('f-fullscreen').addEventListener('click', () => {
  const p = document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen({ navigationUI: 'hide' });
  p.catch((e: unknown) => toast(`Full screen not available: ${e instanceof Error ? e.message : String(e)}`));
});

$('f-showtext').addEventListener('click', () => {
  settings = { ...settings, showText: !settings.showText };
  saveDisplay(settings);
  renderDisplay();
  renderBanners();
});
$('f-cancel').addEventListener('click', () => showPairing(false));
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
  void startDemo(forwardFrame, (msg) => showStatus({ s: 'error', text: msg }));
} else if (!settings.code) {
  showStatus({ s: 'idle', text: 'Not paired' });
  showPairing(true);
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

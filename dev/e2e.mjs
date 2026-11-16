// End-to-end / soak test: headless Chromium at the Tesla browser's size drives
// the real car page against the real engine (emulator or phone over adb).
//
// It connects, opens Maps, keeps panning it (motion = real video load), sends
// an SMS mid-run and checks the banner, samples the debug overlay, and
// compares engine and page memory at start and end.
//
// Usage: node dev/e2e.mjs --code XXXX-XXXX-XXXX [--host 127.0.0.1 --port 24433]
//          [--seconds 120] [--label name] [--page http://127.0.0.1:4173/] [--idle 1]
// Needs: `vite preview` serving web/dist, the engine running, adb, chromium.
// Exit code 0 = all checks passed. Prints a JSON summary.

import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]]);
  return acc;
}, []));
const code = args.code;
if (!code) { console.error('--code required'); process.exit(2); }
const host = args.host ?? '127.0.0.1';
const port = args.port ?? '24433';
const seconds = Number(args.seconds ?? 120);
const label = args.label ?? `${host}:${port}`;
const page = args.page ?? 'http://127.0.0.1:4173/';
const W = 1840, H = 880; // Tesla browser, expanded

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => { try { return execFileSync('adb', a, { encoding: 'utf8', timeout: 15000 }); } catch { return ''; } };
// The engine isn't an app process to dumpsys (Shizuku spawns it): look it up by pid.
const enginePssKb = () => {
  const pid = adb('shell', 'pidof', 'app.tslink:engine').trim().split(/\s+/)[0];
  const m = pid ? adb('shell', 'dumpsys', 'meminfo', pid).match(/TOTAL PSS:\s+(\d+)/) : null;
  return m ? Number(m[1]) : null;
};

// ---- browser
const chrome = process.env.CHROME || 'chromium-browser';
const dbg = 9500 + Math.floor(Math.random() * 400);
const proc = spawn(chrome, [
  '--headless=new', `--remote-debugging-port=${dbg}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'e2e-'))}`,
  '--no-first-run', `--window-size=${W},${H}`, '--autoplay-policy=no-user-gesture-required', 'about:blank',
], { stdio: 'ignore' });
process.on('exit', () => proc.kill());

let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch(`http://127.0.0.1:${dbg}/json/list`)).json()).find((t) => t.type === 'page'); } catch { /* starting */ }
}
if (!target) throw new Error('chromium did not start');
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let id = 0;
const pending = new Map();
const consoleErrors = [];
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === 'Runtime.exceptionThrown') consoleErrors.push(m.params.exceptionDetails?.exception?.description ?? 'exception');
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') consoleErrors.push(m.params.args?.map((x) => x.value ?? x.description).join(' '));
});
const send = (method, params = {}) => new Promise((resolve) => {
  const n = ++id;
  pending.set(n, resolve);
  ws.send(JSON.stringify({ id: n, method, params }));
});
const evaluate = async (expression) => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.result?.value;

await send('Runtime.enable');
await send('Performance.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: `${page}?debug=1#code=${encodeURIComponent(code)}&host=${host}&port=${port}` });

const overlay = () => evaluate("document.getElementById('debug')?.textContent ?? ''");
const num = (text, re) => { const m = text.match(re); return m ? Number(m[1]) : null; };
const parse = (t) => ({
  conn: (t.match(/conn\s+(\S+)/) ?? [])[1],
  rttP50: num(t, /rtt\s+p50\s+([\d.]+)/), rttP95: num(t, /rtt\s+p50\s+[\d.-]+\s+p95\s+([\d.]+)/),
  rateMbps: num(t, /rate\s+([\d.]+) Mbit/), fps: num(t, /fps\s+(\d+)/),
  decodeP95: num(t, /decode p50\s+[\d.-]+\s+p95\s+([\d.]+)/),
  droppedDecoded: num(t, /decoded (\d+)/), droppedEncoded: num(t, /encoded (\d+)/), errors: num(t, /errors (\d+)/),
});

const result = { label, seconds, checks: {}, samples: [], notes: [] };
const fail = (k, why) => { result.checks[k] = `FAIL: ${why}`; };
const pass = (k, v = 'ok') => { result.checks[k] = v; };

// ---- connect
let t0 = Date.now();
let s = {};
while (Date.now() - t0 < 30000) { s = parse(await overlay()); if (s.conn === 'connected') break; await sleep(500); }
if (s.conn !== 'connected') { fail('connect', `conn=${s.conn}`); finish(); }
pass('connect', `${Date.now() - t0} ms`);

// ---- open Maps from the launcher
t0 = Date.now();
let opened = false;
while (Date.now() - t0 < 20000 && !opened) {
  opened = await evaluate(`(() => { const b = [...document.querySelectorAll('#grid .app')].find(b => b.textContent.trim() === 'Maps'); if (!b) return false; b.click(); return true; })()`);
  if (!opened) await sleep(500);
}
if (!opened) fail('launch', 'no Maps in launcher'); else pass('launch');
await sleep(4000);

// Pane canvas rectangle, for drags.
const rect = await evaluate(`(() => { const r = document.querySelector('.pane canvas').getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })()`);
const drag = async (x0, y0, x1, y1, steps = 12) => {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: x0, y: y0, button: 'left', clickCount: 1 });
  for (let i = 1; i <= steps; i++) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: x0 + ((x1 - x0) * i) / steps, y: y0 + ((y1 - y0) * i) / steps, button: 'left', buttons: 1 });
    await sleep(16);
  }
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: x1, y: y1, button: 'left', clickCount: 1 });
};

const heap = async () => (await send('Performance.getMetrics')).result?.metrics?.find((m) => m.name === 'JSHeapUsedSize')?.value ?? null;
const pss0 = enginePssKb();
const heap0 = await heap();

// ---- run: pan continuously, sample every 5 s, one SMS at ~1/3
const end = Date.now() + seconds * 1000;
let smsAt = Date.now() + Math.min(20000, (seconds * 1000) / 3);
let smsSeen = null;
let lastSample = 0;
let reconnects = 0;
let wasConnected = true;
let flip = false;
while (Date.now() < end) {
  const { x, y, w, h } = rect;
  flip = !flip;
  // --idle 1: nobody touches the car screen (driving with the map open).
  if (args.idle) await sleep(500);
  else await drag(x + w * (flip ? 0.7 : 0.3), y + h * 0.5, x + w * (flip ? 0.3 : 0.7), y + h * (flip ? 0.4 : 0.6));
  if (smsAt && Date.now() >= smsAt) {
    adb('emu', 'sms', 'send', '5550199', `tslink e2e ${label}`);
    smsAt = 0;
    const tSms = Date.now();
    while (Date.now() - tSms < 8000 && !smsSeen) {
      if (await evaluate(`[...document.querySelectorAll('.notif')].some(n => n.textContent.includes('tslink e2e'))`)) smsSeen = Date.now() - tSms;
      else await sleep(200);
    }
  }
  if (Date.now() - lastSample >= 5000) {
    lastSample = Date.now();
    const p = parse(await overlay());
    result.samples.push({ t: Math.round((end - Date.now()) / 1000), ...p });
    if (p.conn !== 'connected' && wasConnected) reconnects++;
    wasConnected = p.conn === 'connected';
  }
}

const last = parse(await overlay());
const pss1 = enginePssKb();
const heap1 = await heap();
const fpsVals = result.samples.map((x) => x.fps).filter((x) => x != null);
result.summary = {
  fpsMedian: [...fpsVals].sort((a, b) => a - b)[Math.floor(fpsVals.length / 2)] ?? null,
  rttP95: last.rttP95, decodeP95: last.decodeP95, rateMbps: last.rateMbps,
  droppedEncoded: last.droppedEncoded, droppedDecoded: last.droppedDecoded, decodeErrors: last.errors,
  reconnects, smsBannerMs: smsSeen, enginePssKb: [pss0, pss1], jsHeapMb: [heap0, heap1].map((v) => v && +(v / 1e6).toFixed(1)),
  consoleErrors: consoleErrors.slice(0, 5),
};

// ---- verdicts
if (last.conn !== 'connected') fail('still connected', last.conn); else pass('still connected');
// Panning never stops, so a 5 s sample at 0 fps is a frozen picture.
const sortedFps = [...fpsVals].sort((a, b) => a - b);
const fpsMedian = sortedFps[Math.floor(sortedFps.length / 2)] ?? 0;
const frozen = fpsVals.filter((f) => f === 0).length;
if (args.idle) {
  // A still screen sends few frames; what matters is the display staying awake (checked by the caller).
  pass('video', `idle: ${fpsVals.filter((f) => f > 0).length} samples with frames (a still screen sends none)`);
} else {
  if (fpsMedian < 20) fail('video', `median ${fpsMedian} fps`); else pass('video', `median ${fpsMedian} fps`);
  if (frozen > 1) fail('no freezes', `${frozen} frozen samples of ${fpsVals.length}`); else pass('no freezes', `${frozen} of ${fpsVals.length}`);
}
if ((last.errors ?? 0) > 0) fail('decode errors', String(last.errors)); else pass('decode errors', '0');
if (smsSeen == null) fail('notification', 'banner not seen within 8 s'); else pass('notification', `${smsSeen} ms`);
if (reconnects > 0) fail('no reconnects', String(reconnects)); else pass('no reconnects');
if (pss0 && pss1 && pss1 > pss0 * 1.3 && pss1 - pss0 > 20000) fail('engine memory', `${pss0} -> ${pss1} kB`);
else pass('engine memory', pss0 && pss1 ? `${pss0} -> ${pss1} kB` : 'n/a');
if (heap0 && heap1 && heap1 > heap0 * 2 && heap1 - heap0 > 20e6) fail('page memory', `${heap0} -> ${heap1}`); else pass('page memory');
if (consoleErrors.length) fail('console', consoleErrors[0]); else pass('console', 'clean');
finish();

function finish() {
  const ok = Object.values(result.checks).every((v) => !String(v).startsWith('FAIL'));
  result.ok = ok;
  console.log(JSON.stringify(result, (k, v) => (k === "samples" ? v.map((x) => `${x.t}s ${x.conn} ${x.fps}fps ${x.rateMbps}M rtt95 ${x.rttP95}`) : v), 2));
  proc.kill();
  process.exit(ok ? 0 : 1);
}

// Startup profile: what the first seconds of a car session cost on the page.
// Headless Chromium at the Tesla's default browser size (773x601 @ 1.53)
// against the real engine. Prints session -> first frame, app list size and
// handling time, main-thread long tasks, and fps per second.
//
// Usage: TSLINK_CODE=XXXX-XXXX-XXXX node dev/perf-startup.mjs --host 192.168.x.y
//          [--page http://127.0.0.1:4173/] [--seconds 12] [--cpu 4] [--app Waze]
// The pairing code is a secret: it comes from the environment, not the command
// line (visible to other local users), and the temporary browser profile is
// deleted afterwards.
// --cpu N slows the page's CPU N times (the car's computer is slower than a desktop).
// Needs `vite preview` serving web/dist and a running engine that accepts
// this machine (debug build: files/dev_allow).
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).reduce((a, x, i, all) => (x.startsWith('--') ? [...a, [x.slice(2), all[i + 1]]] : a), []));
const { host } = args;
const code = process.env.TSLINK_CODE;
const page = args.page ?? 'http://127.0.0.1:4173/';
const seconds = Number(args.seconds ?? 12);
if (!code || !host) { console.error('TSLINK_CODE and --host required'); process.exit(2); }
const W = 773, H = 601, DPR = 1.53;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const port = 9600 + Math.floor(Math.random() * 300);
const profile = mkdtempSync(join(tmpdir(), 'perf-'));
const proc = spawn(process.env.CHROME || 'chromium-browser', ['--headless=new', `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`, '--no-first-run', `--window-size=${W},${H}`, 'about:blank'], { stdio: 'ignore' });
process.on('exit', () => { proc.kill(); try { rmSync(profile, { recursive: true, force: true }); } catch { /* still locked */ } });
let target;
for (let i = 0; i < 50 && !target; i++) { await sleep(200); try { target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page'); } catch { /* starting */ } }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let id = 0; const pending = new Map();
ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
const send = (method, params = {}) => new Promise((resolve) => { const n = ++id; pending.set(n, resolve); ws.send(JSON.stringify({ id: n, method, params })); });
const ev = async (expression) => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.result?.value;

await send('Runtime.enable');
if (args.cpu) await send('Emulation.setCPUThrottlingRate', { rate: Number(args.cpu) });
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: DPR, mobile: false });
// Long tasks from the very start (registered before the page's scripts run).
await send('Page.enable');
await send('Page.addScriptToEvaluateOnNewDocument', { source: `
  window.__long = [];
  new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push([Math.round(e.startTime), Math.round(e.duration)]); })
    .observe({ type: 'longtask', buffered: true });` });
await send('Page.navigate', { url: `${page}?debug=1#code=${encodeURIComponent(code)}&host=${encodeURIComponent(host)}&port=4433` });

// Open an app from the launcher if nothing is showing (a fresh engine has an
// empty car display, and an empty display sends no frames).
const appLabel = args.app ?? 'Waze';
for (let i = 0; i < 20; i++) {
  const ok = await ev(`(() => { if (document.getElementById('launcher').hidden) return true;
    const b = [...document.querySelectorAll('#grid .app')].find((b) => b.textContent.trim() === ${JSON.stringify(appLabel)});
    if (!b) return false; b.click(); return true; })()`);
  if (ok) break;
  await sleep(250);
}

// Keep the picture moving (drag the map back and forth) so gaps mean freezes,
// and sample the overlay once a second.
const perSecond = [];
const t0 = Date.now();
let dir = 1;
while (Date.now() - t0 < seconds * 1000) {
  const rect = await ev(`(() => { const c = document.querySelector('.pane canvas'); if (!c) return null; const r = c.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })()`);
  const tick = Date.now();
  if (rect && rect.w > 0) {
    const x0 = rect.x + rect.w * 0.5, y0 = rect.y + rect.h * 0.5;
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: x0, y: y0, button: 'left', clickCount: 1 });
    for (let i = 1; i <= 20; i++) {
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: x0 + dir * i * 6, y: y0, button: 'left', buttons: 1 });
      await sleep(30);
    }
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: x0 + dir * 120, y: y0, button: 'left', clickCount: 1 });
    dir = -dir;
  }
  await sleep(Math.max(0, 1000 - (Date.now() - tick)));
  const o = await ev("document.getElementById('debug')?.textContent ?? ''");
  const num = (re) => { const m = o.match(re); return m ? Number(m[1]) : null; };
  perSecond.push({ fps: num(/fps\s+(\d+)/), gapMax: num(/gap max (\d+)/), stalls: num(/stalls (\d+)/), rate: num(/rate\s+([\d.]+)/) });
}
const r = await ev(`(() => {
  const at = (n) => performance.getEntriesByName(n)[0];
  const s = at('tslink:session')?.startTime ?? 0;
  const apps = at('tslink:apps');
  const rel = (x) => x == null ? null : Math.round(x - s);
  return {
    sessionAtMs: Math.round(s),
    firstFrameAfterSessionMs: rel(at('tslink:firstFrame')?.startTime),
    apps: apps && { afterSessionMs: rel(apps.startTime), handleMs: Math.round(apps.duration), count: apps.detail?.count, missingIcons: apps.detail?.missingIcons,
      iconsKB: Math.round([...document.querySelectorAll('#grid img')].reduce((n, i) => n + i.src.length, 0) / 1024) },
    longTasks: window.__long.map(([st, d]) => [Math.round(st - s), d]).filter(([st]) => st > -500),
  };
})()`);
console.log(JSON.stringify(r));
console.log('second  fps  gapMax(ms)  stalls  rate(Mbit/s)');
perSecond.forEach((p, i) => console.log(`${String(i + 1).padStart(6)}  ${String(p.fps ?? '-').padStart(3)}  ${String(p.gapMax ?? '-').padStart(10)}  ${String(p.stalls ?? '-').padStart(6)}  ${p.rate ?? '-'}`));
process.exit(0);

// Capture screenshots of the benchmark page while it runs, via the Chrome
// DevTools Protocol (no puppeteer). Frames go to <outDir>/frame-NNNN.png;
// stitch them with ffmpeg afterwards.
//
// Usage: node capture.mjs <url> <outDir> [seconds] [fps]

import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [url, outDir, seconds = '60', fps = '2'] = process.argv.slice(2);
if (!url || !outDir) {
  console.error('usage: node capture.mjs <url> <outDir> [seconds] [fps]');
  process.exit(1);
}
mkdirSync(outDir, { recursive: true });

const chrome = process.env.CHROME || 'chromium-browser';
const port = 9300 + Math.floor(Math.random() * 500);
const proc = spawn(chrome, [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'cap-'))}`,
  '--no-first-run', '--window-size=1920,1200', '--hide-scrollbars', 'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    target = list.find((t) => t.type === 'page');
  } catch { /* not up yet */ }
}
if (!target) { proc.kill(); throw new Error('chrome did not start'); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let id = 0;
const waiting = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const n = ++id;
  waiting.set(n, resolve);
  ws.send(JSON.stringify({ id: n, method, params }));
});

await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1200, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url });

const total = Number(seconds) * Number(fps);
for (let i = 0; i < total; i++) {
  const t0 = Date.now();
  const r = await send('Page.captureScreenshot', { format: 'png' });
  if (r.result?.data) writeFileSync(join(outDir, `frame-${String(i).padStart(4, '0')}.png`), Buffer.from(r.result.data, 'base64'));
  const title = (await send('Runtime.evaluate', { expression: 'document.title', returnByValue: true })).result?.result?.value;
  if (title === 'carlink:done') { console.log(`done after ${i + 1} frames`); break; }
  await sleep(Math.max(0, 1000 / Number(fps) - (Date.now() - t0)));
}
ws.close();
proc.kill();

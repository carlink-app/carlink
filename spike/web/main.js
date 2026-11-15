// Main thread: feature detection, settings, running tests in the worker,
// showing stats and results, sending reports back to the phone.
//
// URL parameters: ?host=<phone>  preset the phone host
//                 ?autorun=1     run the benchmark on load and send the report
//                 ?quick=1       shorter tests (for automated runs)

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const log = (msg) => {
  const line = `${new Date().toLocaleTimeString()} ${msg}\n`;
  $('log').textContent = line + $('log').textContent;
  console.log(msg);
};

const state = {
  features: {},
  config: { wtPort: 4433, certHashes: [] },
  servedByPhone: false,
  runs: [],
  running: false,
  stopSuite: false,
};

// ---------- feature detection ----------

const CODECS = [
  ['H.264 High L5.0', 'avc1.640032', 'h264'],
  ['H.264 Baseline L5.0', 'avc1.42E032'],
  ['HEVC Main L5.0', 'hvc1.1.6.L150.90', 'hevc'],
  ['VP9', 'vp09.00.40.08'],
  ['AV1', 'av01.0.08M.08'],
];

async function detectFeatures() {
  const f = {};
  f.userAgent = navigator.userAgent;
  const chrome = navigator.userAgent.match(/Chrom(?:e|ium)\/([\d.]+)/);
  f.chromiumVersion = chrome ? chrome[1] : 'unknown';
  f.isSecureContext = window.isSecureContext;
  f.crossOriginIsolated = window.crossOriginIsolated;
  f.screen = `${screen.width}x${screen.height} @${devicePixelRatio}x, window ${innerWidth}x${innerHeight}`;
  f.hardwareConcurrency = navigator.hardwareConcurrency;
  f.deviceMemory = navigator.deviceMemory ?? 'n/a';
  f.VideoDecoder = typeof VideoDecoder !== 'undefined';
  f.AudioDecoder = typeof AudioDecoder !== 'undefined';
  f.WebTransport = typeof WebTransport !== 'undefined';
  f.RTCPeerConnection = typeof RTCPeerConnection !== 'undefined';
  f.AudioContext = typeof AudioContext !== 'undefined';
  f.mediaSession = 'mediaSession' in navigator;
  f.geolocation = 'geolocation' in navigator;
  f.serviceWorker = 'serviceWorker' in navigator;
  f.OffscreenCanvas = typeof OffscreenCanvas !== 'undefined';
  f.transferControlToOffscreen = 'transferControlToOffscreen' in HTMLCanvasElement.prototype;
  f.pointerrawupdate = 'onpointerrawupdate' in window;
  f.getCoalescedEvents = typeof PointerEvent !== 'undefined' && 'getCoalescedEvents' in PointerEvent.prototype;
  f.SharedArrayBuffer = typeof SharedArrayBuffer !== 'undefined';
  try {
    // Tiny module using a SIMD instruction (from wasm-feature-detect).
    f.wasmSIMD = WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,10,1,8,0,65,0,253,15,253,98,11]));
  } catch { f.wasmSIMD = false; }
  f.WebGL2 = !!document.createElement('canvas').getContext('webgl2');
  f.webglRenderer = (() => {
    const gl = document.createElement('canvas').getContext('webgl');
    if (!gl) return 'n/a';
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  })();

  f.decode = {};
  if (f.VideoDecoder) {
    for (const [name, codec, key] of CODECS) {
      const res = {};
      for (const hw of ['prefer-hardware', 'no-preference']) {
        try {
          const r = await VideoDecoder.isConfigSupported({
            codec, codedWidth: 1920, codedHeight: 1200, hardwareAcceleration: hw, optimizeForLatency: true,
          });
          res[hw] = r.supported;
        } catch (e) {
          res[hw] = `error ${e.message}`;
        }
      }
      f[`decode ${name}`] = `hw:${res['prefer-hardware'] === true ? 'yes' : 'no'} any:${res['no-preference'] === true ? 'yes' : 'no'}`;
      if (key) f.decode[key] = res;
    }
  }
  return f;
}

function renderFeatures(f) {
  const good = (v) => v === true || (typeof v === 'string' && /yes/.test(v) && !/no/.test(v));
  const bad = (v) => v === false || (typeof v === 'string' && /no|error/.test(v) && !/yes/.test(v));
  $('features').innerHTML = Object.entries(f).filter(([k]) => k !== 'decode').map(([k, v]) => {
    const cls = good(v) ? 'ok' : bad(v) ? 'bad' : typeof v === 'string' && /yes/.test(v) ? 'warn' : '';
    return `<tr><td>${k}</td><td class="${cls}">${String(v).replace(/</g, '&lt;')}</td></tr>`;
  }).join('');
}

const canDecode = (codec) => {
  const d = state.features.decode?.[codec];
  return !!d && (d['no-preference'] === true || d['prefer-hardware'] === true);
};

// ---------- config ----------

async function loadConfig() {
  // Page served by the phone itself: /info has the current hash and ports.
  try {
    const r = await fetch('info', { cache: 'no-store' });
    if (r.ok) {
      const info = await r.json();
      state.servedByPhone = true;
      state.config.wtPort = Number(info.wtPort) || 4433;
      state.config.certHashes = info.certHashes;
      if (!$('host').value) $('host').value = location.hostname;
      log(`served by phone, phone IPs: ${(info.ips || []).join(', ') || 'unknown'}`);
      return;
    }
  } catch { /* not served by the phone */ }
  // Public copy of the page: config.json lists hashes for upcoming weeks.
  try {
    const r = await fetch('config.json', { cache: 'no-store' });
    if (r.ok) {
      const c = await r.json();
      state.config.wtPort = c.wtPort || 4433;
      state.config.certHashes = c.certHashes || [];
      log(`config.json: ${state.config.certHashes.length} cert hashes`);
    }
  } catch (e) {
    log(`no config.json: ${e.message}`);
  }
}

const wtURL = (host, priority) => `https://${host}:${state.config.wtPort + (priority ? 1 : 0)}/wt`;

function wsURL(host, mode, codec, priority) {
  const q = `mode=${mode}&codec=${codec}&dscp=${priority ? 1 : 0}`;
  if (state.servedByPhone) {
    return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws?${q}`;
  }
  // From a public HTTPS page only wss:// is allowed, which needs a real cert on the phone.
  return location.protocol === 'https:' ? `wss://${host}:8443/ws?${q}` : `ws://${host}:8080/ws?${q}`;
}

// ---------- running tests ----------

const worker = new Worker('worker.js');
let pendingSummary = null;

worker.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'log') log(m.text);
  if (m.type === 'stats') $('stats').textContent = formatStats(m.stats);
  if (m.type === 'summary') {
    state.runs.push(m.summary);
    renderResults();
    const resolve = pendingSummary;
    pendingSummary = null;
    resolve?.(m.summary);
  }
};

function fmt(p) {
  if (!p || !p.n) return '-';
  return `p50 ${p.p50.toFixed(1)}  p95 ${p.p95.toFixed(1)}  p99 ${p.p99.toFixed(1)}  max ${p.max.toFixed(1)} ms`;
}

function formatStats(s) {
  return [
    `${s.name}  ${s.codec || ''}  ${s.width ? s.width + 'x' + s.height : ''}`,
    `fps         ${s.fps}`,
    `decode      ${fmt(s.decode)}`,
    `draw        ${fmt(s.draw)}`,
    `net->pixel  ${fmt(s.netToPixel)}`,
    `jitter      ${fmt(s.jitter)}`,
    `rtt         ${fmt(s.rtt)}`,
    `queue max ${s.maxQueue}  reorder ${s.reorderMax}  errors ${s.errors}`,
  ].join('\n');
}

// Each run gets a fresh canvas: an OffscreenCanvas context type (2d, webgl,
// bitmaprenderer) can't be changed once chosen.
function freshCanvas() {
  const old = $('canvas');
  const c = document.createElement('canvas');
  c.id = 'canvas';
  c.width = 1920;
  c.height = 1200;
  old.replaceWith(c);
  return c;
}

function runTest(t) {
  const host = $('host').value.trim();
  const msg = {
    type: 'start',
    name: t.name,
    mode: t.mode,
    codec: t.codec || 'h264',
    renderer: t.renderer || '2d',
    hw: t.hw || 'no-preference',
    priority: !!t.priority,
    pingInterval: t.pingInterval || 20,
    duration: t.duration || 0,
    clipURL: new URL(`clip.${t.codec || 'h264'}`, location.href).href,
    wtURL: wtURL(host, t.priority),
    wsURL: wsURL(host, t.mode === 'ws-video' ? 'video' : 'echo', t.codec || 'h264', t.priority),
    certHashes: state.config.certHashes,
  };
  const transfer = [];
  if (t.mode !== 'wt-ping' && t.mode !== 'ws-ping') {
    msg.canvas = freshCanvas().transferControlToOffscreen();
    transfer.push(msg.canvas);
  }
  log(`start: ${t.name}`);
  return new Promise((resolve) => {
    pendingSummary = resolve;
    worker.postMessage(msg, transfer);
  });
}

function buildSuite(quick) {
  const d = (s) => (quick ? Math.ceil(s / 2) : s);
  const host = $('host').value.trim();
  const tests = [];
  const add = (cond, why, t) => tests.push(cond ? t : { ...t, skip: why });
  const hasDecoder = state.features.VideoDecoder && state.features.transferControlToOffscreen;
  const hasWT = state.features.WebTransport && !!host;
  const hasWS = state.servedByPhone || location.protocol === 'http:';

  for (const renderer of ['2d', 'webgl', 'bitmap']) {
    add(hasDecoder, 'no VideoDecoder', { name: `local h264 ${renderer}`, mode: 'local', codec: 'h264', renderer, duration: d(8) });
  }
  add(hasDecoder && canDecode('hevc'), 'no HEVC decode', { name: 'local hevc 2d', mode: 'local', codec: 'hevc', duration: d(8) });
  add(hasDecoder, 'no VideoDecoder', { name: 'local h264 2d prefer-hardware', mode: 'local', codec: 'h264', hw: 'prefer-hardware', duration: d(6) });

  add(hasDecoder && hasWT, 'no WebTransport/decoder', { name: 'wt video h264', mode: 'wt-video', codec: 'h264', duration: d(12) });
  add(hasDecoder && hasWT, 'no WebTransport/decoder', { name: 'wt video h264 priority', mode: 'wt-video', codec: 'h264', priority: true, duration: d(12) });
  add(hasDecoder && hasWT && canDecode('hevc'), 'no HEVC/WebTransport', { name: 'wt video hevc', mode: 'wt-video', codec: 'hevc', duration: d(12) });
  add(hasDecoder && hasWS, 'no WebSocket path', { name: 'ws video h264', mode: 'ws-video', codec: 'h264', duration: d(12) });
  add(hasDecoder && hasWS, 'no WebSocket path', { name: 'ws video h264 priority', mode: 'ws-video', codec: 'h264', priority: true, duration: d(12) });

  // Idle link: sparse pings show Wi-Fi power-save wake-up delay.
  for (const [iv, dur] of [[20, 8], [200, 12], [1000, 25]]) {
    add(hasWT, 'no WebTransport', { name: `wt ping every ${iv} ms`, mode: 'wt-ping', pingInterval: iv, duration: d(dur) });
  }
  add(hasWS, 'no WebSocket path', { name: 'ws ping every 1000 ms', mode: 'ws-ping', pingInterval: 1000, duration: d(25) });
  return tests;
}

async function runSuite({ quick = false } = {}) {
  if (state.running) return;
  state.running = true;
  state.stopSuite = false;
  setRunning(true);
  const tests = buildSuite(quick);
  const total = tests.length;
  for (const [i, t] of tests.entries()) {
    if (state.stopSuite) break;
    $('suiteStatus').textContent = `${i + 1}/${total}: ${t.name}`;
    if (t.skip) {
      state.runs.push({ name: t.name, skipped: t.skip });
      renderResults();
      continue;
    }
    // Safety net: a run that never connects or loads is stopped after duration + 30 s.
    const guard = setTimeout(() => worker.postMessage({ type: 'stop' }), (t.duration + 30) * 1000);
    await runTest(t);
    clearTimeout(guard);
  }
  $('suiteStatus').textContent = state.stopSuite ? 'stopped' : 'done';
  state.running = false;
  setRunning(false);
  await sendReport();
}

function setRunning(on) {
  $('suite').disabled = on;
  $('start').disabled = on;
  $('stop').disabled = !on;
}

// ---------- results table ----------

const cell = (p, k) => (p && p.n ? p[k].toFixed(1) : '');

function renderResults() {
  const rows = state.runs.map((r) => {
    if (r.skipped) return `<tr><td>${r.name}</td><td colspan="11" class="warn">skipped: ${r.skipped}</td></tr>`;
    const cls = r.failed || r.errors ? 'bad' : '';
    return `<tr class="${cls}"><td>${r.name}${r.failed ? ' (' + r.failed + ')' : ''}</td>
      <td>${r.fps ? r.fps.toFixed(1) : ''}</td>
      <td>${cell(r.decode, 'p50')}</td><td>${cell(r.decode, 'p99')}</td>
      <td>${cell(r.draw, 'p99')}</td>
      <td>${cell(r.netToPixel, 'p50')}</td><td>${cell(r.netToPixel, 'p99')}</td>
      <td>${cell(r.jitter, 'p99')}</td><td>${cell(r.jitter, 'max')}</td>
      <td>${cell(r.rtt, 'p50')}</td><td>${cell(r.rtt, 'p99')}</td>
      <td>${r.errors || ''}${r.droppedFrames ? ' drop ' + r.droppedFrames : ''}${r.pingLoss ? ' loss ' + (r.pingLoss * 100).toFixed(1) + '%' : ''}</td></tr>`;
  }).join('');
  $('results').innerHTML = `<table><tr><th>test</th><th>fps</th><th>dec p50</th><th>dec p99</th><th>draw p99</th>
    <th>net→px p50</th><th>net→px p99</th><th>jitter p99</th><th>jitter max</th><th>rtt p50</th><th>rtt p99</th><th>errors</th></tr>${rows}</table>`;
}

// ---------- report ----------

function hashOptions(hashes) {
  return (hashes || []).map((h) => ({
    algorithm: 'sha-256',
    value: new Uint8Array(h.match(/../g).map((b) => parseInt(b, 16))),
  }));
}

async function sendReport() {
  const report = JSON.stringify({
    time: new Date().toISOString(),
    page: location.href,
    features: state.features,
    runs: state.runs,
  });
  if (state.servedByPhone) {
    try {
      const r = await fetch('report', { method: 'POST', body: report });
      log(r.ok ? 'report sent over HTTP' : `report failed: ${r.status}`);
      if (r.ok) return true;
    } catch (e) {
      log(`report over HTTP failed: ${e.message}`);
    }
  }
  const host = $('host').value.trim();
  if (!host || !state.features.WebTransport) { log('cannot send report: no host or no WebTransport'); return false; }
  try {
    const wt = new WebTransport(wtURL(host, false), { serverCertificateHashes: hashOptions(state.config.certHashes) });
    await wt.ready;
    const s = await wt.createBidirectionalStream();
    const w = s.writable.getWriter();
    await w.write(new TextEncoder().encode('report ' + report));
    await w.close();
    const { value } = await s.readable.getReader().read();
    log(`report sent over WebTransport: ${new TextDecoder().decode(value)}`);
    wt.close();
    return true;
  } catch (e) {
    log(`report over WebTransport failed: ${e.message}`);
    return false;
  }
}

// ---------- UI ----------

$('suite').onclick = () => {
  localStorage.setItem('host', $('host').value.trim());
  runSuite();
};

$('start').onclick = async () => {
  localStorage.setItem('host', $('host').value.trim());
  const mode = $('mode').value;
  if (mode !== 'local' && !$('host').value.trim()) return log('enter the phone host first');
  state.running = true;
  setRunning(true);
  await runTest({
    name: `${mode} ${$('codec').value} ${$('renderer').value}`,
    mode, codec: $('codec').value, renderer: $('renderer').value, hw: $('hw').value,
    priority: $('priority').value === '1', pingInterval: Number($('pingInterval').value),
  });
  state.running = false;
  setRunning(false);
};

$('stop').onclick = () => {
  state.stopSuite = true;
  worker.postMessage({ type: 'stop' });
};

$('report').onclick = () => sendReport();

$('fs').onclick = () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else $('stage').requestFullscreen?.().catch((e) => log(`fullscreen failed: ${e.message}`));
};

// ---------- init ----------

$('host').value = params.get('host') || localStorage.getItem('host') || '';
$('origin').innerHTML = `${location.origin} - secure context: <b class="${isSecureContext ? 'ok' : 'bad'}">${isSecureContext}</b>`;
window.addEventListener('error', (e) => log(`error: ${e.message}`));

(async () => {
  await loadConfig();
  state.features = await detectFeatures();
  renderFeatures(state.features);
  log('feature detection done');
  if (params.get('autorun') === '1') {
    await runSuite({ quick: params.get('quick') === '1' });
    document.title = 'carlink:done';
  }
})();

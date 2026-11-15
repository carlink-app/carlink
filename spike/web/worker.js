// Worker: network, WebCodecs decode, drawing to an OffscreenCanvas.
// Everything on the hot path runs here so the main thread can't stall it.

const FRAME_US = 16667;
let run = null; // current test run

const post = (m) => self.postMessage(m);
const log = (text) => post({ type: 'log', text });

self.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'start') {
    if (run) stop('replaced');
    start(m).catch((err) => { log(`run failed: ${err.message || err}`); stop(`failed: ${err.message || err}`); });
  }
  if (m.type === 'stop') stop();
};

// ---------- stats ----------

function pct(arr) {
  if (!arr.length) return { n: 0 };
  const s = Float64Array.from(arr).sort();
  const at = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
  return { n: s.length, p50: at(0.5), p95: at(0.95), p99: at(0.99), max: s[s.length - 1] };
}

const SERIES = ['decode', 'draw', 'netToPixel', 'jitter', 'rtt'];

function newWindow() {
  const w = { drawn: 0, maxQueue: 0, reorderMax: 0 };
  for (const k of SERIES) w[k] = [];
  return w;
}

function newRun(m) {
  return {
    m, startedAt: performance.now(),
    closers: [], timers: [],
    renderer: null, decoder: null, codecString: null, width: 0, height: 0,
    pending: new Map(), // timestamp -> { arrive, decodeCall }
    reorder: new Map(), nextSeq: 0,
    minDelay: Infinity, errors: 0, pingsSent: 0, pongs: 0,
    latest: null, drawing: false, droppedFrames: 0,
    win: newWindow(), all: newWindow(),
  };
}

function record(key, v) {
  run.win[key].push(v);
  run.all[key].push(v);
}

function tick() {
  const w = run.win;
  run.all.drawn += w.drawn;
  run.all.maxQueue = Math.max(run.all.maxQueue, w.maxQueue);
  run.all.reorderMax = Math.max(run.all.reorderMax, w.reorderMax);
  const stats = { name: run.m.name, codec: run.codecString, width: run.width, height: run.height,
    fps: w.drawn, maxQueue: w.maxQueue, reorderMax: w.reorderMax, errors: run.errors };
  for (const k of SERIES) stats[k] = pct(w[k]);
  post({ type: 'stats', stats });
  run.win = newWindow();
}

function stop(reason) {
  if (!run) return;
  const r = run;
  run = null;
  r.timers.forEach((t) => { clearInterval(t); clearTimeout(t); });
  r.closers.forEach((c) => { try { c(); } catch { /* already closed */ } });
  try { r.decoder?.close(); } catch { /* already closed */ }
  r.latest?.frame.close();
  const a = r.all;
  a.drawn += r.win.drawn;
  const durationS = (performance.now() - r.startedAt) / 1000;
  const summary = {
    name: r.m.name, mode: r.m.mode, codec: r.m.codec, renderer: r.m.renderer, hw: r.m.hw,
    priority: !!r.m.priority, pingIntervalMs: r.m.pingInterval,
    codecString: r.codecString, width: r.width, height: r.height,
    durationS, fps: a.drawn / durationS, framesDrawn: a.drawn,
    errors: r.errors, maxQueue: a.maxQueue, reorderMax: a.reorderMax, droppedFrames: r.droppedFrames,
    // The last ping may still be in flight when the run stops.
    pingLoss: r.pingsSent > 1 ? Math.max(0, r.pingsSent - 1 - r.pongs) / (r.pingsSent - 1) : null,
    failed: reason && reason !== 'replaced' ? reason : null,
  };
  for (const k of SERIES) summary[k] = pct(a[k]);
  post({ type: 'summary', summary });
}

// ---------- H.264 / HEVC parsing ----------

const nalType = (b, codec) => (codec === 'hevc' ? (b >> 1) & 0x3f : b & 0x1f);

function nals(au, codec) {
  const out = [];
  for (let i = 0; i + 3 < au.length; i++) {
    if (au[i] === 0 && au[i + 1] === 0 && au[i + 2] === 1) {
      out.push({ type: nalType(au[i + 3], codec), at: i + 3 });
      i += 2;
    }
  }
  return out;
}

// Remove emulation prevention bytes (00 00 03 -> 00 00).
function unescape(b, from, len) {
  const out = [];
  let zeros = 0;
  for (let i = from; i < b.length && out.length < len; i++) {
    if (zeros >= 2 && b[i] === 3) { zeros = 0; continue; }
    zeros = b[i] === 0 ? zeros + 1 : 0;
    out.push(b[i]);
  }
  return out;
}

const hex2 = (b) => b.toString(16).padStart(2, '0').toUpperCase();

function codecString(au, codec) {
  if (codec === 'h264') {
    const sps = nals(au, codec).find((n) => n.type === 7);
    if (!sps) return null;
    return `avc1.${hex2(au[sps.at + 1])}${hex2(au[sps.at + 2])}${hex2(au[sps.at + 3])}`;
  }
  // HEVC: profile_tier_level starts 3 bytes into the SPS NAL unit.
  const sps = nals(au, codec).find((n) => n.type === 33);
  if (!sps) return null;
  const b = unescape(au, sps.at, 16);
  const ptl = b.slice(3);
  const space = ['', 'A', 'B', 'C'][ptl[0] >> 6];
  const tier = (ptl[0] >> 5) & 1 ? 'H' : 'L';
  const profile = ptl[0] & 0x1f;
  let compat = ((ptl[1] << 24) | (ptl[2] << 16) | (ptl[3] << 8) | ptl[4]) >>> 0;
  let rev = 0;
  for (let i = 0; i < 32; i++) { rev = ((rev << 1) | (compat & 1)) >>> 0; compat >>>= 1; }
  const constraints = ptl.slice(5, 11);
  while (constraints.length && constraints[constraints.length - 1] === 0) constraints.pop();
  const level = ptl[11];
  return [`hvc1`, `${space}${profile}`, rev.toString(16).toUpperCase(), `${tier}${level}`,
    ...constraints.map((c) => c.toString(16).toUpperCase())].join('.');
}

function splitAccessUnits(b, codec) {
  const aud = codec === 'hevc' ? 35 : 9;
  const aus = [];
  let start = -1;
  for (let i = 0; i + 3 < b.length; i++) {
    if (b[i] !== 0 || b[i + 1] !== 0 || b[i + 2] !== 1) continue;
    if (nalType(b[i + 3], codec) === aud) {
      let pos = i;
      if (pos > 0 && b[pos - 1] === 0) pos--;
      if (start >= 0) aus.push(b.subarray(start, pos));
      start = pos;
    }
    i += 2;
  }
  if (start >= 0) aus.push(b.subarray(start));
  return aus;
}

// ---------- renderers ----------

function makeRenderer(canvas, kind) {
  const resize = (f) => {
    if (canvas.width !== f.displayWidth || canvas.height !== f.displayHeight) {
      canvas.width = f.displayWidth;
      canvas.height = f.displayHeight;
      return true;
    }
    return false;
  };

  if (kind === 'webgl') {
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, desynchronized: true, preserveDrawingBuffer: false });
    if (!gl) throw new Error('no webgl2');
    const sh = (type, src) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      return s;
    };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, `#version 300 es
      out vec2 uv;
      void main() {
        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
        uv = vec2(p.x * 0.5, 1.0 - p.y * 0.5);
        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
      }`));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, `#version 300 es
      precision mediump float;
      uniform sampler2D tex;
      in vec2 uv;
      out vec4 color;
      void main() { color = texture(tex, uv); }`));
    gl.linkProgram(prog);
    gl.useProgram(prog);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return {
      draw(f, done) {
        if (resize(f)) gl.viewport(0, 0, canvas.width, canvas.height);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, f);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        f.close();
        done();
      },
    };
  }

  if (kind === 'bitmap') {
    const ctx = canvas.getContext('bitmaprenderer');
    return {
      draw(f, done) {
        resize(f);
        createImageBitmap(f).then((bmp) => {
          f.close();
          ctx.transferFromImageBitmap(bmp);
          done();
        }, () => { f.close(); done(); });
      },
    };
  }

  const ctx = canvas.getContext('2d', { desynchronized: true, alpha: false });
  return {
    draw(f, done) {
      resize(f);
      ctx.drawImage(f, 0, 0);
      f.close();
      done();
    },
  };
}

// ---------- decode ----------

function ensureDecoder(au) {
  if (run.decoder) return true;
  const cs = codecString(au, run.m.codec);
  if (!cs) return false; // wait for a keyframe with parameter sets
  run.codecString = cs;
  const r = run;
  r.decoder = new VideoDecoder({
    output: (frame) => onFrame(r, frame),
    error: (e) => { r.errors++; log(`decoder error: ${e.message}`); },
  });
  // No `description`: the stream is Annex B with parameter sets in-band.
  r.decoder.configure({ codec: cs, optimizeForLatency: true, hardwareAcceleration: r.m.hw });
  log(`decoder: ${cs}, ${r.m.hw}, renderer ${r.m.renderer}`);
  return true;
}

// Decoded frames are drawn newest-first: if several come out while a draw
// is still running (slow GPU, busy worker), only the latest is drawn and the
// rest are dropped. Dropping decoded frames is safe; dropping encoded ones isn't.
const drawChannel = new MessageChannel();
drawChannel.port1.onmessage = () => drawLatest();

function onFrame(r, frame) {
  if (r !== run) { frame.close(); return; }
  const meta = r.pending.get(frame.timestamp);
  r.pending.delete(frame.timestamp);
  if (r.latest) {
    r.latest.frame.close();
    r.droppedFrames++;
  }
  r.latest = { frame, meta, out: performance.now() };
  r.width = frame.displayWidth;
  r.height = frame.displayHeight;
  if (!r.drawing) {
    r.drawing = true;
    drawChannel.port2.postMessage(0);
  }
}

function drawLatest() {
  const r = run;
  if (!r || !r.latest) { if (r) r.drawing = false; return; }
  const { frame, meta, out } = r.latest;
  r.latest = null;
  r.renderer.draw(frame, () => {
    if (r !== run) return;
    const drawn = performance.now();
    r.win.drawn++;
    record('draw', drawn - out);
    if (meta) {
      record('decode', out - meta.decodeCall);
      if (meta.arrive != null) record('netToPixel', drawn - meta.arrive);
    }
    if (r.latest) drawChannel.port2.postMessage(0);
    else r.drawing = false;
  });
}

// Feed one access unit. Encoded frames are never dropped: dropping a P-frame
// corrupts every frame after it until the next keyframe.
function decode(seq, au, key, arrive) {
  if (!ensureDecoder(au)) return;
  if (run.decoder.state !== 'configured') return;
  const ts = seq * FRAME_US;
  run.pending.set(ts, { arrive, decodeCall: performance.now() });
  run.win.maxQueue = Math.max(run.win.maxQueue, run.decoder.decodeQueueSize);
  try {
    run.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data: au }));
  } catch (e) {
    run.errors++;
    log(`decode() threw: ${e.message}`);
  }
}

// Network frames: header u32 seq | f64 send ms | u8 flags, then the access unit.
// WebTransport streams can finish out of order, so put frames back in order.
function onNetworkFrame(buf, arrive) {
  if (!run) return;
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const seq = dv.getUint32(0);
  const sent = dv.getFloat64(4);
  const key = (dv.getUint8(12) & 1) === 1;
  const au = buf.subarray(13);

  // One-way delay relative to the best seen so far. Clock offset cancels out.
  const delay = arrive - sent;
  run.minDelay = Math.min(run.minDelay, delay);
  record('jitter', delay - run.minDelay);

  run.reorder.set(seq, { au, key, arrive });
  run.win.reorderMax = Math.max(run.win.reorderMax, run.reorder.size - 1);
  while (run.reorder.has(run.nextSeq)) {
    const f = run.reorder.get(run.nextSeq);
    run.reorder.delete(run.nextSeq);
    decode(run.nextSeq, f.au, f.key, f.arrive);
    run.nextSeq++;
  }
  if (run.reorder.size > 120) {
    log(`gave up waiting for frame ${run.nextSeq}`);
    run.errors++;
    run.nextSeq = Math.min(...run.reorder.keys());
  }
}

// ---------- modes ----------

async function start(m) {
  run = newRun(m);
  const r = run;
  r.timers.push(setInterval(() => run === r && tick(), 1000));
  r.armDuration = () => {
    if (m.duration) r.timers.push(setTimeout(() => run === r && stop(), m.duration * 1000));
  };
  if (m.canvas) r.renderer = makeRenderer(m.canvas, m.renderer);
  if (m.mode === 'local') return startLocal(m);
  if (m.mode === 'wt-video' || m.mode === 'wt-ping') return startWT(m, m.mode === 'wt-video');
  if (m.mode === 'ws-video' || m.mode === 'ws-ping') return startWS(m);
  throw new Error(`unknown mode ${m.mode}`);
}

async function startLocal(m) {
  const r = run;
  const abort = new AbortController();
  r.closers.push(() => abort.abort());
  const res = await fetch(m.clipURL, { signal: abort.signal });
  if (!res.ok) throw new Error(`clip fetch: ${res.status}`);
  const aus = splitAccessUnits(new Uint8Array(await res.arrayBuffer()), m.codec);
  if (r !== run) return;
  // Feed at 60 fps like a live stream, looping. Frame 0 is the only keyframe.
  r.startedAt = performance.now();
  r.armDuration();
  const t0 = r.startedAt;
  let seq = 0;
  const feed = () => {
    if (r !== run) return;
    const now = performance.now();
    while (t0 + seq * (FRAME_US / 1000) <= now) {
      decode(seq, aus[seq % aus.length], seq % aus.length === 0, null);
      seq++;
    }
    setTimeout(feed, Math.max(0, t0 + seq * (FRAME_US / 1000) - performance.now()));
  };
  feed();
}

function startPings(send, interval) {
  const r = run;
  r.timers.push(setInterval(() => {
    if (r !== run) return;
    r.pingsSent++;
    send(new Float64Array([performance.now()]).buffer);
  }, interval));
}

function onPong(buf) {
  if (!run) return;
  run.pongs++;
  const t = new DataView(buf instanceof ArrayBuffer ? buf : buf.buffer, buf.byteOffset || 0, 8).getFloat64(0, true);
  record('rtt', performance.now() - t);
}

const hashOptions = (hashes) => (hashes || []).map((h) => ({
  algorithm: 'sha-256',
  value: new Uint8Array(h.match(/../g).map((b) => parseInt(b, 16))),
}));

async function startWT(m, video) {
  const r = run;
  const wt = new WebTransport(m.wtURL, { serverCertificateHashes: hashOptions(m.certHashes) });
  r.closers.push(() => wt.close());
  wt.closed.catch(() => {});
  await wt.ready;
  if (r !== run) return;
  r.startedAt = performance.now();
  r.armDuration();

  const dw = wt.datagrams.writable.getWriter();
  startPings((b) => dw.write(new Uint8Array(b)).catch(() => {}), m.pingInterval || 20);
  (async () => {
    const reader = wt.datagrams.readable.getReader();
    for (;;) {
      const { value, done } = await reader.read();
      if (done || r !== run) return;
      onPong(value);
    }
  })().catch(() => {});

  if (!video) return;
  const cmd = await wt.createBidirectionalStream();
  const w = cmd.writable.getWriter();
  await w.write(new TextEncoder().encode(`video ${m.codec}`));
  await w.close();

  const streams = wt.incomingUnidirectionalStreams.getReader();
  for (;;) {
    const { value, done } = await streams.read();
    if (done || r !== run) return;
    readFrameStream(value).catch((e) => { if (r === run) { r.errors++; log(`stream: ${e.message}`); } });
  }
}

async function readFrameStream(stream) {
  const reader = stream.getReader();
  const parts = [];
  let len = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    parts.push(value);
    len += value.length;
  }
  const arrive = performance.now();
  let buf = parts[0];
  if (parts.length > 1) {
    buf = new Uint8Array(len);
    let off = 0;
    for (const p of parts) { buf.set(p, off); off += p.length; }
  }
  onNetworkFrame(buf, arrive);
}

function startWS(m) {
  const r = run;
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(m.wsURL);
    ws.binaryType = 'arraybuffer';
    r.closers.push(() => ws.close());
    ws.onopen = () => {
      r.startedAt = performance.now();
      r.armDuration();
      startPings((b) => ws.readyState === 1 && ws.send(b), m.pingInterval || 20);
      resolve();
    };
    ws.onerror = () => reject(new Error('WebSocket error (mixed content or unreachable?)'));
    ws.onmessage = (e) => {
      if (r !== run) return;
      const arrive = performance.now();
      if (e.data.byteLength === 8) onPong(e.data);
      else onNetworkFrame(new Uint8Array(e.data), arrive);
    };
  });
}

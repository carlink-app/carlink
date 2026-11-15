// Decode worker: per-pane sequencing (Reorderer), WebCodecs decode and
// drawing to an OffscreenCanvas. Everything on the hot path runs here so the
// main thread (UI, touch) can't stall it and vice versa.

import { codecString, spsBytes, type Codec } from './h26x';
import {
  FLAG_KEY, HEADER_LEN, KIND_VIDEO, parseHeader, parseHeaderPrefix,
  type FromWorker, type PaneStats, type ToWorker,
} from './protocol';
import { Reorderer } from './reorder';
import { Samples } from './stats';

const scope = self as unknown as {
  postMessage(m: FromWorker): void;
  onmessage: ((e: MessageEvent<ToWorker>) => void) | null;
};
const post = (m: FromWorker) => scope.postMessage(m);
const log = (text: string) => post({ type: 'log', text });

interface Frame { au: Uint8Array; key: boolean; ptsUs: number }

class PaneDecoder {
  private ctx: OffscreenCanvasRenderingContext2D | null = null;
  private canvas: OffscreenCanvas | null = null;
  private codec: Codec = 'h264';
  private hintW = 0;
  private hintH = 0;

  private decoder: VideoDecoder | null = null;
  private configKey = '';
  private codecStr: string | null = null;

  readonly seq: Reorderer<Frame>;

  // Drawing: only the newest decoded frame is drawn.
  private latest: VideoFrame | null = null;
  private drawScheduled = false;
  private decodeCalls = new Map<number, number>(); // timestamp -> performance.now() at decode()

  // Stats.
  private drawn = 0;
  private lastDraw = 0;
  /** Phone timestamp (µs) of the last drawn frame. */
  private lastPts = 0;
  private maxGap: number | null = null;
  private stalls = 0;
  private decodeMs = new Samples(600);
  private droppedDecoded = 0;
  private errors = 0;
  private width = 0;
  private height = 0;

  constructor(readonly id: number) {
    this.seq = new Reorderer<Frame>({
      now: () => performance.now(),
      emit: (_seq, f) => this.decode(f),
      requestKeyframe: () => post({ type: 'keyframe', pane: id }),
      log: (t) => log(`pane ${id}: ${t}`),
    });
  }

  setCanvas(c: OffscreenCanvas) {
    this.canvas = c;
    this.ctx = c.getContext('2d', { alpha: false, desynchronized: true });
  }

  setFormat(w: number, h: number, codec: Codec) {
    if (codec === this.codec && w === this.hintW && h === this.hintH) return;
    this.hintW = w;
    this.hintH = h;
    this.codec = codec;
    this.configKey = '';
    // The phone recreates the encoder (its seq may restart): start over at its next keyframe.
    this.seq.resync(true);
  }

  private decode(f: Frame) {
    if (f.key && !this.ensureDecoder(f.au)) { this.seq.resync(); return; }
    const d = this.decoder;
    if (!d || d.state !== 'configured') { this.seq.resync(); return; }
    this.decodeCalls.set(f.ptsUs, performance.now());
    try {
      d.decode(new EncodedVideoChunk({ type: f.key ? 'key' : 'delta', timestamp: f.ptsUs, data: f.au }));
    } catch (e) {
      this.decodeCalls.delete(f.ptsUs);
      this.fail(`decode() threw: ${(e as Error).message}`);
      this.seq.resync();
    }
  }

  private fail(text: string) {
    this.errors++;
    // Main logs it (console + debug overlay).
    post({ type: 'decodeError', pane: this.id, codec: this.codec, text });
  }

  /** (Re)configure from the keyframe's SPS when the codec string or SPS (size) changes. */
  private ensureDecoder(au: Uint8Array): boolean {
    const cs = codecString(au, this.codec);
    const sps = spsBytes(au, this.codec);
    if (!cs || !sps) {
      if (this.decoder?.state === 'configured') return true; // keyframe without SPS: keep config
      log(`pane ${this.id}: keyframe without SPS`);
      return false;
    }
    const key = `${cs}|${sps.join(',')}`;
    if (this.decoder?.state === 'configured' && key === this.configKey) return true;
    if (typeof VideoDecoder === 'undefined') {
      if (!this.errors) this.fail('this browser has no VideoDecoder (WebCodecs)');
      return false;
    }
    if (!this.decoder || this.decoder.state === 'closed') {
      this.decoder = new VideoDecoder({
        output: (frame) => this.onDecoded(frame),
        error: (e) => {
          this.configKey = '';
          this.fail(`decoder error: ${e.message}`);
          this.seq.resync();
        },
      });
    }
    const config: VideoDecoderConfig = { codec: cs, optimizeForLatency: true };
    if (this.hintW && this.hintH) { config.codedWidth = this.hintW; config.codedHeight = this.hintH; }
    // No `description`: the stream is Annex B with parameter sets in-band.
    try {
      this.decoder.configure(config);
    } catch (e) {
      this.fail(`configure(${cs}) failed: ${(e as Error).message}`);
      return false;
    }
    this.configKey = key;
    this.codecStr = cs;
    log(`pane ${this.id}: decoder ${cs}`);
    return true;
  }

  private onDecoded(frame: VideoFrame) {
    const t0 = this.decodeCalls.get(frame.timestamp);
    if (t0 !== undefined) {
      this.decodeCalls.delete(frame.timestamp);
      this.decodeMs.push(performance.now() - t0);
    }
    if (this.decodeCalls.size > 300) this.decodeCalls.clear(); // never leak on errors
    if (this.latest) { this.latest.close(); this.droppedDecoded++; }
    this.latest = frame;
    if (!this.drawScheduled) {
      this.drawScheduled = true;
      drawQueue.add(this);
      drawPort.postMessage(0);
    }
  }

  draw() {
    this.drawScheduled = false;
    const f = this.latest;
    this.latest = null;
    if (!f) return;
    const c = this.canvas;
    if (c && this.ctx) {
      if (c.width !== f.displayWidth || c.height !== f.displayHeight) {
        c.width = f.displayWidth;
        c.height = f.displayHeight;
      }
      this.ctx.drawImage(f, 0, 0);
      this.drawn++;
      const now = performance.now();
      if (this.lastDraw) {
        const gap = now - this.lastDraw;
        this.maxGap = Math.max(this.maxGap ?? 0, gap);
        // A freeze: the frame came much later than the phone made it (a still
        // screen has a big gap in both and doesn't count).
        const ptsGapMs = (f.timestamp - this.lastPts) / 1000;
        if (gap - ptsGapMs > STALL_MS) this.stalls++;
      } else {
        post({ type: 'firstDraw', pane: this.id });
      }
      this.lastDraw = now;
      this.lastPts = f.timestamp;
    }
    this.width = f.displayWidth;
    this.height = f.displayHeight;
    f.close();
  }

  /** New session: sequence numbers restart; freeze counting starts over. */
  reset() {
    this.seq.reset();
    this.lastDraw = 0;
    this.stalls = 0;
    this.maxGap = null;
  }

  stats(): PaneStats {
    const s: PaneStats = {
      pane: this.id,
      fps: this.drawn,
      decodeP50: this.decodeMs.pct(0.5),
      decodeP95: this.decodeMs.pct(0.95),
      droppedDecoded: this.droppedDecoded,
      droppedEncoded: this.seq.dropped,
      codec: this.codecStr,
      width: this.width,
      height: this.height,
      queue: this.decoder?.decodeQueueSize ?? 0,
      errors: this.errors,
      waitingKey: this.seq.syncing,
      maxGapMs: this.maxGap === null ? null : Math.round(this.maxGap),
      stalls: this.stalls,
    };
    this.drawn = 0;
    this.maxGap = null;
    return s;
  }
}

// Draws run as a separate task (MessageChannel) after decoder outputs, so a
// burst of outputs collapses into one draw of the newest frame.
const drawQueue = new Set<PaneDecoder>();
const drawChannel = new MessageChannel();
const drawPort = drawChannel.port2;
drawChannel.port1.onmessage = () => {
  const list = [...drawQueue];
  drawQueue.clear();
  for (const p of list) p.draw();
};

const panes = new Map<number, PaneDecoder>();
const pane = (id: number) => {
  let p = panes.get(id);
  if (!p) { p = new PaneDecoder(id); panes.set(id, p); }
  return p;
};

scope.onmessage = (e) => {
  const m = e.data;
  switch (m.type) {
    case 'canvas': pane(m.pane).setCanvas(m.canvas); break;
    case 'reset': panes.forEach((p) => p.reset()); break;
    case 'pane': pane(m.pane).setFormat(m.w, m.h, m.codec); break;
    case 'frame': {
      const b = new Uint8Array(m.buf);
      const h = parseHeader(b);
      if (!h || h.kind !== KIND_VIDEO) return;
      pane(h.pane).seq.push(h.seq, { au: b.subarray(HEADER_LEN), key: (h.flags & FLAG_KEY) !== 0, ptsUs: h.ptsUs });
      break;
    }
    case 'lost': {
      const h = parseHeaderPrefix(new Uint8Array(m.buf));
      if (h && h.kind === KIND_VIDEO) pane(h.pane).seq.lost(h.seq);
      break;
    }
  }
};

/** A frame this much later than its phone timestamp says is a visible freeze. */
const STALL_MS = 250;

setInterval(() => panes.forEach((p) => p.seq.tick()), 50);
setInterval(() => panes.forEach((p) => post({ type: 'stats', stats: p.stats() })), 1000);

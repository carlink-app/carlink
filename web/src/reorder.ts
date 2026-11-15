// Per-pane frame sequencing: puts frames back in seq order, starts at
// keyframes, detects gaps. Pure logic (time and outputs are injected) so it
// can be unit tested; the worker wires it to the decoder.
//
// Streams complete out of order: a keyframe is big and usually finishes
// after the small deltas that follow it. So frames are buffered also while
// waiting for a keyframe ("syncing"), and decoding starts at the keyframe,
// discarding anything older.

/** A missing frame is declared lost after this long... */
export const GAP_TIMEOUT_MS = 150;
/** ...or once more than this many later frames are waiting for it. */
export const GAP_MAX_BUFFERED = 30;
/** While syncing, keep at most this many frames (oldest dropped first). */
export const SYNC_MAX_BUFFERED = 240;
/** Keyframe requests are rate-limited to one per this interval (unless forced). */
export const KEYFRAME_RETRY_MS = 1000;

/** Serial-number arithmetic for u32 seq (wraps at 2^32): a - b as int32. */
export const seqDiff = (a: number, b: number): number => (a - b) | 0;
export const seqLt = (a: number, b: number): boolean => seqDiff(a, b) < 0;
const seqNext = (a: number): number => (a + 1) >>> 0;

export interface Sequenced { key: boolean }

export interface ReorderHooks<T> {
  now(): number;
  /** Next frame in order. The first one after a (re)sync is always a keyframe. */
  emit(seq: number, frame: T): void;
  requestKeyframe(): void;
  log?(text: string): void;
}

export class Reorderer<T extends Sequenced> {
  /** null = syncing: waiting for a keyframe to start from. */
  private nextSeq: number | null = null;
  private buf = new Map<number, T>();
  private lostSeqs = new Set<number>();
  private gapSince = 0;
  private keyRequestedAt = -Infinity;
  dropped = 0;

  constructor(private hooks: ReorderHooks<T>) {}

  get syncing(): boolean { return this.nextSeq === null; }
  get buffered(): number { return this.buf.size; }

  /** New session: seq restarts, nothing old is useful. The caller requests a keyframe. */
  reset(): void {
    this.clear();
    this.nextSeq = null;
    this.keyRequestedAt = -Infinity;
  }

  /**
   * Wait for the next keyframe. `clear` drops buffered frames (encoder was
   * recreated, its seq may restart); otherwise they are kept, because the
   * keyframe they follow may still be in flight.
   */
  resync(clear = false): void {
    if (clear) this.clear();
    this.nextSeq = null;
    this.gapSince = 0;
    this.requestKeyframe(true);
  }

  push(seq: number, frame: T): void {
    seq >>>= 0;
    if (this.nextSeq !== null && seqLt(seq, this.nextSeq)) { this.dropped++; return; } // late
    if (this.buf.has(seq)) { this.dropped++; return; } // duplicate
    this.buf.set(seq, frame);
    if (this.nextSeq === null) {
      if (frame.key) this.startAt(seq);
      else this.trimSync();
      return;
    }
    this.drain();
  }

  /** The stream carrying `seq` failed: no point waiting for it. */
  lost(seq: number): void {
    seq >>>= 0;
    if (this.nextSeq === null || seqLt(seq, this.nextSeq)) return;
    this.lostSeqs.add(seq);
    this.drain();
  }

  /** Call periodically (e.g. every 50 ms). */
  tick(): void {
    if (this.gapSince && this.hooks.now() - this.gapSince > GAP_TIMEOUT_MS) {
      this.gap(`frame ${this.nextSeq} timed out`);
    }
    if (this.nextSeq === null) this.requestKeyframe(false);
  }

  requestKeyframe(force: boolean): void {
    const now = this.hooks.now();
    if (!force && now - this.keyRequestedAt < KEYFRAME_RETRY_MS) return;
    this.keyRequestedAt = now;
    this.hooks.requestKeyframe();
  }

  private clear() {
    this.dropped += this.buf.size;
    this.buf.clear();
    this.lostSeqs.clear();
    this.gapSince = 0;
  }

  /** Start decoding at keyframe `k`, discarding everything older. */
  private startAt(k: number) {
    for (const s of this.buf.keys()) {
      if (seqLt(s, k)) { this.buf.delete(s); this.dropped++; }
    }
    for (const s of this.lostSeqs) if (seqLt(s, k)) this.lostSeqs.delete(s);
    this.nextSeq = k;
    this.gapSince = 0;
    this.drain();
  }

  private trimSync() {
    while (this.buf.size > SYNC_MAX_BUFFERED) {
      let oldest: number | null = null;
      for (const s of this.buf.keys()) if (oldest === null || seqLt(s, oldest)) oldest = s;
      this.buf.delete(oldest!);
      this.dropped++;
    }
  }

  private drain() {
    while (this.nextSeq !== null && this.buf.has(this.nextSeq)) {
      const seq = this.nextSeq;
      const f = this.buf.get(seq)!;
      this.buf.delete(seq);
      this.nextSeq = seqNext(seq);
      this.hooks.emit(seq, f);
    }
    if (this.nextSeq === null) return;
    if (this.lostSeqs.has(this.nextSeq)) { this.gap(`frame ${this.nextSeq} lost`); return; }
    if (this.buf.size === 0) { this.gapSince = 0; return; }
    if (!this.gapSince) this.gapSince = this.hooks.now();
    if (this.buf.size > GAP_MAX_BUFFERED) this.gap(`frame ${this.nextSeq} missing, ${this.buf.size} waiting`);
  }

  /** Give up on the missing frame: jump to the lowest buffered keyframe, or resync. */
  private gap(why: string) {
    this.hooks.log?.(`gap (${why})`);
    const from = this.nextSeq ?? 0;
    let key: number | null = null;
    for (const [s, f] of this.buf) {
      if (f.key && (key === null || seqDiff(s, from) < seqDiff(key, from))) key = s;
    }
    if (key !== null) { this.startAt(key); return; }
    this.resync(false);
  }
}

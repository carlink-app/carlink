/** Fixed-size window of samples with percentiles. */
export class Samples {
  private buf: number[] = [];
  constructor(private max = 300) {}
  push(v: number): void {
    this.buf.push(v);
    if (this.buf.length > this.max) this.buf.splice(0, this.buf.length - this.max);
  }
  clear(): void { this.buf = []; }
  pct(p: number): number | null {
    if (!this.buf.length) return null;
    const s = Float64Array.from(this.buf).sort();
    return s[Math.min(s.length - 1, Math.floor(p * s.length))];
  }
}

/** A frame this much later than its phone timestamp says is a visible freeze. */
export const STALL_MS = 250;

/**
 * Gaps between drawn frames: the longest one (per stats window) and freezes,
 * frames drawn more than STALL_MS later than their phone timestamps say (a
 * still screen has a big gap in both and doesn't count).
 */
export class DrawGaps {
  private lastDraw = 0;
  /** Phone timestamp (µs) of the last drawn frame. */
  private lastPts = 0;
  maxGap: number | null = null;
  stalls = 0;

  /** A frame drawn at [now] (ms) with phone timestamp [pts] (µs); true for the first since reset. */
  draw(now: number, pts: number): boolean {
    const first = !this.lastDraw;
    if (!first) {
      const gap = now - this.lastDraw;
      this.maxGap = Math.max(this.maxGap ?? 0, gap);
      const ptsGapMs = (pts - this.lastPts) / 1000;
      if (gap - ptsGapMs > STALL_MS) this.stalls++;
    }
    this.lastDraw = now;
    this.lastPts = pts;
    return first;
  }

  /** Longest gap since the last call (rounded ms; null = fewer than two frames), then starts a new window. */
  takeMaxGapMs(): number | null {
    const g = this.maxGap === null ? null : Math.round(this.maxGap);
    this.maxGap = null;
    return g;
  }

  /** New session: freeze counting starts over. */
  reset(): void {
    this.lastDraw = 0;
    this.stalls = 0;
    this.maxGap = null;
  }
}

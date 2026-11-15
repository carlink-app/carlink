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

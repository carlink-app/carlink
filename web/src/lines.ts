// JSON-lines framing for the control stream.

/** Incoming lines longer than this are a protocol violation (the phone's apps list with icons can be big). */
export const MAX_INCOMING_LINE = 8 << 20;

/** Feed decoded text chunks; get back the complete JSON objects. Bad lines are skipped. */
export class LineSplitter {
  private buf = '';
  constructor(private onBad: (line: string, why: string) => void = () => {}) {}

  push(chunk: string): unknown[] {
    this.buf += chunk;
    const out: unknown[] = [];
    let nl: number;
    while ((nl = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, nl).trim(); // trim also handles CRLF
      this.buf = this.buf.slice(nl + 1);
      if (!line) continue;
      try {
        const v: unknown = JSON.parse(line);
        if (v && typeof v === 'object' && !Array.isArray(v)) out.push(v);
        else this.onBad(line, 'not an object');
      } catch {
        this.onBad(line, 'bad JSON');
      }
    }
    if (this.buf.length > MAX_INCOMING_LINE) throw new Error('Control line too long');
    return out;
  }
}

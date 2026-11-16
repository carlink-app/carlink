// QR pairing (see docs/PROTOCOL.md "Pairing"): the page shows a one-time
// secret as a QR code; the phone scans it and, for a short while, accepts the
// secret instead of the pairing code; the first connection made with it gets
// the real code in a `paired` message.
import qrcode from 'qrcode-generator';
import { CODE_LENGTH, normalizeCode } from './cert';

/** Same alphabet as the phone's pairing codes (no 0/O, 1/I). */
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const SECRET_LENGTH = 20; // 100 bits
export const QR_PREFIX = 'TSLINK-PAIR:';

export function newSecret(random: (a: Uint8Array<ArrayBuffer>) => Uint8Array = (a) => crypto.getRandomValues(a)): string {
  const bytes = random(new Uint8Array(SECRET_LENGTH));
  return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join(''); // 256 % 32 == 0: unbiased
}

export const qrText = (secret: string) => QR_PREFIX + secret;

/** A pairing code from the phone's `paired` message, normalized; null if malformed. */
export function pairedCode(v: unknown): string | null {
  if (typeof v !== 'string') return null;
  const c = normalizeCode(v);
  if (c.length !== CODE_LENGTH || [...c].some((ch) => !ALPHABET.includes(ch))) return null;
  return c.replace(/(.{4})(?=.)/g, '$1-');
}

/** Dark modules of the QR code for [text] (error correction M). */
export function qrModules(text: string): boolean[][] {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  return Array.from({ length: n }, (_, r) => Array.from({ length: n }, (_, c) => qr.isDark(r, c)));
}

/** Draw [modules] on [canvas], [cssSize] px square, black on white with a 4-module quiet zone. */
export function drawQr(canvas: HTMLCanvasElement, modules: boolean[][], cssSize: number, dpr: number) {
  const n = modules.length + 8;
  const px = Math.max(1, Math.floor((cssSize * dpr) / n));
  canvas.width = canvas.height = px * n;
  canvas.style.width = canvas.style.height = `${(px * n) / dpr}px`;
  const g = canvas.getContext('2d')!;
  g.fillStyle = '#fff';
  g.fillRect(0, 0, canvas.width, canvas.height);
  g.fillStyle = '#000';
  modules.forEach((row, r) => row.forEach((dark, c) => { if (dark) g.fillRect((c + 4) * px, (r + 4) * px, px, px); }));
}

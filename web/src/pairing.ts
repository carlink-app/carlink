// QR pairing (see docs/PROTOCOL.md "Pairing"): the page shows a one-time
// secret as a QR code; the phone scans it and, for a short while, accepts the
// secret instead of the pairing code; the first connection made with it gets
// the real code in a `paired` message.
import qrcode from 'qrcode-generator';
import { CODE_LENGTH, groupCode, normalizeCode } from './cert';

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
  return groupCode(c);
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

/**
 * What the pairing screen (first visit and the gear) opens with: the QR code,
 * unless a paired session is live (a QR would replace it: "Pair again" instead).
 */
export function pairingView(hasCode: boolean, connected: boolean): 'qr' | 'paired' {
  return hasCode && connected ? 'paired' : 'qr';
}

/** Retry pace while the QR code is up (the phone accepts the secret once it scanned it). */
const PAIRING_RETRY_MS = 1000;
/** Retry pace right after pairing, while the phone switches to the real code. */
const PAIRED_RETRY_MS = 300;
/**
 * Handshake limit while pairing: one started just as the phone switched codes
 * goes unanswered (measured: ~5 s lost twice). A car on the hotspot answers in ms.
 */
const PAIRING_CONNECT_MS = 1500;

/**
 * Connection pace around pairing: fast retries and short handshakes while the
 * QR code is up or until [quietUntil] after pairing; otherwise the normal
 * backoff and handshake time (undefined).
 */
export function pairingPace(pairing: boolean, now: number, quietUntil: number): { retryMs?: number; connectTimeoutMs?: number } {
  if (pairing) return { retryMs: PAIRING_RETRY_MS, connectTimeoutMs: PAIRING_CONNECT_MS };
  if (now < quietUntil) return { retryMs: PAIRED_RETRY_MS, connectTimeoutMs: PAIRING_CONNECT_MS };
  return { retryMs: undefined, connectTimeoutMs: undefined };
}

/** Handshakes in a row that got no answer before the QR screen says the address may be wrong. */
const UNREACHABLE_AFTER = 2;

/**
 * The line under the QR code. A phone that answers but hasn't scanned yet
 * fails the handshake at once: keep waiting. Handshakes that get no answer
 * at all ([unanswered] in a row) mean the phone isn't at that address.
 */
export function qrStatusText(unanswered: number, host: string, port: number): { text: string; warn: boolean } {
  if (unanswered < UNREACHABLE_AFTER) return { text: 'Waiting for your phone…', warn: false };
  return {
    text: `Can't reach the phone at ${host}:${port}. Is the car on the phone's hotspot? Otherwise check the address under Advanced.`,
    warn: true,
  };
}

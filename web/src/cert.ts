// Deterministic WebTransport certificate derived from the pairing code.
// Must produce byte-identical DER to core/src/cert.rs (see docs/PROTOCOL.md).
// WebCrypto ECDSA uses a random nonce, so signing uses @noble/curves (RFC 6979).

import { p256 } from '@noble/curves/nist.js';
import { sha256 } from '@noble/hashes/sha2.js';

const KEY_LABEL = 'carlink-wt-key-v1:';
export const WEEK_SECS = 7 * 24 * 3600;
const VALID_SECS = 13 * 24 * 3600;
// 1970-01-01 was a Thursday; Monday 1970-01-05 00:00 UTC.
const FIRST_MONDAY = 4 * 24 * 3600;
// P-256 group order.
const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;

const enc = new TextEncoder();

/** Codes are shown grouped ("ABCD-EFGH-JKLM"); case and separators don't matter. */
export function normalizeCode(code: string): string {
  return code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
}

/** Characters in a pairing code (phone side: Prefs.newCode). */
export const CODE_LENGTH = 12;

/**
 * As typed in the pairing field: upper case, only letters and digits, grouped
 * by four with dashes ("abcdefg" -> "ABCD-EFG"), at most CODE_LENGTH
 * characters. [caret] (index in [raw]) comes back moved to the same character.
 */
export function formatCode(raw: string, caret = raw.length): { value: string; caret: number } {
  const before = normalizeCode(raw.slice(0, caret)).length;
  const chars = normalizeCode(raw).slice(0, CODE_LENGTH);
  const value = chars.replace(/(.{4})(?=.)/g, '$1-');
  const n = Math.min(before, chars.length);
  // Position after the n-th character: n plus the dashes before it.
  return { value, caret: n + Math.floor(Math.max(0, n - 1) / 4) };
}

/** Monday 00:00 UTC of the week containing `unixSecs`. */
export function weekStart(unixSecs: number): number {
  return FIRST_MONDAY + Math.floor((unixSecs - FIRST_MONDAY) / WEEK_SECS) * WEEK_SECS;
}

function secretKey(code: string): Uint8Array {
  let h = sha256(enc.encode(KEY_LABEL + normalizeCode(code)));
  for (;;) {
    const d = BigInt('0x' + toHex(h));
    if (d !== 0n && d < N) return h;
    h = sha256(h); // practically never happens
  }
}

export interface WtCert {
  der: Uint8Array;
  hash: Uint8Array;
  notBefore: number;
}

export function makeCert(code: string, unixSecs: number): WtCert {
  const key = secretKey(code);
  const notBefore = weekStart(unixSecs);
  const pub = p256.getPublicKey(key, false); // 04 || X || Y
  const tbs = tbsCertificate(notBefore, pub);
  // prehash: false -> we pass SHA-256(tbs) ourselves, like sign_prehash in Rust.
  const sig = p256.sign(sha256(tbs), key, { prehash: false, lowS: true, format: 'compact' });
  const sigDer = seq(cat(tlv(0x02, derUint(sig.subarray(0, 32))), tlv(0x02, derUint(sig.subarray(32, 64)))));
  const der = seq(cat(tbs, algEcdsaSha256(), tlv(0x03, cat(new Uint8Array([0]), sigDer))));
  return { der, hash: sha256(der), notBefore };
}

/** Hashes for `serverCertificateHashes`: this week and last week (clock skew, rotation). */
export function certHashes(code: string, unixSecs: number): Uint8Array[] {
  return [makeCert(code, unixSecs).hash, makeCert(code, unixSecs - WEEK_SECS).hash];
}

function tbsCertificate(notBefore: number, pubUncompressed: Uint8Array): Uint8Array {
  const version = tlv(0xa0, tlv(0x02, new Uint8Array([2])));
  const serial = tlv(0x02, derUint(u64be(notBefore)));
  const name = seq(tlv(0x31, seq(cat(tlv(0x06, new Uint8Array([0x55, 0x04, 0x03])), tlv(0x0c, enc.encode('carlink'))))));
  const validity = seq(cat(utcTime(notBefore), utcTime(notBefore + VALID_SECS)));
  const alg = seq(cat(
    tlv(0x06, new Uint8Array([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01])), // id-ecPublicKey
    tlv(0x06, new Uint8Array([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07])), // prime256v1
  ));
  const spki = seq(cat(alg, tlv(0x03, cat(new Uint8Array([0]), pubUncompressed))));
  return seq(cat(version, serial, algEcdsaSha256(), name, validity, name, spki, extensions()));
}

/**
 * [3] EXPLICIT extensions. Chromium's WebTransport certificate parser rejects
 * certificates without extensions. Same set as cert.rs `extensions()`.
 */
function extensions(): Uint8Array {
  const b = (...x: number[]) => new Uint8Array(x);
  const keyUsage = seq(cat(
    tlv(0x06, b(0x55, 0x1d, 0x0f)),
    tlv(0x01, b(0xff)),
    tlv(0x04, tlv(0x03, b(0x07, 0x80))), // digitalSignature
  ));
  const extKeyUsage = seq(cat(
    tlv(0x06, b(0x55, 0x1d, 0x25)),
    tlv(0x04, seq(tlv(0x06, b(0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01)))), // serverAuth
  ));
  const basicConstraints = seq(cat(
    tlv(0x06, b(0x55, 0x1d, 0x13)),
    tlv(0x01, b(0xff)),
    tlv(0x04, seq(b())),
  ));
  return tlv(0xa3, seq(cat(keyUsage, extKeyUsage, basicConstraints)));
}

function algEcdsaSha256(): Uint8Array {
  return seq(tlv(0x06, new Uint8Array([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02])));
}

const seq = (content: Uint8Array) => tlv(0x30, content);

function tlv(tag: number, content: Uint8Array): Uint8Array {
  const n = content.length;
  const head = n < 0x80 ? [tag, n] : n < 0x100 ? [tag, 0x81, n] : [tag, 0x82, n >> 8, n & 0xff];
  return cat(new Uint8Array(head), content);
}

/** Minimal two's-complement encoding of an unsigned big-endian integer. */
function derUint(be: Uint8Array): Uint8Array {
  let i = 0;
  while (i + 1 < be.length && be[i] === 0) i++;
  const rest = be.subarray(i);
  return rest[0] & 0x80 ? cat(new Uint8Array([0]), rest) : rest.slice();
}

function u64be(v: number): Uint8Array {
  const b = new Uint8Array(8);
  new DataView(b.buffer).setBigUint64(0, BigInt(v));
  return b;
}

function utcTime(unixSecs: number): Uint8Array {
  const d = new Date(unixSecs * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  const txt = p(d.getUTCFullYear() % 100) + p(d.getUTCMonth() + 1) + p(d.getUTCDate())
    + p(d.getUTCHours()) + p(d.getUTCMinutes()) + p(d.getUTCSeconds()) + 'Z';
  return tlv(0x17, enc.encode(txt));
}

function cat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

export function toHex(b: Uint8Array): string {
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

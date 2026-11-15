// Control-stream authentication (PROTOCOL.md "Authentication"): the page
// proves it knows the pairing code with an HMAC over the phone's nonce.

import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { normalizeCode } from './cert';

const enc = new TextEncoder();

export function authMac(code: string, nonce: Uint8Array): Uint8Array {
  const key = sha256(enc.encode('carlink-auth-key-v1:' + normalizeCode(code)));
  const label = enc.encode('carlink-auth-v1|');
  const msg = new Uint8Array(label.length + nonce.length);
  msg.set(label);
  msg.set(nonce, label.length);
  return hmac(sha256, key, msg);
}

export function toBase64(b: Uint8Array): string {
  let s = '';
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s);
}

export function fromBase64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

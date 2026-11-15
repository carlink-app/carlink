import { describe, expect, it } from 'vitest';
import { authMac, fromBase64, toBase64 } from './auth';
import { toHex } from './cert';

describe('auth', () => {
  it('matches the PROTOCOL.md vector', () => {
    const nonce = Uint8Array.from({ length: 32 }, (_, i) => i);
    expect(toHex(authMac('abcd-efgh-jklm', nonce))).toBe('6b5183c6e69c925ba2965bdb3a9b95730378219ba4f8fb84147ca63b33aaf1ad');
    expect(toHex(authMac('ABCDEFGHJKLM', nonce))).toBe('6b5183c6e69c925ba2965bdb3a9b95730378219ba4f8fb84147ca63b33aaf1ad');
  });
  it('base64 round-trips', () => {
    const b = Uint8Array.from({ length: 256 }, (_, i) => i);
    expect(fromBase64(toBase64(b))).toEqual(b);
    expect(toBase64(new Uint8Array([0, 1, 2]))).toBe('AAEC');
  });
});

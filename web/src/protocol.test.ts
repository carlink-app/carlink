import { describe, expect, it } from 'vitest';
import { HEADER_LEN, parseHeader, parseHeaderPrefix, writeHeader } from './protocol';

const hex = (s: string) => Uint8Array.from(s.replace(/\s/g, '').match(/../g)!, (b) => parseInt(b, 16));
const GOLDEN = '01 01 0102 fffffffe 0000010000000005 00000000075bcd15';
const EXPECTED = { kind: 1, flags: 1, pane: 0x0102, seq: 0xfffffffe, ptsUs: 2 ** 40 + 5, sendUs: 123456789 };

describe('frame header', () => {
  it('parses the PROTOCOL.md golden vector', () => {
    expect(parseHeader(hex(GOLDEN))).toEqual(EXPECTED);
  });
  it('parses a subarray with a non-zero byteOffset', () => {
    const big = new Uint8Array(7 + HEADER_LEN + 3);
    big.set(hex(GOLDEN), 7);
    expect(parseHeader(big.subarray(7))).toEqual(EXPECTED);
    expect(parseHeaderPrefix(big.subarray(7, 15))).toEqual({ kind: 1, flags: 1, pane: 0x0102, seq: 0xfffffffe });
  });
  it('rejects short buffers', () => {
    expect(parseHeader(hex(GOLDEN).subarray(0, 23))).toBeNull();
    expect(parseHeaderPrefix(new Uint8Array(7))).toBeNull();
  });
  it('writeHeader round-trips to the golden bytes', () => {
    const out = new Uint8Array(HEADER_LEN);
    writeHeader(EXPECTED, out);
    expect(out).toEqual(hex(GOLDEN));
  });
});

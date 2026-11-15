import { describe, expect, it } from 'vitest';
import { codecString, splitAccessUnits } from './h26x';

const sc = [0, 0, 0, 1];
describe('h26x', () => {
  it('derives an avc1 codec string from the SPS', () => {
    const au = new Uint8Array([...sc, 0x09, 0xf0, ...sc, 0x67, 0x64, 0x00, 0x32, 0xac, ...sc, 0x68, 0xee, ...sc, 0x65, 0x88]);
    expect(codecString(au, 'h264')).toBe('avc1.640032');
    expect(codecString(new Uint8Array([...sc, 0x41, 0x9a]), 'h264')).toBeNull();
  });
  it('derives an hvc1 codec string from the SPS', () => {
    // SPS NAL header 42 01, then sps_vps_id/max_sub_layers byte, then PTL: Main, compat bit 1+2, level 150.
    const sps = [0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x00, 0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96, 0xa0];
    expect(codecString(new Uint8Array([...sc, ...sps]), 'hevc')).toBe('hvc1.1.6.L150.90');
  });
  it('splits access units at AUDs', () => {
    const b = new Uint8Array([...sc, 0x09, 0xf0, ...sc, 0x65, 1, ...sc, 0x09, 0xf0, ...sc, 0x41, 2]);
    const aus = splitAccessUnits(b, 'h264');
    expect(aus.length).toBe(2);
    expect(aus[0].length).toBe(12);
  });
});

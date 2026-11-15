import { describe, expect, it } from 'vitest';
import { clipProblem, NO_CLIP } from './demo';

const sc = [0, 0, 0, 1];
const aud = [...sc, 0x09, 0xf0];
const sps = [...sc, 0x67, 0x64, 0x00, 0x32, 0xac];
const clip = new Uint8Array([...aud, ...sps, ...sc, 0x68, 0xee, ...sc, 0x65, 0x88, ...aud, ...sc, 0x41, 0x9a]);

describe('clipProblem', () => {
  it('accepts an Annex B H.264 clip starting with SPS', () => {
    expect(clipProblem(200, 'application/octet-stream', clip)).toBeNull();
    expect(clipProblem(200, null, clip)).toBeNull();
  });
  it('missing clip: 404 or an HTML page (SPA fallback) -> clear message', () => {
    expect(clipProblem(404, 'text/html', null)).toBe(NO_CLIP);
    expect(clipProblem(200, 'text/html; charset=utf-8', null)).toBe(NO_CLIP);
    expect(clipProblem(200, null, new Uint8Array())).toBe(NO_CLIP);
  });
  it('other HTTP errors', () => {
    expect(clipProblem(500, 'text/plain', null)).toBe('Demo video: HTTP 500');
  });
  it('rejects bytes that are not a playable clip', () => {
    const html = new TextEncoder().encode('<!doctype html><html></html>');
    expect(clipProblem(200, 'application/octet-stream', html)).toMatch(/not an Annex B/);
    // Access units but no SPS in the first one.
    expect(clipProblem(200, null, new Uint8Array([...aud, ...sc, 0x41, 0x9a]))).toMatch(/not an Annex B/);
  });
});
